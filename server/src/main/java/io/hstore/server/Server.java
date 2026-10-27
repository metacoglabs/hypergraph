// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server;

import io.hstore.db.HypergraphDatabase;
import io.hstore.db.query.Ast.Statement;
import io.hstore.db.query.Parser;
import io.hstore.db.query.QueryResult;
import io.hstore.db.query.Session;
import io.hstore.db.security.Scram;
import io.hstore.engine.HStoreException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

final class Server implements AutoCloseable {

    record Policy(String authentication, int maxConnections, int idleTimeoutSeconds, int authenticationTimeoutSeconds, long maxMessageBytes,
                  StatementLogger statements, boolean logConnections, Optional<SSLContext> tls, Duration failureDelay) {

        static Policy permissive() {
            return new Policy("auto", 200, 0, 30, 64L << 20, StatementLogger.silent(), false, Optional.empty(), Duration.ZERO);
        }

        static Policy from(ServerConfig config, Optional<SSLContext> tls) {
            return new Policy(config.string(Setting.AUTHENTICATION).toLowerCase(), config.integer(Setting.MAX_CONNECTIONS),
                    config.integer(Setting.IDLE_TIMEOUT_SECONDS), config.integer(Setting.AUTHENTICATION_TIMEOUT_SECONDS),
                    (long) config.integer(Setting.MAX_MESSAGE_MB) << 20, StatementLogger.from(config), config.flag(Setting.LOG_CONNECTIONS), tls,
                    Duration.ofMillis(Math.clamp(config.integer(Setting.AUTHENTICATION_FAILURE_DELAY_MS), 0, 10_000)));
        }

        Policy withTls(SSLContext context) {
            return new Policy(authentication, maxConnections, idleTimeoutSeconds, authenticationTimeoutSeconds, maxMessageBytes, statements,
                    logConnections, Optional.of(context), failureDelay);
        }

        Policy withTimeouts(int idle, int authentication) {
            return new Policy(this.authentication, maxConnections, idle, authentication, maxMessageBytes, statements, logConnections, tls,
                    failureDelay);
        }

        Policy withAuthentication(String mode) {
            return new Policy(mode, maxConnections, idleTimeoutSeconds, authenticationTimeoutSeconds, maxMessageBytes, statements,
                    logConnections, tls, failureDelay);
        }

        Policy withFailureDelay(Duration delay) {
            return new Policy(authentication, maxConnections, idleTimeoutSeconds, authenticationTimeoutSeconds, maxMessageBytes, statements,
                    logConnections, tls, delay);
        }

        boolean openToOtherHosts() {
            return authentication.equals("off");
        }

        boolean admits(HypergraphDatabase database, InetAddress peer) {
            return peer.isLoopbackAddress() || openToOtherHosts() || authenticationRequired(database);
        }

        void delayFailure() {
            if (!failureDelay.isZero()) {
                try {
                    Thread.sleep(failureDelay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        boolean authenticationRequired(HypergraphDatabase database) {
            return switch (authentication) {
                case "on" -> true;
                case "off" -> false;
                default -> database.requiresAuthentication();
            };
        }
    }

    private static final System.Logger LOG = System.getLogger("hstore.server");
    private static final int MAX_AUTHENTICATION_ATTEMPTS = 5;
    private static final int HANDSHAKE_TIMEOUT_MILLIS = 10_000;
    private static final long UNAUTHENTICATED_MESSAGE_BYTES = 64 << 10;
    private static final int MAX_WAITING_PER_ADDRESS = 16;

    private final HypergraphDatabase database;
    private final Policy policy;
    private final ServerSocket listener;
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
    private final Map<InetAddress, Integer> waiting = new ConcurrentHashMap<>();
    private final AtomicLong served = new AtomicLong();
    private volatile boolean running = true;

    Server(HypergraphDatabase database, InetSocketAddress address) {
        this(database, address, Policy.permissive());
    }

    Server(HypergraphDatabase database, InetSocketAddress address, Policy policy) {
        this.database = database;
        this.policy = policy;
        try {
            this.listener = policy.tls().isPresent() ? policy.tls().get().getServerSocketFactory().createServerSocket() : new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(address);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot listen on " + address, e);
        }
    }

    int port() {
        return listener.getLocalPort();
    }

    void serve() {
        LOG.log(System.Logger.Level.INFO, "listening on {0}:{1}{2}", listener.getInetAddress().getHostAddress(), String.valueOf(port()),
                policy.tls().isPresent() ? " with TLS" : "");
        if (!listener.getInetAddress().isLoopbackAddress()) {
            if (policy.openToOtherHosts()) {
                LOG.log(System.Logger.Level.WARNING, "authentication = off and the server listens on {0}: every client that can reach this port "
                        + "has full access as the system administrator", listener.getInetAddress().getHostAddress());
            } else if (!policy.authenticationRequired(database)) {
                LOG.log(System.Logger.Level.WARNING, "the database has no users, so connections from other hosts are refused until "
                        + "CREATE USER creates one");
            }
        }
        List<String> legacy = database.usersWithOldPasswords();
        if (!legacy.isEmpty()) {
            LOG.log(System.Logger.Level.WARNING, "{0} users have passwords stored by HStore 0.1 and cannot sign in over this port until each "
                    + "signs in once through Studio or gets a new password with ALTER USER: {1}", legacy.size(), String.join(", ", legacy));
        }
        while (running) {
            try {
                Socket socket = listener.accept();
                if (open.size() >= policy.maxConnections()) {
                    connections.submit(() -> reject(socket, "too many connections"));
                    continue;
                }
                if (waiting.merge(socket.getInetAddress(), 1, Integer::sum) > MAX_WAITING_PER_ADDRESS) {
                    settle(socket.getInetAddress());
                    connections.submit(() -> reject(socket, "too many unauthenticated connections from this address"));
                    continue;
                }
                open.add(socket);
                connections.submit(() -> handle(socket));
            } catch (SocketException closed) {
                if (running) {
                    throw new UncheckedIOException(closed);
                }
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void reject(Socket socket, String reason) {
        LOG.log(System.Logger.Level.WARNING, "connection from {0} refused: {1} (max_connections={2})",
                socket.getRemoteSocketAddress(), reason, policy.maxConnections());
        try (socket) {
            socket.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
            WireProtocol.respond(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                    "error [ABORTED_RESOURCE_LIMIT]: " + reason);
        } catch (IOException _) {
        }
    }

    private void handle(Socket socket) {
        long connection = served.incrementAndGet();
        long started = System.nanoTime();
        String peer = String.valueOf(socket.getRemoteSocketAddress());
        boolean active = false;
        boolean unsettled = true;
        Session session = new Session(database);
        session.refuseClearTextPasswords(UPGRADE);
        try (socket; session) {
            if (socket instanceof SSLSocket secure) {
                secure.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
                secure.startHandshake();
            }
            session.origin(socket.getInetAddress().getHostAddress());
            InputStream in = new BufferedInputStream(socket.getInputStream());
            Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            if (!policy.admits(database, socket.getInetAddress())) {
                refuseOtherHost(out, connection, peer);
                return;
            }
            int failures = 0;
            boolean pending = policy.authenticationRequired(database);
            timeout(socket, session);
            unsettled = unsettled(socket, session, unsettled);
            WireProtocol.respond(out, "hstore connection " + connection + " at generation " + database.engine().transactions().current().id()
                    + (pending ? "; authentication required" : ""));
            for (Optional<String> request = receive(in, out, session); request.isPresent() && running; request = receive(in, out, session)) {
                if (!active && WireProtocol.http(request.get())) {
                    LOG.log(System.Logger.Level.WARNING, "connection {0} from {1} closed: it sent an HTTP request", connection, peer);
                    return;
                }
                if (!active && policy.logConnections()) {
                    LOG.log(System.Logger.Level.INFO, "connection {0} received from {1}", connection, peer);
                }
                active = true;
                if (!policy.admits(database, socket.getInetAddress())) {
                    refuseOtherHost(out, connection, peer);
                    return;
                }
                if (request.get().startsWith(SASL)) {
                    boolean before = session.authenticated();
                    if (!sasl(session, request.get(), out, socket, connection, peer) && !before && ++failures >= MAX_AUTHENTICATION_ATTEMPTS) {
                        LOG.log(System.Logger.Level.WARNING, "connection {0} from {1} closed after {2} failed authentication attempts",
                                connection, peer, failures);
                        return;
                    }
                    timeout(socket, session);
                    unsettled = unsettled(socket, session, unsettled);
                    continue;
                }
                if (!session.authenticated() && policy.authenticationRequired(database)) {
                    if (!authenticate(session, request.get(), out, connection, peer) && ++failures >= MAX_AUTHENTICATION_ATTEMPTS) {
                        LOG.log(System.Logger.Level.WARNING, "connection {0} from {1} closed after {2} failed authentication attempts",
                                connection, peer, failures);
                        return;
                    }
                    timeout(socket, session);
                    unsettled = unsettled(socket, session, unsettled);
                    continue;
                }
                WireProtocol.respond(out, execute(session, request.get(), connection));
            }
        } catch (SSLException handshake) {
            LOG.log(System.Logger.Level.INFO, "connection {0} from {1} failed the TLS handshake: {2}", connection, peer, handshake.getMessage());
        } catch (WireProtocol.TooLarge large) {
            LOG.log(System.Logger.Level.WARNING, "connection {0} from {1} closed: {2}", connection, peer, large.getMessage());
        } catch (SocketTimeoutException idle) {
            if (!active && socket instanceof SSLSocket) {
                LOG.log(System.Logger.Level.INFO, "connection {0} from {1} did not complete the TLS handshake in time", connection, peer);
                return;
            }
            if (!session.authenticated() && policy.authenticationRequired(database)) {
                LOG.log(System.Logger.Level.INFO, "connection {0} from {1} closed: no authentication within {2}s", connection, peer,
                        policy.authenticationTimeoutSeconds());
                return;
            }
            LOG.log(System.Logger.Level.INFO, "connection {0} closed after {1}s idle", connection, policy.idleTimeoutSeconds());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "connection " + connection + " ended", e);
        } finally {
            open.remove(socket);
            if (unsettled) {
                settle(socket.getInetAddress());
            }
            if (policy.logConnections()) {
                LOG.log(active ? System.Logger.Level.INFO : System.Logger.Level.DEBUG, "disconnection: connection {0} from {1} session time {2} ms",
                        connection, peer, (System.nanoTime() - started) / 1_000_000);
            }
        }
    }

    private static final String OTHER_HOSTS = "this database has no users, so it only accepts connections from its own host; "
            + "create a user with CREATE USER, or set authentication = off to let other hosts in without authentication";

    private void refuseOtherHost(Writer out, long connection, String peer) throws IOException {
        LOG.log(System.Logger.Level.WARNING, "connection {0} from {1} refused: no users exist and the client is not on this host", connection, peer);
        WireProtocol.respond(out, "error [AUTHENTICATION_REQUIRED]: " + OTHER_HOSTS);
    }

    private boolean unsettled(Socket socket, Session session, boolean unsettled) {
        if (unsettled && trusted(session)) {
            settle(socket.getInetAddress());
            return false;
        }
        return unsettled;
    }

    private void settle(InetAddress address) {
        waiting.computeIfPresent(address, (_, count) -> count <= 1 ? null : count - 1);
    }

    private boolean trusted(Session session) {
        return session.authenticated() || !policy.authenticationRequired(database);
    }

    private void timeout(Socket socket, Session session) throws IOException {
        socket.setSoTimeout(Math.max(0, trusted(session) ? policy.idleTimeoutSeconds() : policy.authenticationTimeoutSeconds()) * 1000);
    }

    private Optional<String> receive(InputStream in, Writer out, Session session) throws IOException {
        long limit = trusted(session) ? policy.maxMessageBytes() : UNAUTHENTICATED_MESSAGE_BYTES;
        try {
            return WireProtocol.receive(in, limit);
        } catch (WireProtocol.TooLarge large) {
            WireProtocol.respond(out, "error [ABORTED_RESOURCE_LIMIT]: " + large.getMessage());
            throw large;
        }
    }

    private boolean authenticate(Session session, String request, Writer out, long connection, String peer) throws IOException {
        List<Statement> statements;
        try {
            statements = Parser.script(request);
        } catch (HStoreException failure) {
            WireProtocol.respond(out, "error [" + failure.code() + "]: " + failure.getMessage());
            return false;
        }
        if (statements.stream().anyMatch(Server::signsIn)) {
            refuseCleartext(out, connection, peer);
            return false;
        }
        WireProtocol.respond(out, "error [AUTHENTICATION_REQUIRED]: authenticate first with SASL " + Scram.MECHANISM
                + "; see the wire protocol documentation");
        return false;
    }

    static final String SASL = "SASL ";
    static final String UPGRADE = "this server no longer accepts passwords in clear text; upgrade your HStore client to version "
            + Main.VERSION + " or later, which signs in with " + Scram.MECHANISM;

    private static boolean signsIn(Statement statement) {
        return switch (statement) {
            case Statement.Authenticate _ -> true;
            case Statement.At(long _, Statement inner) -> signsIn(inner);
            case Statement.AsOf(long _, Statement inner) -> signsIn(inner);
            default -> false;
        };
    }

    private void refuseCleartext(Writer out, long connection, String peer) throws IOException {
        LOG.log(System.Logger.Level.WARNING, "connection {0} from {1} sent a clear-text password, which is refused; the client needs an upgrade",
                connection, peer);
        WireProtocol.respond(out, "error [AUTHENTICATION_FAILED]: " + UPGRADE);
    }

    private boolean sasl(Session session, String request, Writer out, Socket socket, long connection, String peer) throws IOException {
        String[] parts = request.substring(SASL.length()).strip().split(" ");
        try {
            if (parts.length == 2) {
                String serverFirst = session.scramFirst(parts[0], decode(parts[1]), channelBinding(socket));
                WireProtocol.respond(out, "sasl " + Base64.getEncoder().encodeToString(serverFirst.getBytes(StandardCharsets.UTF_8)));
                return true;
            }
            if (parts.length == 1 && !parts[0].isEmpty()) {
                Optional<String> serverFinal = session.scramFinal(decode(parts[0]));
                if (serverFinal.isPresent()) {
                    if (policy.logConnections()) {
                        LOG.log(System.Logger.Level.INFO, "connection {0} authorized: user={1} tenant={2} role={3} method={4}",
                                connection, session.principal().user(), session.principal().tenant(), session.principal().role(),
                                socket instanceof SSLSocket ? Scram.MECHANISM_PLUS : Scram.MECHANISM);
                    }
                    WireProtocol.respond(out, "authenticated as " + session.principal().user() + "\nsasl "
                            + Base64.getEncoder().encodeToString(serverFinal.get().getBytes(StandardCharsets.UTF_8)));
                    return true;
                }
                LOG.log(System.Logger.Level.WARNING, "password authentication failed for user \"{0}\" from {1}",
                        session.scramUser().orElse(""), peer);
                policy.delayFailure();
                WireProtocol.respond(out, "error [AUTHENTICATION_FAILED]: authentication failed");
                return false;
            }
            WireProtocol.respond(out, "error [AUTHENTICATION_FAILED]: expected SASL <mechanism> <client-first-message> or SASL <client-final-message>");
            return false;
        } catch (HStoreException failure) {
            LOG.log(System.Logger.Level.WARNING, "connection {0} from {1}: SASL sign-in failed: {2}", connection, peer, failure.getMessage());
            WireProtocol.respond(out, "error [" + (failure instanceof HStoreException.ResourceLimit ? failure.code() : "AUTHENTICATION_FAILED")
                    + "]: " + failure.getMessage());
            return false;
        }
    }

    private static String decode(String base64) {
        try {
            return new String(Base64.getDecoder().decode(base64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw HStoreException.invalid("malformed SCRAM message: not base64");
        }
    }

    private static Optional<byte[]> channelBinding(Socket socket) {
        return socket instanceof SSLSocket secure ? Scram.endPointOf(secure.getSession().getLocalCertificates()) : Optional.empty();
    }

    private String execute(Session session, String script, long connection) {
        List<String> rendered = new ArrayList<>();
        List<Parser.Sourced> statements;
        try {
            statements = Parser.sourced(script);
        } catch (HStoreException failure) {
            LOG.log(System.Logger.Level.INFO, "connection {0}: {1}", connection,
                    Parser.mentionsAuthenticate(script) ? "a script with AUTHENTICATE could not be parsed" : failure.getMessage());
            return "error [" + failure.code() + "]: " + failure.getMessage();
        }
        if (statements.stream().map(Parser.Sourced::statement).anyMatch(Server::signsIn)) {
            LOG.log(System.Logger.Level.WARNING, "connection {0} sent a clear-text password, which is refused; the client needs an upgrade",
                    connection);
            return "error [AUTHENTICATION_FAILED]: " + UPGRADE;
        }
        for (Parser.Sourced sourced : statements) {
            Statement statement = sourced.statement();
            long started = System.nanoTime();
            try {
                QueryResult result = session.execute(statement);
                rendered.add(session.render(result));
                policy.statements().executed("connection " + connection, session.principal().user(), statement,
                        (System.nanoTime() - started) / 1_000_000, sourced.text());
            } catch (HStoreException failure) {
                LOG.log(failure.retryable() ? System.Logger.Level.INFO : System.Logger.Level.WARNING,
                        "connection {0} user {1}: {2} [{3}]", connection, session.principal().user(), failure.getMessage(), failure.code());
                rendered.add("error [" + failure.code() + (failure.retryable() ? ", retryable" : "") + "]: " + failure.getMessage());
                break;
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.ERROR, "connection " + connection + " statement failed", failure);
                rendered.add("error [INTERNAL]: the statement failed; the server log has the details");
                break;
            }
        }
        return String.join("\n\n", rendered);
    }

    @Override
    public void close() {
        running = false;
        try {
            listener.close();
            for (Socket socket : open) {
                socket.close();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        connections.close();
    }
}

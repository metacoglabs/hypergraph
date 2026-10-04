package io.hstore.server;

import io.hstore.db.HypergraphDatabase;
import io.hstore.db.query.Ast.Statement;
import io.hstore.db.query.Parser;
import io.hstore.db.query.QueryResult;
import io.hstore.db.query.Session;
import io.hstore.engine.HStoreException;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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

    record Policy(String authentication, int maxConnections, int idleTimeoutSeconds, StatementLogger statements, boolean logConnections,
                  Optional<SSLContext> tls) {

        static Policy permissive() {
            return new Policy("auto", 200, 0, StatementLogger.silent(), false, Optional.empty());
        }

        static Policy from(ServerConfig config, Optional<SSLContext> tls) {
            return new Policy(config.string(Setting.AUTHENTICATION).toLowerCase(), config.integer(Setting.MAX_CONNECTIONS),
                    config.integer(Setting.IDLE_TIMEOUT_SECONDS), StatementLogger.from(config), config.flag(Setting.LOG_CONNECTIONS), tls);
        }

        Policy withTls(SSLContext context) {
            return new Policy(authentication, maxConnections, idleTimeoutSeconds, statements, logConnections, Optional.of(context));
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

    private final HypergraphDatabase database;
    private final Policy policy;
    private final ServerSocket listener;
    private final ExecutorService connections = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> open = ConcurrentHashMap.newKeySet();
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
        while (running) {
            try {
                Socket socket = listener.accept();
                if (open.size() >= policy.maxConnections()) {
                    reject(socket);
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

    private void reject(Socket socket) {
        LOG.log(System.Logger.Level.WARNING, "connection from {0} refused: max_connections={1} reached",
                socket.getRemoteSocketAddress(), policy.maxConnections());
        try (socket) {
            WireProtocol.send(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8),
                    "error [ABORTED_RESOURCE_LIMIT]: too many connections");
        } catch (IOException _) {
        }
    }

    private void handle(Socket socket) {
        long connection = served.incrementAndGet();
        long started = System.nanoTime();
        String peer = String.valueOf(socket.getRemoteSocketAddress());
        boolean active = false;
        try (socket; Session session = new Session(database)) {
            if (socket instanceof SSLSocket secure) {
                secure.setSoTimeout(HANDSHAKE_TIMEOUT_MILLIS);
                secure.startHandshake();
            }
            socket.setSoTimeout(Math.max(0, policy.idleTimeoutSeconds()) * 1000);
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            boolean authenticated = !policy.authenticationRequired(database);
            int failures = 0;
            WireProtocol.send(out, "hstore connection " + connection + " at generation " + database.engine().transactions().current().id()
                    + (authenticated ? "" : "; authentication required"));
            for (Optional<String> request = WireProtocol.receive(in); request.isPresent() && running; request = WireProtocol.receive(in)) {
                if (!active && policy.logConnections()) {
                    LOG.log(System.Logger.Level.INFO, "connection {0} received from {1}", connection, peer);
                }
                active = true;
                if (!authenticated) {
                    authenticated = authenticate(session, request.get(), out, connection, peer);
                    if (!authenticated && ++failures >= MAX_AUTHENTICATION_ATTEMPTS) {
                        LOG.log(System.Logger.Level.WARNING, "connection {0} from {1} closed after {2} failed authentication attempts",
                                connection, peer, failures);
                        return;
                    }
                    continue;
                }
                WireProtocol.send(out, execute(session, request.get(), connection));
            }
        } catch (SSLException handshake) {
            LOG.log(System.Logger.Level.INFO, "connection {0} from {1} failed the TLS handshake: {2}", connection, peer, handshake.getMessage());
        } catch (SocketTimeoutException idle) {
            if (!active && socket instanceof SSLSocket) {
                LOG.log(System.Logger.Level.INFO, "connection {0} from {1} did not complete the TLS handshake in time", connection, peer);
                return;
            }
            LOG.log(System.Logger.Level.INFO, "connection {0} closed after {1}s idle", connection, policy.idleTimeoutSeconds());
        } catch (IOException e) {
            LOG.log(System.Logger.Level.DEBUG, "connection " + connection + " ended", e);
        } finally {
            open.remove(socket);
            if (policy.logConnections()) {
                LOG.log(active ? System.Logger.Level.INFO : System.Logger.Level.DEBUG, "disconnection: connection {0} from {1} session time {2} ms",
                        connection, peer, (System.nanoTime() - started) / 1_000_000);
            }
        }
    }

    private boolean authenticate(Session session, String request, Writer out, long connection, String peer) throws IOException {
        List<Statement> statements;
        try {
            statements = Parser.script(request);
        } catch (HStoreException failure) {
            WireProtocol.send(out, "error [" + failure.code() + "]: " + failure.getMessage());
            return false;
        }
        if (statements.size() == 1 && statements.getFirst() instanceof Statement.Authenticate(String user, String password)) {
            if (session.authenticate(user, password)) {
                if (policy.logConnections()) {
                    LOG.log(System.Logger.Level.INFO, "connection {0} authorized: user={1} tenant={2} role={3}",
                            connection, user, session.principal().tenant(), session.principal().role());
                }
                WireProtocol.send(out, "authenticated as " + user);
                return true;
            }
            LOG.log(System.Logger.Level.WARNING, "password authentication failed for user \"{0}\" from {1}", user, peer);
            WireProtocol.send(out, "error [AUTHENTICATION_FAILED]: authentication failed");
            return false;
        }
        WireProtocol.send(out, "error [AUTHENTICATION_REQUIRED]: authenticate first: AUTHENTICATE <user> PASSWORD '<password>'");
        return false;
    }

    private String execute(Session session, String script, long connection) {
        List<String> rendered = new ArrayList<>();
        List<Parser.Sourced> statements;
        try {
            statements = Parser.sourced(script);
        } catch (HStoreException failure) {
            LOG.log(System.Logger.Level.INFO, "connection {0}: {1}", connection, failure.getMessage());
            return "error [" + failure.code() + "]: " + failure.getMessage();
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
                rendered.add("error: " + failure);
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

// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server;

import io.hstore.db.query.Ast.Statement;
import io.hstore.db.query.Parser;
import io.hstore.db.security.Scram;
import io.hstore.engine.HStoreException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSocket;

final class RemoteEndpoint implements Endpoint {

    record Credentials(String user, String password) {
        @Override
        public String toString() {
            return "Credentials[user=" + user + "]";
        }
    }

    private static final int TIMEOUT_MILLIS = 10_000;

    private final Socket socket;
    private final InputStream in;
    private final Writer out;
    private String banner;

    RemoteEndpoint(InetSocketAddress address, Optional<Credentials> credentials) {
        this(address, credentials, Optional.empty());
    }

    RemoteEndpoint(InetSocketAddress address, Optional<Credentials> credentials, Optional<Tls.Client> tls) {
        try {
            this.socket = tls.isPresent()
                    ? tls.get().connect(address.getHostString(), address.getPort(), TIMEOUT_MILLIS)
                    : plain(address);
            socket.setSoTimeout(TIMEOUT_MILLIS);
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            this.banner = WireProtocol.receive(in, Long.MAX_VALUE).orElseThrow(() -> new IOException("server closed the connection"));
            socket.setSoTimeout(0);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot connect to " + address, e);
        }
        if (banner.startsWith("error")) {
            close();
            throw new IllegalStateException(banner);
        }
        if (banner.endsWith("authentication required") && credentials.isPresent()) {
            String reply;
            try {
                reply = signIn(credentials.get());
            } catch (RuntimeException failure) {
                close();
                throw failure;
            }
            if (reply.startsWith("error")) {
                close();
                throw new IllegalArgumentException(reply);
            }
            banner = banner.replace("authentication required", reply);
        }
    }

    String signIn(Credentials credentials) {
        Optional<byte[]> binding = socket instanceof SSLSocket secure ? peerEndPoint(secure) : Optional.empty();
        Scram.Client client = new Scram.Client(credentials.user(), credentials.password(), binding);
        String first = send(Server.SASL + client.mechanism() + " " + encode(client.first()));
        if (!first.startsWith("sasl ")) {
            return first;
        }
        String last = send(Server.SASL + encode(client.last(decode(first.substring("sasl ".length())))));
        if (last.startsWith("error")) {
            return last;
        }
        String[] lines = last.split("\n", 2);
        if (lines.length != 2 || !lines[0].startsWith("authenticated as ") || !lines[1].startsWith("sasl ")) {
            return "error: unexpected reply to the sign-in: " + last;
        }
        try {
            client.verify(decode(lines[1].substring("sasl ".length())));
        } catch (HStoreException failure) {
            return "error: " + failure.getMessage();
        }
        return lines[0];
    }

    private static Optional<byte[]> peerEndPoint(SSLSocket socket) {
        try {
            return Scram.endPointOf(socket.getSession().getPeerCertificates());
        } catch (SSLPeerUnverifiedException e) {
            return Optional.empty();
        }
    }

    private static String encode(String message) {
        return Base64.getEncoder().encodeToString(message.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String message) {
        try {
            return new String(Base64.getDecoder().decode(message.strip()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("the server sent a malformed SASL message");
        }
    }

    private static Socket plain(InetSocketAddress address) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(address.getHostString(), address.getPort()), TIMEOUT_MILLIS);
        return socket;
    }

    @Override
    public String execute(String script) {
        Optional<Credentials> signIn = authentication(script);
        if (signIn.isPresent()) {
            String reply = signIn(signIn.get());
            if (!reply.startsWith("error")) {
                banner = banner.replaceFirst("authentication required$", reply);
            }
            return reply;
        }
        return send(script);
    }

    private static Optional<Credentials> authentication(String script) {
        try {
            List<Statement> statements = Parser.script(script);
            return statements.size() == 1 && statements.getFirst() instanceof Statement.Authenticate(String user, String password)
                    ? Optional.of(new Credentials(user, password))
                    : Optional.empty();
        } catch (HStoreException unparsable) {
            return Optional.empty();
        }
    }

    private String send(String script) {
        try {
            WireProtocol.send(out, script);
            return WireProtocol.receive(in, Long.MAX_VALUE).orElse("error: server closed the connection");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String describe() {
        return banner;
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

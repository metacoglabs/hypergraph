package io.hstore.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

final class RemoteEndpoint implements Endpoint {

    record Credentials(String user, String password) {
        String statement() {
            return "AUTHENTICATE '" + user.replace("'", "''") + "' PASSWORD '" + password.replace("'", "''") + "'";
        }
    }

    private final Socket socket;
    private final BufferedReader in;
    private final Writer out;
    private String banner;

    RemoteEndpoint(InetSocketAddress address, Optional<Credentials> credentials) {
        try {
            this.socket = new Socket(address.getHostString(), address.getPort());
            this.in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            this.out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
            this.banner = WireProtocol.receive(in).orElseThrow(() -> new IOException("server closed the connection"));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot connect to " + address, e);
        }
        if (banner.startsWith("error")) {
            close();
            throw new IllegalStateException(banner);
        }
        if (banner.endsWith("authentication required") && credentials.isPresent()) {
            String reply = execute(credentials.get().statement());
            if (reply.startsWith("error")) {
                close();
                throw new IllegalArgumentException(reply);
            }
            banner = banner.replace("authentication required", reply);
        }
    }

    @Override
    public String execute(String script) {
        try {
            WireProtocol.send(out, script);
            return WireProtocol.receive(in).orElse("error: server closed the connection");
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

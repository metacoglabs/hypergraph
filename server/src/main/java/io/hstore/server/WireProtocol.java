package io.hstore.server;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.util.Optional;

final class WireProtocol {

    static final String TERMINATOR = ".";

    private WireProtocol() {
    }

    static void send(Writer out, String message) throws IOException {
        for (String line : message.split("\n", -1)) {
            out.write(line.startsWith(TERMINATOR) ? TERMINATOR + line : line);
            out.write('\n');
        }
        out.write(TERMINATOR);
        out.write('\n');
        out.flush();
    }

    static Optional<String> receive(BufferedReader in) throws IOException {
        StringBuilder message = new StringBuilder();
        String line;
        while ((line = in.readLine()) != null) {
            if (line.equals(TERMINATOR)) {
                return Optional.of(message.isEmpty() ? "" : message.substring(0, message.length() - 1));
            }
            message.append(line.startsWith(TERMINATOR + TERMINATOR) ? line.substring(1) : line).append('\n');
        }
        return Optional.empty();
    }
}

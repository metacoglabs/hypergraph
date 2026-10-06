// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server;

final class Shell {

    private static final String HELP = """
            Statements end with ';'. Meta commands:
              \\q        quit
              \\timing   toggle timing
              \\help     this text
            Examples:
              CREATE NODE TYPE Person (name STRING INDEXED REQUIRED, age INT INDEXED);
              CREATE SET EDGE TYPE Claim (amount FLOAT) ROLES (buyer, seller);
              INSERT NODE Person 'alice' {name: 'Alice', age: 34} AS $a;
              INSERT EDGE Claim {amount: 12.5} MEMBERS ($a AS buyer) AS $c;
              MATCH EDGE c:Claim WHERE c CONTAINS ($a) RETURN c, card(c);
              EXPLAIN MATCH NODE p:Person WHERE p.age > 30;
              MEMBERS OF $c;  NEIGHBORS OF $a;  OVERLAP JOIN Claim THRESHOLD 2;
              BEGIN; ...; COMMIT;   CREATE BRANCH what_if;   USE BRANCH what_if;
              HISTORY LIMIT 10;   STATS;   CHECKPOINT;   COMPACT;
            """;

    private final Endpoint endpoint;
    private boolean timing;

    Shell(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    void run() {
        IO.println("hstore shell — " + endpoint.describe() + "  (\\help for help)");
        StringBuilder buffer = new StringBuilder();
        while (true) {
            String line = IO.readln(buffer.isEmpty() ? "hstore> " : "   ...> ");
            if (line == null) {
                break;
            }
            String trimmed = line.strip();
            if (buffer.isEmpty() && trimmed.startsWith("\\")) {
                if (!meta(trimmed)) {
                    break;
                }
                continue;
            }
            buffer.append(line).append('\n');
            if (trimmed.endsWith(";")) {
                long started = System.nanoTime();
                IO.println(endpoint.execute(buffer.toString()));
                if (timing) {
                    IO.println("time: %.3f ms".formatted((System.nanoTime() - started) / 1e6));
                }
                buffer.setLength(0);
            }
        }
        IO.println("bye");
    }

    private boolean meta(String command) {
        switch (command) {
            case "\\q", "\\quit" -> {
                return false;
            }
            case "\\timing" -> {
                timing = !timing;
                IO.println("timing " + (timing ? "on" : "off"));
            }
            case "\\help", "\\?" -> IO.println(HELP);
            default -> IO.println("unknown command " + command);
        }
        return true;
    }
}

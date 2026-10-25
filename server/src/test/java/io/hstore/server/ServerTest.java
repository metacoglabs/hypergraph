// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.query.Parser;
import io.hstore.db.query.Session;
import io.hstore.db.security.Principal;
import io.hstore.db.security.Role;
import io.hstore.engine.Dictionary;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.StorageEngine;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.Validity;
import io.hstore.engine.topology.Weight;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import javax.net.ssl.SSLContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerTest {

    @TempDir
    Path directory;

    @Test
    void framingEscapesTerminatorLines() throws Exception {
        StringWriter buffer = new StringWriter();
        WireProtocol.send(buffer, "first\n.\n..dots\nlast");
        assertEquals("first\n.\n..dots\nlast", WireProtocol.receive(stream(buffer.toString()), 1 << 10).orElseThrow());
    }

    @Test
    void onlyLineFeedsEndLines() throws Exception {
        assertEquals("x\r.\rDROP USER root\nnext\r", WireProtocol.receive(stream("x\r.\rDROP USER root\nnext\r\n.\r\n"), 1 << 10).orElseThrow());
        StringWriter buffer = new StringWriter();
        WireProtocol.respond(buffer, "value\r.\rfake");
        assertEquals("value\n..\nfake\n.\n", buffer.toString());
    }

    @Test
    void messagesAreCappedAndHttpIsRecognised() {
        assertThrows(WireProtocol.TooLarge.class, () -> WireProtocol.receive(stream("a".repeat(2_000)), 1 << 10));
        assertTrue(WireProtocol.http("POST / HTTP/1.1\r\nHost: 127.0.0.1:7432\r\n\r\n"));
        assertTrue(!WireProtocol.http("MATCH NODE n RETURN n"));
    }

    private static InputStream stream(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void concurrentClientsShareOneDatabase() throws Exception {
        DatabaseOptions options = DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, options)) {
            Thread acceptor;
            try (Server server = new Server(database, new InetSocketAddress("127.0.0.1", 0))) {
                acceptor = Thread.ofVirtual().start(server::serve);
                InetSocketAddress address = new InetSocketAddress("127.0.0.1", server.port());
                try (Endpoint admin = new RemoteEndpoint(address, Optional.empty())) {
                    assertTrue(admin.describe().startsWith("hstore connection"));
                    admin.execute("CREATE NODE TYPE Item (n INT INDEXED); CREATE SET EDGE TYPE Bag;");
                    admin.execute("INSERT EDGE Bag AS $bag;");
                }
                try (ExecutorService clients = Executors.newVirtualThreadPerTaskExecutor()) {
                    List<Future<Integer>> results = clients.invokeAll(IntStream.range(0, 8).<Callable<Integer>>mapToObj(client -> () -> {
                        try (Endpoint endpoint = new RemoteEndpoint(address, Optional.empty())) {
                            int rejected = 0;
                            for (int i = 0; i < 10; i++) {
                                String reply = endpoint.execute("INSERT NODE Item 'i" + client + "-" + i + "' {n: " + i + "} AS $x; ADD $x TO @Bag:'missing';");
                                rejected += reply.contains("error") ? 1 : 0;
                            }
                            return rejected;
                        }
                    }).toList());
                    for (Future<Integer> result : results) {
                        assertEquals(10, result.get());
                    }
                }
                try (Endpoint check = new RemoteEndpoint(address, Optional.empty())) {
                    String counted = check.execute("MATCH NODE i:Item RETURN count(*);");
                    assertTrue(counted.contains("| 80 "), counted);
                    String indexed = check.execute("EXPLAIN MATCH NODE i:Item WHERE i.n = 3;");
                    assertTrue(indexed.startsWith("IndexRange(Item.n"), indexed);
                }
            }
            acceptor.join();
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void sigtermCheckpointsAndLogsTheCleanShutdown() throws Exception {
        String modules = System.getProperty("jdk.module.path");
        List<String> command = new ArrayList<>(List.of(ProcessHandle.current().info().command().orElseThrow()));
        command.addAll(modules == null ? List.of("-cp", System.getProperty("java.class.path"), Main.class.getName())
                : List.of("-p", modules, "-m", "io.hstore.server/" + Main.class.getName()));
        command.addAll(List.of("serve", directory.resolve("data").toString(), "--port", "0", "--studio", "off"));
        Path log = directory.resolve("serve.log");
        Process server = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!Files.readString(log).contains("listening on") && System.nanoTime() < deadline && server.isAlive()) {
                Thread.sleep(50);
            }
            assertTrue(Files.readString(log).contains("listening on"), Files.readString(log));
            server.destroy();
            assertTrue(server.waitFor(60, TimeUnit.SECONDS));
            String output = Files.readString(log);
            assertTrue(output.contains("received shutdown request"), output);
            assertTrue(output.contains("checkpoint complete"), output);
            assertTrue(output.contains("shut down cleanly"), output);
        } finally {
            server.destroyForcibly();
        }
    }

    static Path fixture(String name) throws Exception {
        return Path.of(ServerTest.class.getResource("/tls/" + name).toURI());
    }

    @Test
    void wireProtocolRunsOverTls() throws Exception {
        DatabaseOptions options = DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
        SSLContext server = Tls.server(fixture("certificate.pem"), fixture("key.pem"));
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, options)) {
            Thread acceptor;
            try (Server tls = new Server(database, new InetSocketAddress("127.0.0.1", 0), Server.Policy.permissive().withTls(server))) {
                acceptor = Thread.ofVirtual().start(tls::serve);
                InetSocketAddress address = new InetSocketAddress("127.0.0.1", tls.port());
                Tls.Client trusting = new Tls.Client(Tls.trusting(fixture("certificate.pem")), true);
                try (Endpoint endpoint = new RemoteEndpoint(address, Optional.empty(), Optional.of(trusting))) {
                    assertTrue(endpoint.describe().startsWith("hstore connection"));
                    assertTrue(endpoint.execute("CREATE NODE TYPE Person (name STRING); SHOW TYPES;").contains("Person"));
                    endpoint.execute("CREATE USER admin PASSWORD 'pw-secret' ROLE admin;");
                }
                try (Endpoint bound = new RemoteEndpoint(address, Optional.of(new RemoteEndpoint.Credentials("admin", "pw-secret")), Optional.of(trusting))) {
                    assertTrue(bound.describe().endsWith("authenticated as admin"), bound.describe());
                    assertTrue(bound.execute("WHOAMI;").contains("ADMIN"));
                }
                assertThrows(IllegalArgumentException.class, () -> new RemoteEndpoint(address,
                        Optional.of(new RemoteEndpoint.Credentials("admin", "wrong-password")), Optional.of(trusting)));
                assertThrows(UncheckedIOException.class,
                        () -> new RemoteEndpoint(address, Optional.empty(), Optional.of(new Tls.Client(Tls.system(), true))));
                assertTimeoutPreemptively(Duration.ofSeconds(20),
                        () -> assertThrows(UncheckedIOException.class, () -> new RemoteEndpoint(address, Optional.empty())));
            }
            acceptor.join();
        }
    }

    @Test
    void nodeCacheIsSizedInMegabytesAndOldNamesAreRejected(@TempDir Path data) throws Exception {
        assertEquals(512L << 20, ServerConfig.load(Optional.empty(), Map.of("HSTORE_NODE_CACHE_MB", "512"), Map.of()).engineOptions().nodeCacheBytes());
        assertEquals(256L << 20, ServerConfig.load(Optional.empty(), Map.of(), Map.of()).engineOptions().nodeCacheBytes());
        List<IllegalArgumentException> failures = new ArrayList<>();
        for (String old : List.of("cache_nodes", "cache_mb")) {
            Files.writeString(data.resolve(ServerConfig.FILE), old + " = 4096\n");
            failures.add(assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(Optional.of(data), Map.of(), Map.of())));
            failures.add(assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.load(Optional.empty(), Map.of("HSTORE_" + old.toUpperCase(), "4096"), Map.of())));
            failures.add(assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.load(Optional.empty(), Map.of(), Map.of(old.replace('_', '-'), "4096"))));
        }
        for (String outOfRange : List.of("0", "-1", "99999999999999")) {
            failures.add(assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.load(Optional.empty(), Map.of("HSTORE_NODE_CACHE_MB", outOfRange), Map.of()).engineOptions()));
        }
        failures.forEach(failure -> assertTrue(failure.getMessage().contains("node_cache_mb"), failure.getMessage()));
    }

    @Test
    void openSegmentFilesComeFromTheFileTheEnvironmentOrAFlag(@TempDir Path data) throws Exception {
        assertEquals(256, ServerConfig.load(Optional.empty(), Map.of(), Map.of()).engineOptions().maxOpenSegmentFiles());
        Files.writeString(data.resolve(ServerConfig.FILE), "max_open_segment_files = 64\n");
        assertEquals(64, ServerConfig.load(Optional.of(data), Map.of(), Map.of()).engineOptions().maxOpenSegmentFiles());
        assertEquals(32, ServerConfig.load(Optional.of(data), Map.of("HSTORE_MAX_OPEN_SEGMENT_FILES", "32"), Map.of())
                .engineOptions().maxOpenSegmentFiles());
        assertEquals(16, ServerConfig.load(Optional.of(data), Map.of("HSTORE_MAX_OPEN_SEGMENT_FILES", "32"),
                Map.of("max-open-segment-files", "16")).engineOptions().maxOpenSegmentFiles());
        for (String outOfRange : List.of("0", "-3", "4294967296")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.load(Optional.empty(), Map.of("HSTORE_MAX_OPEN_SEGMENT_FILES", outOfRange), Map.of()).engineOptions());
            assertTrue(failure.getMessage().contains("max_open_segment_files"), failure.getMessage());
        }
    }

    @Test
    void checkpointWalMbIsAutomaticUnlessGivenInMegabytes() {
        assertEquals(EngineOptions.AUTOMATIC_CHECKPOINTS, ServerConfig.load(Optional.empty(), Map.of(), Map.of()).engineOptions().checkpointWalBytes());
        assertEquals(EngineOptions.AUTOMATIC_CHECKPOINTS,
                ServerConfig.load(Optional.empty(), Map.of("HSTORE_CHECKPOINT_WAL_MB", "AUTO"), Map.of()).engineOptions().checkpointWalBytes());
        assertEquals(64L << 20, ServerConfig.load(Optional.empty(), Map.of("HSTORE_CHECKPOINT_WAL_MB", "64"), Map.of()).engineOptions().checkpointWalBytes());
        assertEquals(512L << 20, ServerConfig.load(Optional.empty(), Map.of(), Map.of("checkpoint-wal-mb", "512")).engineOptions().checkpointWalBytes());
        for (String invalid : List.of("0", "-1", "99999999999999", "sometimes")) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> ServerConfig.load(Optional.empty(), Map.of("HSTORE_CHECKPOINT_WAL_MB", invalid), Map.of()).engineOptions());
            assertTrue(failure.getMessage().contains("checkpoint_wal_mb"), failure.getMessage());
        }
    }

    @Test
    void tlsWithoutACertificateIsRejectedAtStartup() {
        ServerConfig config = ServerConfig.load(Optional.empty(), Map.of(), Map.of("tls", "on"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, config::serverTls);
        assertTrue(failure.getMessage().contains("tls_certificate_file"));
    }

    @Test
    void serversRequireCredentialsOnceUsersExist() throws Exception {
        DatabaseOptions options = DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, options)) {
            new Session(database).execute("CREATE USER admin PASSWORD 'pw-secret' ROLE admin");
            Thread acceptor;
            try (Server server = new Server(database, new InetSocketAddress("127.0.0.1", 0))) {
                acceptor = Thread.ofVirtual().start(server::serve);
                InetSocketAddress address = new InetSocketAddress("127.0.0.1", server.port());
                try (Endpoint anonymous = new RemoteEndpoint(address, Optional.empty())) {
                    assertTrue(anonymous.describe().endsWith("authentication required"));
                    assertTrue(anonymous.execute("SHOW TYPES;").startsWith("error"));
                }
                assertThrows(IllegalArgumentException.class,
                        () -> new RemoteEndpoint(address, Optional.of(new RemoteEndpoint.Credentials("admin", "wrong"))));
                try (Endpoint admin = new RemoteEndpoint(address, Optional.of(new RemoteEndpoint.Credentials("admin", "pw-secret")))) {
                    assertTrue(admin.execute("WHOAMI;").contains("ADMIN"));
                    assertTrue(admin.execute("FORMAT JSON; SHOW USERS;").contains("\"rows\":[[\"admin\""));
                }
            }
            acceptor.join();
        }
    }

    @Test
    void connectionsOpenedBeforeTheFirstUserMustAuthenticate() throws Exception {
        DatabaseOptions options = DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, options)) {
            Thread acceptor;
            try (Server server = new Server(database, new InetSocketAddress("127.0.0.1", 0))) {
                acceptor = Thread.ofVirtual().start(server::serve);
                InetSocketAddress address = new InetSocketAddress("127.0.0.1", server.port());
                try (Endpoint early = new RemoteEndpoint(address, Optional.empty())) {
                    assertTrue(early.execute("CREATE USER admin PASSWORD 'pw-secret' ROLE admin;").contains("created user admin"));
                    assertTrue(early.execute("SHOW USERS;").startsWith("error [AUTHENTICATION_REQUIRED]"));
                    assertTrue(early.execute("AUTHENTICATE admin PASSWORD 'pw-secret';").contains("authenticated as admin"));
                    assertTrue(early.execute("FORMAT JSON; SHOW USERS;").contains("\"rows\":[[\"admin\""));
                }
            }
            acceptor.join();
        }
    }

    @Test
    void hostileConnectionsAreClosedEarly() throws Exception {
        DatabaseOptions options = DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, options)) {
            Thread acceptor;
            try (Server server = new Server(database, new InetSocketAddress("127.0.0.1", 0), Server.Policy.permissive().withTimeouts(0, 1))) {
                acceptor = Thread.ofVirtual().start(server::serve);
                InetSocketAddress address = new InetSocketAddress("127.0.0.1", server.port());
                assertFalse(exchange(address, "POST / HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Type: text/plain\r\n\r\n"
                        + ".\nCREATE USER intruder PASSWORD 'x-password' ROLE admin\n.\n").stream().anyMatch(message -> message.startsWith("created")));
                assertFalse(database.requiresAuthentication());
                try (Endpoint admin = new RemoteEndpoint(address, Optional.empty())) {
                    admin.execute("CREATE USER admin PASSWORD 'pw-secret' ROLE admin; CREATE TENANT acme; CREATE USER alice PASSWORD 'alice-pass' TENANT acme ROLE writer;");
                }
                List<String> oversized = exchange(address, "x".repeat(100_000) + "\n.\n");
                assertTrue(oversized.getLast().startsWith("error [ABORTED_RESOURCE_LIMIT]") || oversized.getLast().equals("closed"), oversized.toString());
                assertTrue(oversized.stream().noneMatch(message -> message.startsWith("error [AUTHENTICATION")), oversized.toString());
                List<Socket> waiting = new ArrayList<>();
                try {
                    for (int i = 0; i < 16; i++) {
                        Socket socket = new Socket(address.getHostString(), address.getPort());
                        waiting.add(socket);
                        socket.setSoTimeout(5_000);
                        WireProtocol.receive(new java.io.BufferedInputStream(socket.getInputStream()), 1 << 10);
                    }
                    assertTrue(exchange(address, "").getFirst().contains("too many unauthenticated connections"));
                } finally {
                    for (Socket socket : waiting) {
                        socket.close();
                    }
                }
                Thread.sleep(200);
                try (Socket idle = new Socket(address.getHostString(), address.getPort())) {
                    idle.setSoTimeout(10_000);
                    long started = System.nanoTime();
                    InputStream in = idle.getInputStream();
                    while (in.read() != -1) {
                    }
                    assertTrue(Duration.ofNanos(System.nanoTime() - started).toSeconds() < 8);
                }
                try (Endpoint admin = new RemoteEndpoint(address, Optional.of(new RemoteEndpoint.Credentials("admin", "pw-secret")));
                     Endpoint alice = new RemoteEndpoint(address, Optional.of(new RemoteEndpoint.Credentials("alice", "alice-pass")))) {
                    assertFalse(alice.execute("WHOAMI;").startsWith("error"));
                    admin.execute("CREATE NODE TYPE Doc (text STRING); INSERT NODE Doc 'x' {text: 'a\r.\rCREATE USER injected PASSWORD ''p'' ROLE admin'};");
                    assertFalse(admin.execute("SHOW USERS;").contains("injected"));
                    assertTrue(admin.execute("MATCH NODE d:Doc RETURN d.text;").contains("CREATE USER injected"));
                    admin.execute("ALTER USER alice PASSWORD 'rotated-pass';");
                    assertTrue(alice.execute("WHOAMI;").contains("authenticate again"));
                    assertTrue(alice.execute("AUTHENTICATE alice PASSWORD 'rotated-pass';").contains("authenticated as alice"));
                    assertTrue(alice.execute("WHOAMI;").contains("acme"));
                    String crashed = admin.execute("MATCH NODE p WHERE " + "(".repeat(50_000) + "p = @1" + ")".repeat(50_000) + " RETURN p;");
                    assertTrue(crashed.startsWith("error [INVALID_SCHEMA]") && !crashed.contains("Exception"), crashed);
                    assertFalse(admin.execute("WHOAMI;").startsWith("error"));
                }
            }
            acceptor.join();
        }
    }

    private static Optional<InetAddress> otherInterface() throws SocketException {
        return NetworkInterface.networkInterfaces()
                .filter(network -> {
                    try {
                        return network.isUp() && !network.isLoopback();
                    } catch (SocketException e) {
                        return false;
                    }
                })
                .flatMap(NetworkInterface::inetAddresses)
                .filter(address -> address instanceof Inet4Address && !address.isLoopbackAddress() && !address.isLinkLocalAddress())
                .findFirst();
    }

    @Test
    void otherHostsNeedAUserUnlessAuthenticationIsOff() throws Exception {
        Optional<InetAddress> other = otherInterface();
        Assumptions.assumeTrue(other.isPresent(), "needs a network interface other than loopback");
        DatabaseOptions options = DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, options)) {
            Thread acceptor;
            try (Server server = new Server(database, new InetSocketAddress("0.0.0.0", 0))) {
                acceptor = Thread.ofVirtual().start(server::serve);
                InetSocketAddress remote = new InetSocketAddress(other.get(), server.port());
                InetSocketAddress local = new InetSocketAddress("127.0.0.1", server.port());
                List<String> refused = exchange(remote, "CREATE USER intruder PASSWORD 'intruder-pass' ROLE admin;\n.\n");
                assertTrue(refused.getFirst().startsWith("error [AUTHENTICATION_REQUIRED]: this database has no users"), refused.toString());
                assertTrue(refused.stream().noneMatch(message -> message.contains("created user")), refused.toString());
                assertFalse(database.requiresAuthentication());
                try (Endpoint early = new RemoteEndpoint(local, Optional.empty())) {
                    assertTrue(early.execute("WHOAMI;").contains("system"));
                    early.execute("CREATE USER admin PASSWORD 'pw-secret' ROLE admin;");
                }
                try (Endpoint anonymous = new RemoteEndpoint(remote, Optional.empty())) {
                    assertTrue(anonymous.describe().endsWith("authentication required"));
                }
                try (Endpoint admin = new RemoteEndpoint(remote, Optional.of(new RemoteEndpoint.Credentials("admin", "pw-secret")))) {
                    assertTrue(admin.execute("WHOAMI;").contains("ADMIN"));
                    admin.execute("DROP USER admin;");
                    assertTrue(admin.execute("WHOAMI;").startsWith("error [AUTHENTICATION_REQUIRED]: this database has no users"));
                }
            }
            acceptor.join();
            try (Server open = new Server(database, new InetSocketAddress("0.0.0.0", 0), Server.Policy.permissive().withAuthentication("off"))) {
                acceptor = Thread.ofVirtual().start(open::serve);
                try (Endpoint anyone = new RemoteEndpoint(new InetSocketAddress(other.get(), open.port()), Optional.empty())) {
                    assertTrue(anyone.execute("WHOAMI;").contains("system"));
                }
            }
            acceptor.join();
        }
    }

    @Test
    void failedSignInsAreAnsweredAfterTheConfiguredDelay() throws Exception {
        DatabaseOptions options = DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, options)) {
            new Session(database).execute("CREATE USER admin PASSWORD 'pw-secret' ROLE admin");
            Thread acceptor;
            try (Server server = new Server(database, new InetSocketAddress("127.0.0.1", 0),
                    Server.Policy.permissive().withFailureDelay(Duration.ofMillis(400)))) {
                acceptor = Thread.ofVirtual().start(server::serve);
                InetSocketAddress address = new InetSocketAddress("127.0.0.1", server.port());
                long started = System.nanoTime();
                assertThrows(IllegalArgumentException.class,
                        () -> new RemoteEndpoint(address, Optional.of(new RemoteEndpoint.Credentials("admin", "wrong-password"))));
                assertTrue(System.nanoTime() - started >= Duration.ofMillis(400).toNanos());
                try (Endpoint admin = new RemoteEndpoint(address, Optional.of(new RemoteEndpoint.Credentials("admin", "pw-secret")))) {
                    assertTrue(admin.execute("WHOAMI;").contains("ADMIN"));
                }
            }
            acceptor.join();
        }
    }

    @Test
    void clearTextPasswordsAreRefusedWithAnUpgradeMessage() throws Exception {
        DatabaseOptions options = DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, options)) {
            new Session(database).execute("CREATE USER admin PASSWORD 'pw-secret' ROLE admin");
            Thread acceptor;
            try (Server server = new Server(database, new InetSocketAddress("127.0.0.1", 0))) {
                acceptor = Thread.ofVirtual().start(server::serve);
                InetSocketAddress address = new InetSocketAddress("127.0.0.1", server.port());
                try (Socket socket = new Socket(address.getHostString(), address.getPort())) {
                    socket.setSoTimeout(10_000);
                    InputStream in = new java.io.BufferedInputStream(socket.getInputStream());
                    java.io.Writer out = new java.io.OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                    assertTrue(WireProtocol.receive(in, 1 << 20).orElseThrow().endsWith("authentication required"));
                    WireProtocol.send(out, "AUTHENTICATE admin PASSWORD 'pw-secret'");
                    String old = WireProtocol.receive(in, 1 << 20).orElseThrow();
                    assertTrue(old.startsWith("error [AUTHENTICATION_FAILED]: this server no longer accepts passwords in clear text; upgrade your "
                            + "HStore client to version " + Main.VERSION), old);
                    WireProtocol.send(out, "SHOW USERS;");
                    assertTrue(WireProtocol.receive(in, 1 << 20).orElseThrow().contains("SASL SCRAM-SHA-256"));
                    WireProtocol.send(out, "SASL SCRAM-SHA-1 bixuPXgscj1hYmM=");
                    assertTrue(WireProtocol.receive(in, 1 << 20).orElseThrow().contains("unsupported SASL mechanism"));
                    WireProtocol.send(out, "SASL @@@");
                    assertTrue(WireProtocol.receive(in, 1 << 20).orElseThrow().startsWith("error [AUTHENTICATION_FAILED]"));
                }
                try (Endpoint admin = new RemoteEndpoint(address, Optional.of(new RemoteEndpoint.Credentials("admin", "pw-secret")))) {
                    assertTrue(admin.execute("AUTHENTICATE admin PASSWORD 'pw-secret'; WHOAMI;").contains("upgrade your HStore client"));
                    assertTrue(admin.execute("AUTHENTICATE admin PASSWORD 'pw-secret';").startsWith("authenticated as admin"));
                    assertTrue(admin.execute("WHOAMI;").contains("ADMIN"));
                }
                try (Endpoint nobody = new RemoteEndpoint(address, Optional.empty())) {
                    assertTrue(nobody.execute("AUTHENTICATE nobody PASSWORD 'whatever-it-is';").startsWith("error [AUTHENTICATION_FAILED]: authentication failed"));
                }
            }
            acceptor.join();
        }
    }

    @Test
    void checkFindsAndRepairsMembershipsAcrossTenants() throws Exception {
        DatabaseOptions options = DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
        long[] ids;
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, options)) {
            assertTrue(database.tenantMembershipsChecked());
            Session admin = new Session(database);
            admin.executeAll("""
                    CREATE NODE TYPE Patient;
                    CREATE SET EDGE TYPE Encounter ROLES (patient);
                    CREATE TENANT acme;
                    CREATE TENANT globex;
                    """);
            Session acme = new Session(database, new Principal("a", 1, Role.ADMIN));
            Session globex = new Session(database, new Principal("g", 2, Role.ADMIN));
            acme.executeAll("INSERT NODE Patient 'p' AS $p; INSERT EDGE Encounter MEMBERS ($p AS patient) AS $e;");
            globex.executeAll("INSERT NODE Patient 'q' AS $q; INSERT EDGE Encounter MEMBERS ($q AS patient) AS $g;");
            long p = acme.variables().get("p");
            long g = globex.variables().get("g");
            database.write(writer -> {
                writer.transaction().upsert(g, p, _ -> Incidence.of(p));
                return null;
            });
            database.engine().createBranch("side", Branch.MAIN);
            ids = new long[]{p, g};
        }
        try (StorageEngine engine = StorageEngine.open(directory, options.engine().withExtensions(HypergraphDatabase.extensionSlots(), List.of()))) {
            String report = Check.run(engine);
            assertTrue(report.contains("2 memberships cross tenants"), report);
            assertTrue(report.contains("USE BRANCH side; REMOVE @" + ids[0] + " FROM @" + ids[1]), report);
        }
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, options)) {
            String repaired = Check.repair(database);
            assertTrue(repaired.contains("removed 2 memberships") && repaired.contains("REMOVE @" + ids[0] + " FROM @" + ids[1]), repaired);
            assertTrue(database.tenantMembershipsChecked());
            assertTrue(Check.repair(database).contains("nothing to repair"));
            assertEquals(1L, (long) database.read(reader -> reader.engineView().cardinality(ids[1])));
        }
        try (StorageEngine engine = StorageEngine.open(directory, options.engine().withExtensions(HypergraphDatabase.extensionSlots(), List.of()))) {
            assertTrue(Check.run(engine).contains("no hyperedge on any branch has a member from another tenant"));
        }
    }

    private static List<String> exchange(InetSocketAddress address, String payload) throws Exception {
        try (Socket socket = new Socket(address.getHostString(), address.getPort())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(payload.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().flush();
            InputStream in = new java.io.BufferedInputStream(socket.getInputStream());
            List<String> messages = new ArrayList<>();
            try {
                for (Optional<String> message = WireProtocol.receive(in, 1 << 20); message.isPresent(); message = WireProtocol.receive(in, 1 << 20)) {
                    messages.add(message.get().startsWith("hstore connection") ? "hstore connection" : message.get());
                }
            } catch (java.net.SocketException reset) {
                messages.add("closed");
            }
            return messages;
        }
    }

    @Test
    void statementLogsNeverContainPasswords() {
        for (String script : List.of("CREATE USER mallory PASSWORD \"double-quoted\" ROLE reader", "ALTER USER mallory PASSWORD'no-space'",
                "ALTER USER mallory PASSWORD -- comment\n 'after-comment'", "AUTHENTICATE mallory PASSWORD 'it''s-escaped'")) {
            Parser.Sourced sourced = Parser.sourced(script).getFirst();
            String logged = StatementLogger.describe(sourced.statement(), sourced.text());
            assertTrue(logged.contains("'***'") && !logged.matches("(?s).*(double|space|comment|escaped).*"), logged);
        }
    }

    @Test
    void checkListsMembershipsThatCrossTenants() {
        DatabaseOptions options = DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096));
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, options); Session admin = new Session(database)) {
            admin.executeAll("""
                    CREATE NODE TYPE Patient;
                    CREATE SET EDGE TYPE Encounter;
                    CREATE TENANT acme;
                    CREATE TENANT globex;
                    USE TENANT acme;
                    INSERT NODE Patient 'a' AS $a;
                    USE TENANT globex;
                    INSERT EDGE Encounter AS $e;
                    """);
            long patient = admin.variables().get("a");
            long edge = admin.variables().get("e");
            assertTrue(Check.run(database.engine()).contains("no hyperedge on any branch has a member from another tenant"));
            database.write(writer -> {
                writer.transaction().insert(edge, new Incidence(patient, Dictionary.NO_ROLES, Weight.ONE,
                        Validity.ALWAYS.from(), Validity.ALWAYS.to(), 0, 0));
                return null;
            });
            String report = Check.run(database.engine());
            assertTrue(report.contains("1 memberships cross tenants"), report);
            assertTrue(report.contains("REMOVE @" + patient + " FROM @" + edge), report);
        }
    }

    @Test
    void configurationLayersFileEnvironmentAndFlags(@TempDir Path data) throws Exception {
        Files.writeString(data.resolve(ServerConfig.FILE), "port = 9000\nlog_statement = ddl\n");
        ServerConfig config = ServerConfig.load(Optional.of(data), Map.of("HSTORE_PORT", "9100", "HSTORE_DURABILITY", "async"),
                Map.of("port", "9200"));
        assertEquals(9200, config.integer(Setting.PORT));
        assertEquals("async", config.string(Setting.DURABILITY));
        assertEquals("ddl", config.string(Setting.LOG_STATEMENT));
        assertThrows(IllegalArgumentException.class, () -> ServerConfig.load(Optional.empty(), Map.of(), Map.of("no_such", "1")).string(Setting.of("no_such")));
    }
}

// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server.studio;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.query.Session;
import io.hstore.db.security.SignIns;
import io.hstore.db.value.Json;
import io.hstore.engine.EngineOptions;
import io.hstore.server.Tls;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioTest {

    @TempDir
    Path directory;

    private HypergraphDatabase database;
    private Studio studio;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        try (Session session = new Session(database)) {
            session.executeAll("""
                    CREATE USER admin PASSWORD 'secret-pass' ROLE admin;
                    CREATE NODE TYPE Person (name STRING INDEXED);
                    CREATE SET EDGE TYPE Meeting ROLES (host, guest);
                    INSERT NODE Person 'ann' {name: 'Ann'} AS $ann;
                    INSERT NODE Person 'bo' {name: 'Bo'} AS $bo;
                    INSERT NODE Person 'cy' {name: 'Cy'} AS $cy;
                    INSERT EDGE Meeting MEMBERS ($ann AS host, $bo AS guest, $cy AS guest) AS $meeting;
                    INSERT EDGE Meeting MEMBERS ($meeting AS host, $cy AS guest);
                    """);
        }
        studio = new Studio(database, new Studio.Options(new InetSocketAddress("127.0.0.1", 0), database::requiresAuthentication, () -> false,
                (origin, user, statement, millis, script) -> { }, "test", Duration.ofMinutes(5), Optional.empty(), Optional.empty(), Duration.ZERO));
        studio.start();
    }

    @AfterEach
    void stop() {
        studio.close();
        database.close();
    }

    private HttpResponse<String> get(String path, Optional<String> cookie) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + studio.port() + path));
        cookie.ifPresent(value -> request.header("Cookie", value));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body, Optional<String> cookie, boolean csrf) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + studio.port() + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (csrf) {
            request.header("X-HStore-Studio", "1");
        }
        cookie.ifPresent(value -> request.header("Cookie", value));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private Optional<String> login() throws Exception {
        HttpResponse<String> response = post("/api/login", "{\"user\":\"admin\",\"password\":\"secret-pass\"}", Optional.empty(), true);
        assertEquals(200, response.statusCode());
        return response.headers().firstValue("Set-Cookie").map(header -> header.substring(0, header.indexOf(';')));
    }

    private static Map<String, Json> object(String body) {
        return ((Json.Obj) Json.parse(body)).fields();
    }

    @Test
    void servesTheConsoleAndRejectsPathTraversal() throws Exception {
        HttpResponse<String> index = get("/", Optional.empty());
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("HStore Studio"));
        assertTrue(index.headers().firstValue("Content-Security-Policy").orElseThrow().contains("default-src 'self'"));
        assertEquals(200, get("/js/graph/view.js", Optional.empty()).statusCode());
        assertEquals(404, get("/../pom.xml", Optional.empty()).statusCode());
        assertEquals(404, get("/js/%2e%2e/app.js", Optional.empty()).statusCode());
    }

    @Test
    void requiresCredentialsAndTheCsrfHeader() throws Exception {
        assertEquals(401, get("/api/schema", Optional.empty()).statusCode());
        assertEquals(401, post("/api/login", "{\"user\":\"admin\",\"password\":\"wrong\"}", Optional.empty(), true).statusCode());
        assertEquals(403, post("/api/login", "{\"user\":\"admin\",\"password\":\"secret-pass\"}", Optional.empty(), false).statusCode());
        Optional<String> cookie = login();
        assertTrue(cookie.orElseThrow().startsWith("hstore_studio="));
        assertEquals(200, get("/api/schema", cookie).statusCode());
        assertEquals(200, post("/api/logout", "{}", cookie, true).statusCode());
        assertEquals(401, get("/api/schema", cookie).statusCode());
    }

    @Test
    void repeatedFailedLoginsAreThrottled() throws Exception {
        try (Session session = new Session(database)) {
            session.execute("CREATE USER bob PASSWORD 'b0b-pass' ROLE reader");
        }
        for (int i = 0; i < SignIns.ACCOUNT_LIMIT; i++) {
            assertEquals(401, post("/api/login", "{\"user\":\"admin\",\"password\":\"wrong\"}", Optional.empty(), true).statusCode());
            if (i % 3 == 0) {
                assertEquals(200, post("/api/login", "{\"user\":\"bob\",\"password\":\"b0b-pass\"}", Optional.empty(), true).statusCode());
            }
        }
        HttpResponse<String> blocked = post("/api/login", "{\"user\":\"admin\",\"password\":\"secret-pass\"}", Optional.empty(), true);
        assertEquals(429, blocked.statusCode());
        assertTrue(blocked.body().contains("too many failed sign-in attempts"));
        assertEquals(200, post("/api/login", "{\"user\":\"bob\",\"password\":\"b0b-pass\"}", Optional.empty(), true).statusCode());
    }

    @Test
    void theDashboardOpensToMonitors() throws Exception {
        try (Session session = new Session(database)) {
            session.execute("CREATE USER watcher PASSWORD 'watcher-pass' ROLE reader");
        }
        HttpResponse<String> signedIn = post("/api/login", "{\"user\":\"watcher\",\"password\":\"watcher-pass\"}", Optional.empty(), true);
        Optional<String> cookie = signedIn.headers().firstValue("Set-Cookie").map(header -> header.substring(0, header.indexOf(';')));
        assertEquals(403, get("/api/stats", cookie).statusCode());
        try (Session session = new Session(database)) {
            session.execute("GRANT MONITOR TO watcher");
        }
        assertEquals(200, get("/api/stats", cookie).statusCode());
        List<Json> generations = ((Json.Array) object(get("/api/history", cookie).body()).get("generations")).items();
        assertTrue(generations.stream().allMatch(generation -> ((Json.Obj) generation).fields().keySet().equals(Set.of("id"))));
    }

    @Test
    void studioServesHttpsWithSecureCookies() throws Exception {
        Path certificate = Path.of(StudioTest.class.getResource("/tls/certificate.pem").toURI());
        Path key = Path.of(StudioTest.class.getResource("/tls/key.pem").toURI());
        Studio secure = new Studio(database, new Studio.Options(new InetSocketAddress("127.0.0.1", 0), database::requiresAuthentication, () -> false,
                (origin, user, statement, millis, script) -> { }, "test", Duration.ofMinutes(5), Optional.empty(),
                Optional.of(Tls.server(certificate, key)), Duration.ZERO));
        secure.start();
        try {
            HttpClient client = HttpClient.newBuilder().sslContext(Tls.trusting(certificate)).build();
            URI base = URI.create("https://localhost:" + secure.port());
            assertEquals(200, client.send(HttpRequest.newBuilder(base).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            HttpResponse<String> login = client.send(HttpRequest.newBuilder(base.resolve("/api/login"))
                    .header("Content-Type", "application/json").header("X-HStore-Studio", "1")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"user\":\"admin\",\"password\":\"secret-pass\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, login.statusCode());
            assertTrue(login.headers().firstValue("Set-Cookie").orElseThrow().contains("; Secure"));
        } finally {
            secure.close();
        }
    }

    @Test
    void queriesReturnFramesWithAtomsAndGraphsIncludeHigherOrderMembers() throws Exception {
        Optional<String> cookie = login();
        HttpResponse<String> response = post("/api/query",
                "{\"script\":\"MATCH NODE p:Person RETURN p; SHOW NOPE;\"}", cookie, true);
        List<Json> results = ((Json.Array) object(response.body()).get("results")).items();
        assertEquals(1, results.size());
        Map<String, Json> first = ((Json.Obj) results.getFirst()).fields();
        assertTrue(first.containsKey("error"));
        response = post("/api/query", "{\"script\":\"MATCH NODE p:Person RETURN p; SHOW TYPES;\"}", cookie, true);
        results = ((Json.Array) object(response.body()).get("results")).items();
        assertEquals(2, results.size());
        assertEquals(3, ((Json.Array) ((Json.Obj) results.getFirst()).fields().get("atoms")).items().size());
        long ann = database.read(reader -> reader.resolve("Person", "ann"));
        Map<String, Json> graph = object(get("/api/graph?ids=" + ann + "&expand=1", cookie).body());
        List<Json> atoms = ((Json.Array) graph.get("atoms")).items();
        long edges = atoms.stream().filter(atom -> ((Json.Obj) atom).fields().get("kind") instanceof Json.Str(String kind) && kind.equals("SET_EDGE")).count();
        assertEquals(4, atoms.size());
        assertEquals(1, edges);
        Map<String, Json> sample = object(get("/api/graph?type=Meeting", cookie).body());
        assertTrue(((Json.Array) sample.get("atoms")).items().size() >= 5);
        Map<String, Json> keyed = object(get("/api/graph?type=Person&key=bo", cookie).body());
        assertEquals(1, ((Json.Array) keyed.get("atoms")).items().size());
    }

    @Test
    void dashboardStatisticsAreAvailableToAdministrators() throws Exception {
        Optional<String> cookie = login();
        Map<String, Json> stats = object(get("/api/stats", cookie).body());
        assertTrue(stats.containsKey("engine"));
        assertTrue(stats.containsKey("segments"));
        Map<String, Json> configuration = ((Json.Obj) stats.get("configuration")).fields();
        assertEquals(new Json.Str("automatic"), configuration.get("checkpointSource"));
        try (Session session = new Session(database)) {
            session.execute("CHECKPOINT AT 64MB");
        }
        configuration = ((Json.Obj) object(get("/api/stats", cookie).body()).get("configuration")).fields();
        assertEquals(new Json.Str("database"), configuration.get("checkpointSource"));
        assertEquals(64L << 20, ((Json.Number) configuration.get("checkpointWalBytes")).value().longValueExact());
        Map<String, Json> history = object(get("/api/history", cookie).body());
        assertTrue(((Json.Array) history.get("generations")).items().size() > 1);
    }

    @Test
    void sessionsOpenedWithoutCredentialsEndOnceUsersExist() throws Exception {
        try (Session session = new Session(database)) {
            session.execute("DROP USER admin");
        }
        HttpResponse<String> opened = post("/api/login", "{}", Optional.empty(), true);
        assertEquals(200, opened.statusCode());
        Optional<String> cookie = opened.headers().firstValue("Set-Cookie").map(header -> header.substring(0, header.indexOf(';')));
        assertEquals(200, get("/api/schema", cookie).statusCode());
        try (Session session = new Session(database)) {
            session.execute("CREATE USER admin PASSWORD 'secret-pass' ROLE admin");
        }
        assertEquals(401, get("/api/schema", cookie).statusCode());
        assertEquals(401, post("/api/query", "{\"script\":\"SHOW USERS\"}", cookie, true).statusCode());
    }

    @Test
    void tenantAdministratorsStayOutOfEngineWidePages() throws Exception {
        try (Session session = new Session(database)) {
            session.executeAll("CREATE TENANT acme; CREATE USER carol PASSWORD 'c4rol-pass' TENANT acme ROLE admin;");
        }
        HttpResponse<String> signedIn = post("/api/login", "{\"user\":\"carol\",\"password\":\"c4rol-pass\"}", Optional.empty(), true);
        assertEquals(200, signedIn.statusCode());
        Optional<String> cookie = signedIn.headers().firstValue("Set-Cookie").map(header -> header.substring(0, header.indexOf(';')));
        assertEquals(403, get("/api/stats", cookie).statusCode());
        assertTrue(((Json.Array) object(get("/api/schema", cookie).body()).get("branches")).items().isEmpty());
        Map<String, Json> history = object(get("/api/history", cookie).body());
        List<Json> generations = ((Json.Array) history.get("generations")).items();
        assertTrue(!generations.isEmpty() && generations.stream().allMatch(generation -> ((Json.Obj) generation).fields().keySet().equals(Set.of("id"))));
        for (int i = 0; i < Session.MAX_FAILED_AUTHENTICATIONS; i++) {
            assertTrue(post("/api/query", "{\"script\":\"AUTHENTICATE admin PASSWORD 'guess'\"}", cookie, true).body().contains("\"error\""));
        }
        assertTrue(post("/api/query", "{\"script\":\"AUTHENTICATE admin PASSWORD 'secret-pass'\"}", cookie, true).body()
                .contains("too many failed authentication attempts in this session"));
        for (int i = Session.MAX_FAILED_AUTHENTICATIONS; i < SignIns.ACCOUNT_LIMIT; i++) {
            assertEquals(200, post("/api/login", "{\"user\":\"carol\",\"password\":\"c4rol-pass\"}", Optional.empty(), true).statusCode());
            assertEquals(401, post("/api/login", "{\"user\":\"admin\",\"password\":\"guess\"}", Optional.empty(), true).statusCode());
        }
        assertEquals(429, post("/api/login", "{\"user\":\"admin\",\"password\":\"secret-pass\"}", Optional.empty(), true).statusCode());
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
        for (boolean off : new boolean[]{false, true}) {
            Path empty = directory.resolve(off ? "open" : "closed");
            try (HypergraphDatabase bare = HypergraphDatabase.open(empty, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)))) {
                Studio exposed = new Studio(bare, new Studio.Options(new InetSocketAddress("0.0.0.0", 0), bare::requiresAuthentication, () -> off,
                        (origin, user, statement, millis, script) -> { }, "test", Duration.ofMinutes(5), Optional.empty(), Optional.empty(), Duration.ZERO));
                exposed.start();
                try {
                    String host = other.get().getHostAddress();
                    HttpResponse<String> remote = client.send(HttpRequest.newBuilder(URI.create("http://" + host + ":" + exposed.port() + "/api/info"))
                            .build(), HttpResponse.BodyHandlers.ofString());
                    HttpResponse<String> forwarded = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + exposed.port() + "/api/info"))
                            .header("X-Forwarded-For", "198.51.100.7").build(), HttpResponse.BodyHandlers.ofString());
                    HttpResponse<String> local = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + exposed.port() + "/api/info"))
                            .build(), HttpResponse.BodyHandlers.ofString());
                    assertEquals(off ? 200 : 403, remote.statusCode(), remote.body());
                    assertEquals(off ? 200 : 403, forwarded.statusCode(), forwarded.body());
                    assertEquals(200, local.statusCode());
                    if (!off) {
                        assertTrue(remote.body().contains("this database has no users"), remote.body());
                        try (Session session = new Session(bare)) {
                            session.execute("CREATE USER admin PASSWORD 'secret-pass' ROLE admin");
                        }
                        assertEquals(200, client.send(HttpRequest.newBuilder(URI.create("http://" + host + ":" + exposed.port() + "/api/info"))
                                .build(), HttpResponse.BodyHandlers.ofString()).statusCode());
                    }
                } finally {
                    exposed.close();
                }
            }
        }
    }

    @Test
    void unknownHostNamesAreRefusedWhileAuthenticationIsOff() throws Exception {
        assertTrue(rawGet("rebind.attacker.example").startsWith("HTTP/1.1 200"));
        try (Session session = new Session(database)) {
            session.execute("DROP USER admin");
        }
        assertTrue(rawGet("rebind.attacker.example").startsWith("HTTP/1.1 403"));
        assertTrue(rawGet("rebind.attacker.example:" + studio.port()).startsWith("HTTP/1.1 403"));
        assertTrue(rawGet("127.0.0.1:" + studio.port()).startsWith("HTTP/1.1 200"));
        assertTrue(rawGet("localhost").startsWith("HTTP/1.1 200"));
        assertTrue(rawGet("[::1]:" + studio.port()).startsWith("HTTP/1.1 200"));
        assertTrue(rawGet("10.1.2.3").startsWith("HTTP/1.1 200"));
    }

    private String rawGet(String host) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", studio.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(("GET /api/info HTTP/1.1\r\nHost: " + host + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

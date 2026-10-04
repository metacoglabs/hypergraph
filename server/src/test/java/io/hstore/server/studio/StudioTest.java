package io.hstore.server.studio;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.query.Session;
import io.hstore.db.value.Json;
import io.hstore.engine.EngineOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
                    CREATE USER admin PASSWORD 'secret' ROLE admin;
                    CREATE NODE TYPE Person (name STRING INDEXED);
                    CREATE SET EDGE TYPE Meeting ROLES (host, guest);
                    INSERT NODE Person 'ann' {name: 'Ann'} AS $ann;
                    INSERT NODE Person 'bo' {name: 'Bo'} AS $bo;
                    INSERT NODE Person 'cy' {name: 'Cy'} AS $cy;
                    INSERT EDGE Meeting MEMBERS ($ann AS host, $bo AS guest, $cy AS guest) AS $meeting;
                    INSERT EDGE Meeting MEMBERS ($meeting AS host, $cy AS guest);
                    """);
        }
        studio = new Studio(database, new Studio.Options(new InetSocketAddress("127.0.0.1", 0), database::requiresAuthentication,
                (origin, user, statement, millis, script) -> { }, "test", Duration.ofMinutes(5), Optional.empty()));
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
        HttpResponse<String> response = post("/api/login", "{\"user\":\"admin\",\"password\":\"secret\"}", Optional.empty(), true);
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
        assertEquals(403, post("/api/login", "{\"user\":\"admin\",\"password\":\"secret\"}", Optional.empty(), false).statusCode());
        Optional<String> cookie = login();
        assertTrue(cookie.orElseThrow().startsWith("hstore_studio="));
        assertEquals(200, get("/api/schema", cookie).statusCode());
        assertEquals(200, post("/api/logout", "{}", cookie, true).statusCode());
        assertEquals(401, get("/api/schema", cookie).statusCode());
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
        Map<String, Json> history = object(get("/api/history", cookie).body());
        assertTrue(((Json.Array) history.get("generations")).items().size() > 1);
    }
}

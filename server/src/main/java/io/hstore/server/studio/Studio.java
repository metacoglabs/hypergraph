package io.hstore.server.studio;

import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Reader;
import io.hstore.db.query.Ast.Statement;
import io.hstore.db.query.Parser;
import io.hstore.db.query.QueryResult;
import io.hstore.db.query.Session;
import io.hstore.db.schema.TypeDef;
import io.hstore.db.security.Principal;
import io.hstore.db.security.Role;
import io.hstore.db.security.Security;
import io.hstore.db.value.Json;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.EngineStats;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.Generation;
import io.hstore.engine.maintenance.Recovery;
import io.hstore.engine.txn.TransactionManager;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import javax.net.ssl.SSLContext;

public final class Studio implements AutoCloseable {

    @FunctionalInterface
    public interface StatementListener {
        void executed(String origin, String user, Statement statement, long millis, String script);
    }

    public record Options(InetSocketAddress address, BooleanSupplier authenticationRequired, StatementListener statements,
                          String version, Duration idleSession, Optional<Path> assets, Optional<SSLContext> tls) {
    }

    private record Request(String method, String path, Map<String, String> query, Headers headers, String body, String peer, String address) {

        Optional<String> cookie(String name) {
            return headers.getOrDefault("Cookie", List.of()).stream()
                    .flatMap(header -> Arrays.stream(header.split(";")))
                    .map(String::strip)
                    .filter(pair -> pair.startsWith(name + "="))
                    .map(pair -> pair.substring(name.length() + 1))
                    .findFirst();
        }

        Optional<String> parameter(String name) {
            return Optional.ofNullable(query.get(name)).filter(value -> !value.isBlank());
        }

        int integer(String name, int fallback, int max) {
            return parameter(name).map(value -> {
                try {
                    return Math.clamp(Integer.parseInt(value), 0, max);
                } catch (NumberFormatException invalid) {
                    throw HttpFailure.badRequest(name + " must be an integer");
                }
            }).orElse(fallback);
        }

        Json json() {
            if (body.isBlank()) {
                return new Json.Obj(Map.of());
            }
            return Json.parse(body);
        }
    }

    private record Response(int status, String contentType, byte[] body, List<String> cookies) {

        static Response json(Json body, String... cookies) {
            return new Response(200, "application/json; charset=utf-8", body.print().getBytes(StandardCharsets.UTF_8), List.of(cookies));
        }

        static Response error(int status, Json body) {
            return new Response(status, "application/json; charset=utf-8", body.print().getBytes(StandardCharsets.UTF_8), List.of());
        }
    }

    private static final System.Logger LOG = System.getLogger("hstore.studio");
    private static final String COOKIE = "hstore_studio";
    private static final String CSRF_HEADER = "X-HStore-Studio";
    private static final int MAX_BODY = 1 << 20;
    private static final int MAX_RESULT_ATOMS = 500;
    private static final int MAX_FAILED_LOGINS = 5;
    private static final Duration FAILED_LOGIN_WINDOW = Duration.ofMinutes(15);
    private static final String SECURITY_POLICY = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data: blob:; "
            + "connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";

    private final HypergraphDatabase database;
    private final Options options;
    private final HttpServer http;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final Sessions sessions;
    private final Assets assets;
    private final LoginThrottle throttle = new LoginThrottle(MAX_FAILED_LOGINS, FAILED_LOGIN_WINDOW, System::nanoTime);
    private final long startedAt = System.currentTimeMillis();

    public Studio(HypergraphDatabase database, Options options) {
        this.database = database;
        this.options = options;
        this.sessions = new Sessions(options.idleSession());
        this.assets = new Assets(options.assets());
        try {
            if (options.tls().isPresent()) {
                HttpsServer secure = HttpsServer.create(options.address(), 64);
                secure.setHttpsConfigurator(new HttpsConfigurator(options.tls().get()));
                this.http = secure;
            } else {
                this.http = HttpServer.create(options.address(), 64);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot listen on " + options.address(), e);
        }
        http.createContext("/api/", exchange -> exchange(exchange, this::api));
        http.createContext("/", exchange -> exchange(exchange, this::asset));
        http.setExecutor(executor);
    }

    public void start() {
        http.start();
        LOG.log(System.Logger.Level.INFO, "studio listening on {0}://{1}:{2}", options.tls().isPresent() ? "https" : "http",
                options.address().getHostString(), String.valueOf(port()));
    }

    public int port() {
        return http.getAddress().getPort();
    }

    private void exchange(HttpExchange exchange, Function<Request, Response> handler) throws IOException {
        Response response;
        try {
            response = handler.apply(request(exchange));
        } catch (HttpFailure failure) {
            response = Response.error(failure.status(), error(failure.status() == 401 ? "UNAUTHENTICATED" : "HTTP_" + failure.status(),
                    failure.getMessage(), false));
        } catch (HStoreException failure) {
            response = Response.error(400, error(failure.code().name(), failure.getMessage(), failure.retryable()));
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.ERROR, "studio request " + exchange.getRequestURI().getPath() + " failed", failure);
            response = Response.error(500, error("INTERNAL", String.valueOf(failure), false));
        }
        Headers headers = exchange.getResponseHeaders();
        headers.set("Content-Type", response.contentType());
        headers.set("Cache-Control", "no-store");
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", SECURITY_POLICY);
        response.cookies().forEach(cookie -> headers.add("Set-Cookie", cookie));
        try (exchange; OutputStream out = exchange.getResponseBody()) {
            exchange.sendResponseHeaders(response.status(), response.body().length == 0 ? -1 : response.body().length);
            out.write(response.body());
        }
    }

    private static Request request(HttpExchange exchange) throws IOException {
        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readNBytes(MAX_BODY + 1);
        }
        if (body.length > MAX_BODY) {
            throw HttpFailure.tooLarge("request body exceeds " + MAX_BODY + " bytes");
        }
        Map<String, String> query = new HashMap<>();
        Optional.ofNullable(exchange.getRequestURI().getRawQuery()).ifPresent(raw -> {
            for (String pair : raw.split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    query.put(decode(pair.substring(0, equals)), decode(pair.substring(equals + 1)));
                }
            }
        });
        return new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), query, exchange.getRequestHeaders(),
                new String(body, StandardCharsets.UTF_8), String.valueOf(exchange.getRemoteAddress()),
                exchange.getRemoteAddress().getAddress().getHostAddress());
    }

    private static String decode(String value) {
        return URLDecoder.decode(value, StandardCharsets.UTF_8);
    }

    private static Json error(String code, String message, boolean retryable) {
        return JsonFields.object().put("error", JsonFields.object()
                .put("code", code).put("message", message).put("retryable", retryable).build()).build();
    }

    private Response asset(Request request) {
        if (!request.method().equals("GET")) {
            throw HttpFailure.notFound(request.path());
        }
        return assets.find(request.path())
                .map(asset -> new Response(200, asset.contentType(), asset.body(), List.of()))
                .orElseThrow(() -> HttpFailure.notFound(request.path()));
    }

    private Response api(Request request) {
        if (request.method().equals("POST") && !request.headers().containsKey(CSRF_HEADER)) {
            throw HttpFailure.forbidden("missing " + CSRF_HEADER + " header");
        }
        return switch (request.method() + " " + request.path()) {
            case "GET /api/info" -> info(request);
            case "POST /api/login" -> login(request);
            case "POST /api/logout" -> logout(request);
            case "POST /api/query" -> query(request);
            case "GET /api/graph" -> graph(request);
            case "GET /api/schema" -> schema(request);
            case "GET /api/history" -> history(request);
            case "GET /api/stats" -> stats(request);
            default -> throw HttpFailure.notFound("no route " + request.method() + " " + request.path());
        };
    }

    private Sessions.Entry session(Request request) {
        return request.cookie(COOKIE).flatMap(sessions::find)
                .orElseThrow(() -> HttpFailure.unauthorized("sign in to use the studio"));
    }

    private Json describe(Session session) {
        Principal principal = session.principal();
        String tenant = database.read(reader -> Security.tenant(reader.view(), principal.tenant())
                .map(Security.Tenant::name).orElse("#" + principal.tenant()));
        String branch = database.engine().branches().stream().filter(candidate -> candidate.id() == session.branch())
                .map(Branch::name).findFirst().orElse("main");
        return JsonFields.object()
                .put("user", principal.user())
                .put("tenant", tenant)
                .put("role", principal.role().name())
                .put("branch", branch)
                .put("inTransaction", session.inTransaction())
                .build();
    }

    private Response info(Request request) {
        Optional<Sessions.Entry> entry = request.cookie(COOKIE).flatMap(sessions::find);
        return Response.json(JsonFields.object()
                .put("product", "HStore Studio")
                .put("version", options.version())
                .put("generation", database.engine().transactions().current().id())
                .put("authenticationRequired", options.authenticationRequired().getAsBoolean())
                .put("startedAt", startedAt)
                .put("session", entry.map(found -> describe(found.session())).orElse(Json.NULL))
                .build());
    }

    private Response login(Request request) {
        Json body = request.json();
        Optional<String> user = field(body, "user");
        long wait = throttle.secondsUntilAllowed(request.address());
        if (wait > 0) {
            throw HttpFailure.tooManyRequests("too many failed sign-in attempts; try again in " + wait + " seconds");
        }
        Session session = new Session(database);
        if (user.isPresent()) {
            if (!session.authenticate(user.get(), field(body, "password").orElse(""))) {
                session.close();
                throttle.failed(request.address());
                LOG.log(System.Logger.Level.WARNING, "studio password authentication failed for user \"{0}\" from {1}", user.get(), request.peer());
                throw HttpFailure.unauthorized("authentication failed");
            }
            throttle.succeeded(request.address());
        } else if (options.authenticationRequired().getAsBoolean()) {
            session.close();
            throw HttpFailure.unauthorized("user and password are required");
        }
        Sessions.Entry entry = sessions.open(session);
        LOG.log(System.Logger.Level.INFO, "studio session opened for user={0} role={1} from {2}",
                session.principal().user(), session.principal().role(), request.peer());
        boolean secure = options.tls().isPresent() || request.headers().getOrDefault("X-Forwarded-Proto", List.of()).contains("https");
        return Response.json(JsonFields.object().put("session", describe(session)).build(),
                COOKIE + "=" + entry.token() + "; Path=/; HttpOnly; SameSite=Strict" + (secure ? "; Secure" : ""));
    }

    private Response logout(Request request) {
        request.cookie(COOKIE).ifPresent(sessions::close);
        return Response.json(JsonFields.object().put("session", Json.NULL).build(),
                COOKIE + "=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0");
    }

    private Response query(Request request) {
        Sessions.Entry entry = session(request);
        String script = field(request.json(), "script").orElseThrow(() -> HttpFailure.badRequest("script is required"));
        List<Json> frames = new ArrayList<>();
        entry.exclusively(session -> {
            List<Parser.Sourced> statements;
            try {
                statements = Parser.sourced(script);
            } catch (HStoreException failure) {
                frames.add(JsonFields.object().put("statement", "Parse").put("text", script)
                        .put("error", error(failure.code().name(), failure.getMessage(), false)).build());
                return null;
            }
            for (Parser.Sourced sourced : statements) {
                Statement statement = sourced.statement();
                long started = System.nanoTime();
                String kind = statement.getClass().getSimpleName();
                try {
                    QueryResult result = session.execute(statement);
                    long micros = (System.nanoTime() - started) / 1_000;
                    options.statements().executed("studio", session.principal().user(), statement, micros / 1_000, sourced.text());
                    frames.add(JsonFields.object()
                            .put("statement", kind)
                            .put("text", sourced.text())
                            .put("elapsedMicros", micros)
                            .putAll(result.json())
                            .put("atoms", JsonFields.array(result.atoms().limit(MAX_RESULT_ATOMS).mapToObj(JsonFields::number)))
                            .build());
                } catch (HStoreException failure) {
                    frames.add(JsonFields.object().put("statement", kind).put("text", sourced.text())
                            .put("error", error(failure.code().name(), failure.getMessage(), failure.retryable())).build());
                    break;
                }
            }
            return null;
        });
        return Response.json(JsonFields.object()
                .put("generation", database.engine().transactions().current().id())
                .put("session", entry.exclusively(this::describe))
                .put("results", new Json.Array(frames))
                .build());
    }

    private Response graph(Request request) {
        Session session = session(request).session();
        Neighborhood.Limits limits = new Neighborhood.Limits(request.integer("limit", 300, 5_000),
                request.integer("members", 64, 1_000), request.integer("incident", 100, 5_000));
        int depth = request.integer("expand", 0, 3);
        boolean connect = request.parameter("connect").map(Boolean::parseBoolean).orElse(false);
        Optional<long[]> ids = request.parameter("ids").map(text -> Arrays.stream(text.split(","))
                .map(String::strip).filter(value -> !value.isEmpty()).mapToLong(Long::parseLong).toArray());
        Optional<String> type = request.parameter("type");
        Optional<String> key = request.parameter("key");
        Function<Reader, Json> work = reader -> {
            if (ids.isPresent()) {
                return Neighborhood.around(reader, LongStream.of(ids.get()), depth, connect, limits);
            }
            if (type.isPresent() && key.isPresent()) {
                return Neighborhood.around(reader, reader.find(type.get(), key.get()).stream(), depth, connect, limits);
            }
            return Neighborhood.sample(reader, type, limits);
        };
        OptionalLong generation = request.parameter("generation").stream().mapToLong(Long::parseLong).findFirst();
        Json graph = generation.isPresent()
                ? database.readAt(generation.getAsLong(), session.branch(), session.principal(), work)
                : database.read(session.branch(), session.principal(), work);
        return Response.json(graph);
    }

    private Response schema(Request request) {
        Session session = session(request).session();
        return Response.json(database.read(session.branch(), session.principal(), reader -> JsonFields.object()
                .put("generation", reader.generation())
                .put("types", JsonFields.array(reader.schema().types(reader.view()).stream()
                        .sorted(Comparator.comparing(TypeDef::kind).thenComparing(TypeDef::name))
                        .map(type -> type(reader, type))))
                .put("branches", JsonFields.array(database.engine().branches().stream().map(Studio::branch)))
                .build()));
    }

    private static Json type(Reader reader, TypeDef type) {
        return JsonFields.object()
                .put("id", type.id())
                .put("name", type.name())
                .put("kind", type.kind().name())
                .put("version", type.version())
                .put("count", reader.countOfType(type.id()))
                .put("roles", JsonFields.array(type.roles().stream().map(JsonFields::text)))
                .put("properties", JsonFields.array(type.properties().stream().map(property -> JsonFields.object()
                        .put("name", property.name())
                        .put("type", property.type().name())
                        .put("indexed", property.indexed())
                        .put("required", property.required())
                        .build())))
                .put("jsonIndexes", JsonFields.array(type.jsonIndexes().stream().map(index -> JsonFields.object()
                        .put("path", index.path()).put("type", index.type().name()).build())))
                .build();
    }

    private static Json branch(Branch branch) {
        return JsonFields.object()
                .put("id", branch.id())
                .put("name", branch.name())
                .put("parent", branch.parent())
                .put("state", branch.state().name())
                .put("baseGeneration", branch.baseGeneration())
                .put("createdAt", branch.createdAt())
                .build();
    }

    private Response history(Request request) {
        session(request);
        TransactionManager transactions = database.engine().transactions();
        Stream<Generation> generations = Stream.concat(transactions.history().stream(), Stream.of(transactions.current()))
                .distinct().sorted(Comparator.comparingLong(Generation::id));
        return Response.json(JsonFields.object()
                .put("current", transactions.current().id())
                .put("generations", JsonFields.array(generations.map(generation -> JsonFields.object()
                        .put("id", generation.id())
                        .put("wallTime", generation.wallTime())
                        .put("txnId", generation.txnId())
                        .build())))
                .build());
    }

    private Response stats(Request request) {
        Session session = session(request).session();
        if (session.principal().role() != Role.ADMIN) {
            throw HttpFailure.forbidden("the dashboard requires the ADMIN role");
        }
        EngineStats engine = database.engine().stats();
        TransactionManager.Statistics transactions = database.engine().transactions().statistics();
        Recovery.Outcome recovery = database.engine().recovery();
        EngineOptions configured = database.engine().options();
        return Response.json(JsonFields.object()
                .put("sampledAt", System.currentTimeMillis())
                .put("startedAt", startedAt)
                .put("directory", database.engine().directory().toString())
                .put("generation", engine.generation())
                .put("engine", JsonFields.object()
                        .put("commits", engine.commits())
                        .put("rebases", engine.rebases())
                        .put("conflicts", engine.conflicts())
                        .put("readOnly", transactions.readOnly())
                        .put("pagesRead", engine.pagesRead())
                        .put("pagesWritten", engine.pagesWritten())
                        .put("dataBytesWritten", engine.dataBytesWritten())
                        .put("cacheHits", engine.cacheHits())
                        .put("cacheMisses", engine.cacheMisses())
                        .put("cacheHitRate", engine.cacheHitRate())
                        .put("walBytes", engine.walBytes())
                        .put("walSegments", engine.walSegments())
                        .put("feedBytes", engine.feedBytes())
                        .put("averageGroupCommit", transactions.averageGroupCommit())
                        .build())
                .put("segments", JsonFields.array(engine.segments().stream().map(segment -> JsonFields.object()
                        .put("id", segment.id())
                        .put("state", segment.state().name())
                        .put("pages", segment.pages())
                        .put("bytes", segment.bytes())
                        .put("live", database.engine().liveness().getOrDefault(segment.id(), 0L))
                        .build())))
                .put("recovery", JsonFields.object()
                        .put("replayedCommits", recovery.replayedCommits())
                        .put("discardedTransactions", recovery.discardedTransactions())
                        .put("discardedCommits", recovery.discardedCommits())
                        .build())
                .put("configuration", JsonFields.object()
                        .put("pageSize", configured.pageSize())
                        .put("segmentBytes", (long) configured.pagesPerSegment() * configured.pageSize())
                        .put("durability", configured.durability().name())
                        .put("walMode", configured.walMode().name())
                        .put("cacheBytes", configured.cacheBytes())
                        .put("historyLimit", configured.historyLimit())
                        .put("checkpointWalBytes", configured.checkpointWalBytes())
                        .build())
                .put("semanticGeneration", database.semantic().indexedGeneration())
                .put("studioSessions", sessions.size())
                .put("branches", JsonFields.array(database.engine().branches().stream().map(Studio::branch)))
                .build());
    }

    private static Optional<String> field(Json body, String name) {
        return body instanceof Json.Obj(Map<String, Json> fields) && fields.get(name) instanceof Json.Str(String value)
                ? Optional.of(value) : Optional.empty();
    }

    @Override
    public void close() {
        http.stop(0);
        executor.close();
        sessions.close();
    }
}

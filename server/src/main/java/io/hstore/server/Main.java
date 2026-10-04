package io.hstore.server;

import io.hstore.db.HypergraphDatabase;
import io.hstore.db.security.Principal;
import io.hstore.db.security.Role;
import io.hstore.db.security.Security;
import io.hstore.engine.HStoreException;
import io.hstore.engine.StorageEngine;
import io.hstore.server.studio.Studio;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Level;

public final class Main {

    static final String VERSION = "0.1.0";

    private static final String USAGE = """
            usage: hstore <command> [options]
              init    <dir> [--superuser NAME --password PW] [--<setting> VALUE]...   create a data directory
              serve   <dir> [--<setting> VALUE]...                                    run the TCP server and HStore Studio
              shell   <dir>                                  interactive HQL shell on an embedded database
              exec    <dir> (-c STATEMENTS | FILE)           run HQL against an embedded database
              connect HOST:PORT [--user NAME --password PW]  interactive shell against a server
              ping    HOST:PORT                              exit 0 when a server accepts connections
              bench   <dir> [--scenario NAME] [--scale N]    adversarial storage benchmarks
              check   <dir>                                  verify checksums and tree invariants
              config  [<dir>]                                print effective settings
              version
            settings are read from <dir>/hstore.conf, then HSTORE_<SETTING> variables, then --<setting> flags.
            """;

    sealed interface Command {
        record Init(Path directory, Optional<String> superuser, Optional<String> password) implements Command {
        }

        record Serve(Path directory) implements Command {
        }

        record Shell(Path directory) implements Command {
        }

        record Exec(Path directory, String script) implements Command {
        }

        record Connect(InetSocketAddress address, Optional<RemoteEndpoint.Credentials> credentials) implements Command {
        }

        record Ping(InetSocketAddress address) implements Command {
        }

        record Bench(Path directory, String scenario, int scale) implements Command {
        }

        record Check(Path directory) implements Command {
        }

        record Config(Optional<Path> directory) implements Command {
        }

        record Version() implements Command {
        }

        record Help() implements Command {
        }
    }

    record Arguments(List<String> positional, Map<String, String> options) {
        static Arguments parse(String[] args) {
            Deque<String> pending = new ArrayDeque<>(List.of(args));
            List<String> positional = new ArrayList<>();
            Map<String, String> options = new HashMap<>();
            while (!pending.isEmpty()) {
                String next = pending.pop();
                if (next.equals("-c") && !pending.isEmpty()) {
                    options.put("c", pending.pop());
                } else if (next.startsWith("--")) {
                    String key = next.replaceFirst("^-+", "");
                    boolean hasValue = !pending.isEmpty() && !pending.peek().startsWith("--");
                    options.put(key, hasValue ? pending.pop() : "true");
                } else {
                    positional.add(next);
                }
            }
            return new Arguments(positional, options);
        }

        String require(int index, String what) {
            if (positional.size() <= index) {
                throw new IllegalArgumentException("missing " + what);
            }
            return positional.get(index);
        }

        Optional<String> option(String name) {
            return Optional.ofNullable(options.get(name));
        }
    }

    private Main() {
    }

    public static void main(String[] args) {
        try {
            Arguments arguments = Arguments.parse(args);
            System.exit(run(command(arguments), arguments));
        } catch (IllegalArgumentException | HStoreException failure) {
            IO.println("hstore: " + failure.getMessage());
            System.exit(2);
        } catch (UncheckedIOException failure) {
            IO.println("hstore: " + failure.getMessage() + (failure.getCause() == null ? "" : ": " + failure.getCause().getMessage()));
            System.exit(2);
        }
    }

    private static Command command(Arguments arguments) {
        if (arguments.positional().isEmpty()) {
            return new Command.Help();
        }
        Map<String, String> environment = System.getenv();
        return switch (arguments.positional().getFirst()) {
            case "init" -> new Command.Init(directory(arguments),
                    arguments.option("superuser").or(() -> Optional.ofNullable(environment.get("HSTORE_USER"))),
                    arguments.option("password").or(() -> Optional.ofNullable(environment.get("HSTORE_PASSWORD"))));
            case "serve" -> new Command.Serve(directory(arguments));
            case "shell" -> new Command.Shell(directory(arguments));
            case "exec" -> new Command.Exec(directory(arguments),
                    arguments.option("c").orElseGet(() -> read(Path.of(arguments.require(2, "script file")))));
            case "connect" -> new Command.Connect(address(arguments.require(1, "host:port")), credentials(arguments, environment));
            case "ping" -> new Command.Ping(address(arguments.require(1, "host:port")));
            case "bench" -> new Command.Bench(directory(arguments), arguments.option("scenario").orElse("all"),
                    Integer.parseInt(arguments.option("scale").orElse("1")));
            case "check" -> new Command.Check(directory(arguments));
            case "config" -> new Command.Config(arguments.positional().size() > 1 ? Optional.of(directory(arguments)) : Optional.empty());
            case "version", "--version" -> new Command.Version();
            case "help", "--help", "-h" -> new Command.Help();
            default -> throw new IllegalArgumentException("unknown command " + arguments.positional().getFirst() + "\n" + USAGE);
        };
    }

    private static Optional<RemoteEndpoint.Credentials> credentials(Arguments arguments, Map<String, String> environment) {
        Optional<String> user = arguments.option("user").or(() -> Optional.ofNullable(environment.get("HSTORE_USER")));
        Optional<String> password = arguments.option("password").or(() -> Optional.ofNullable(environment.get("HSTORE_PASSWORD")));
        return user.map(name -> new RemoteEndpoint.Credentials(name, password.orElseGet(() -> prompt(name))));
    }

    private static String prompt(String user) {
        return Optional.ofNullable(System.console())
                .map(console -> new String(console.readPassword("password for %s: ", user)))
                .orElseThrow(() -> new IllegalArgumentException("no password given for " + user + " and no console to prompt on"));
    }

    private static Path directory(Arguments arguments) {
        return Path.of(arguments.require(1, "data directory"));
    }

    private static ServerConfig config(Optional<Path> directory, Arguments arguments) {
        return ServerConfig.load(directory, System.getenv(), arguments.options());
    }

    private static int run(Command command, Arguments arguments) {
        return switch (command) {
            case Command.Help _ -> {
                IO.println(USAGE);
                yield 0;
            }
            case Command.Version _ -> {
                IO.println("hstore " + VERSION + " (Java " + Runtime.version() + ")");
                yield 0;
            }
            case Command.Config(Optional<Path> directory) -> {
                ServerConfig config = config(directory, arguments);
                for (Setting setting : Setting.values()) {
                    boolean secret = setting == Setting.EMBEDDING_API_KEY && !config.string(setting).isBlank();
                    IO.println("%-24s = %s".formatted(setting.key(), secret ? "***" : config.string(setting)));
                }
                yield 0;
            }
            case Command.Init(Path directory, Optional<String> superuser, Optional<String> password) -> init(directory, superuser, password, arguments);
            case Command.Serve(Path directory) -> serve(directory, arguments);
            case Command.Shell(Path directory) -> {
                ServerConfig config = config(Optional.of(directory), arguments);
                Logging.configure(Level.WARNING, "", 1, 1);
                try (Endpoint endpoint = new Endpoint.Local(HypergraphDatabase.open(directory, config.databaseOptions()), directory.toString())) {
                    new Shell(endpoint).run();
                }
                yield 0;
            }
            case Command.Exec(Path directory, String script) -> {
                ServerConfig config = config(Optional.of(directory), arguments);
                Logging.configure(Level.WARNING, "", 1, 1);
                try (Endpoint endpoint = new Endpoint.Local(HypergraphDatabase.open(directory, config.databaseOptions()), directory.toString())) {
                    String output = endpoint.execute(script);
                    IO.println(output);
                    yield output.lines().anyMatch(line -> line.startsWith("error")) ? 1 : 0;
                }
            }
            case Command.Connect(InetSocketAddress address, Optional<RemoteEndpoint.Credentials> credentials) -> {
                try (Endpoint endpoint = new RemoteEndpoint(address, credentials)) {
                    new Shell(endpoint).run();
                }
                yield 0;
            }
            case Command.Ping(InetSocketAddress address) -> {
                try (RemoteEndpoint endpoint = new RemoteEndpoint(address, Optional.empty())) {
                    IO.println(address.getHostString() + ":" + address.getPort() + " - accepting connections (" + endpoint.describe() + ")");
                    yield 0;
                } catch (RuntimeException unreachable) {
                    IO.println(address.getHostString() + ":" + address.getPort() + " - no response");
                    yield 2;
                }
            }
            case Command.Bench(Path directory, String scenario, int scale) -> {
                Logging.configure(Level.WARNING, "", 1, 1);
                IO.println(Bench.header());
                new Bench(directory, config(Optional.empty(), arguments).engineOptions(), scale).run(scenario);
                yield 0;
            }
            case Command.Check(Path directory) -> {
                ServerConfig config = config(Optional.of(directory), arguments);
                Logging.configure(Level.WARNING, "", 1, 1);
                try (StorageEngine engine = StorageEngine.open(directory, config.engineOptions().withExtensions(
                        HypergraphDatabase.extensionSlots(), List.of()))) {
                    IO.println(Check.run(engine));
                }
                yield 0;
            }
        };
    }

    private static int init(Path directory, Optional<String> superuser, Optional<String> password, Arguments arguments) {
        if (Files.exists(directory.resolve("FORMAT"))) {
            if (arguments.option("if-not-exists").isPresent()) {
                IO.println("data directory " + directory + " is already initialised");
                return 0;
            }
            throw new IllegalArgumentException("data directory " + directory + " is already initialised");
        }
        Map<Setting, String> overrides = new EnumMap<>(Setting.class);
        arguments.options().forEach((key, value) -> {
            if (ServerConfig.isSetting(key)) {
                overrides.put(Setting.of(key), value);
            }
        });
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(ServerConfig.FILE), ServerConfig.template(overrides));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot initialise " + directory, e);
        }
        ServerConfig config = config(Optional.of(directory), arguments);
        Logging.configure(config);
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, config.databaseOptions())) {
            if (superuser.isPresent()) {
                String secret = password.orElseThrow(() -> new IllegalArgumentException("--password is required with --superuser"));
                database.write(writer -> {
                    Security.createUser(writer.transaction(), superuser.get(), secret, Principal.DEFAULT_TENANT, Role.ADMIN);
                    return null;
                });
            }
        }
        IO.println("initialised " + directory + superuser.map(user -> " with superuser " + user).orElse(" without authentication"));
        return 0;
    }

    private static int serve(Path directory, Arguments arguments) {
        ServerConfig config = config(Optional.of(directory), arguments);
        Logging.configure(config);
        System.Logger log = System.getLogger("hstore.main");
        log.log(System.Logger.Level.INFO, "starting hstore {0} on Java {1}", VERSION, Runtime.version());
        String overrides = config.describe();
        if (!overrides.isEmpty()) {
            log.log(System.Logger.Level.INFO, "configuration: {0}", overrides);
        }
        HypergraphDatabase database = HypergraphDatabase.open(directory, config.databaseOptions());
        Server.Policy policy = Server.Policy.from(config);
        Server server = new Server(database, config.address(), policy);
        Optional<Studio> studio = config.flag(Setting.STUDIO) ? Optional.of(new Studio(database, new Studio.Options(
                new InetSocketAddress(config.string(Setting.LISTEN_ADDRESS), config.integer(Setting.STUDIO_PORT)),
                () -> policy.authenticationRequired(database), policy.statements()::executed, VERSION,
                Duration.ofMinutes(config.integer(Setting.STUDIO_SESSION_MINUTES)),
                Optional.of(config.string(Setting.STUDIO_ASSETS)).filter(value -> !value.isBlank()).map(Path::of)))) : Optional.empty();
        studio.ifPresent(Studio::start);
        Runtime.getRuntime().addShutdownHook(Thread.ofPlatform().unstarted(() -> {
            log.log(System.Logger.Level.INFO, "received shutdown request; closing connections and checkpointing");
            studio.ifPresent(Studio::close);
            server.close();
            database.close();
        }));
        server.serve();
        return 0;
    }

    private static InetSocketAddress address(String text) {
        int colon = text.lastIndexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("expected host:port, got " + text);
        }
        return new InetSocketAddress(text.substring(0, colon), Integer.parseInt(text.substring(colon + 1)));
    }

    private static String read(Path script) {
        try {
            return Files.readString(script);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read script " + script, e);
        }
    }
}

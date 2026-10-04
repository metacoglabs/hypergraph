package io.hstore.server;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.semantic.Encoder;
import io.hstore.db.semantic.HttpEncoder;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.txn.Durability;
import io.hstore.engine.txn.WalMode;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class ServerConfig {

    static final String FILE = "hstore.conf";

    enum Source { DEFAULT, FILE, ENVIRONMENT, COMMAND_LINE }

    private final Map<Setting, String> values = new EnumMap<>(Setting.class);
    private final Map<Setting, Source> sources = new EnumMap<>(Setting.class);

    private ServerConfig() {
        for (Setting setting : Setting.values()) {
            values.put(setting, setting.fallback());
            sources.put(setting, Source.DEFAULT);
        }
    }

    static ServerConfig load(Optional<Path> directory, Map<String, String> environment, Map<String, String> commandLine) {
        ServerConfig config = new ServerConfig();
        directory.map(path -> path.resolve(FILE)).filter(Files::exists).ifPresent(file -> {
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(file)) {
                properties.load(in);
            } catch (IOException e) {
                throw new UncheckedIOException("cannot read " + file, e);
            }
            properties.forEach((key, value) -> config.set(Setting.of(key.toString()), value.toString().strip(), Source.FILE));
        });
        for (Setting setting : Setting.values()) {
            Optional.ofNullable(environment.get(setting.environmentVariable()))
                    .ifPresent(value -> config.set(setting, value, Source.ENVIRONMENT));
        }
        commandLine.forEach((key, value) -> {
            if (isSetting(key)) {
                config.set(Setting.of(key), value, Source.COMMAND_LINE);
            }
        });
        return config;
    }

    static boolean isSetting(String key) {
        try {
            Setting.of(key);
            return true;
        } catch (IllegalArgumentException unknown) {
            return false;
        }
    }

    private void set(Setting setting, String value, Source source) {
        values.put(setting, value);
        sources.put(setting, source);
    }

    String string(Setting setting) {
        return values.get(setting);
    }

    int integer(Setting setting) {
        return Integer.parseInt(values.get(setting));
    }

    long number(Setting setting) {
        return Long.parseLong(values.get(setting));
    }

    boolean flag(Setting setting) {
        return switch (values.get(setting).toLowerCase()) {
            case "on", "true", "yes", "1" -> true;
            case "off", "false", "no", "0" -> false;
            default -> throw new IllegalArgumentException(setting.key() + " must be on or off, not " + values.get(setting));
        };
    }

    InetSocketAddress address() {
        return new InetSocketAddress(string(Setting.LISTEN_ADDRESS), integer(Setting.PORT));
    }

    EngineOptions engineOptions() {
        return EngineOptions.defaults()
                .withPageSize(integer(Setting.PAGE_SIZE))
                .withDurability(Durability.valueOf(string(Setting.DURABILITY).toUpperCase()))
                .withWalMode(string(Setting.WAL_MODE).equalsIgnoreCase("images") ? WalMode.PAGE_IMAGES : WalMode.PAGE_REFERENCES)
                .withCachedNodes(integer(Setting.CACHE_NODES))
                .withCheckpointWalBytes(number(Setting.CHECKPOINT_WAL_MB) << 20)
                .withHistoryLimit(integer(Setting.HISTORY_LIMIT))
                .withCompactionLiveRatio(Double.parseDouble(string(Setting.COMPACTION_LIVE_RATIO)))
                .withFeedRetention(number(Setting.FEED_RETENTION_GENERATIONS));
    }

    DatabaseOptions databaseOptions() {
        long transactionPages = number(Setting.TRANSACTION_PAGE_BUDGET);
        return DatabaseOptions.defaults()
                .withEngine(engineOptions())
                .withQueryPages(number(Setting.QUERY_PAGE_BUDGET))
                .withTransactionPages(transactionPages <= 0 ? Long.MAX_VALUE : transactionPages)
                .withEncoder(encoder());
    }

    private Encoder encoder() {
        int dimensions = integer(Setting.EMBEDDING_DIMENSIONS);
        Optional<String> key = Optional.of(string(Setting.EMBEDDING_API_KEY)).filter(value -> !value.isBlank());
        return switch (string(Setting.EMBEDDING_PROVIDER).toLowerCase()) {
            case "hashing" -> Encoder.hashing(dimensions);
            case "openai" -> new HttpEncoder(URI.create(required(Setting.EMBEDDING_URL)), required(Setting.EMBEDDING_MODEL), dimensions, key,
                    HttpEncoder.Protocol.OPENAI);
            case "ollama" -> new HttpEncoder(URI.create(required(Setting.EMBEDDING_URL)), required(Setting.EMBEDDING_MODEL), dimensions, key,
                    HttpEncoder.Protocol.OLLAMA);
            default -> throw new IllegalArgumentException("embedding_provider must be hashing, openai or ollama");
        };
    }

    private String required(Setting setting) {
        String value = string(setting);
        if (value.isBlank()) {
            throw new IllegalArgumentException(setting.key() + " is required by embedding_provider " + string(Setting.EMBEDDING_PROVIDER));
        }
        return value;
    }

    String describe() {
        return Stream.of(Setting.values())
                .filter(setting -> sources.get(setting) != Source.DEFAULT)
                .map(setting -> setting.key() + "=" + (setting == Setting.EMBEDDING_API_KEY ? "***" : string(setting))
                        + " (" + sources.get(setting).name().toLowerCase().replace('_', ' ') + ")")
                .collect(Collectors.joining(", "));
    }

    static String template(Map<Setting, String> overrides) {
        StringBuilder out = new StringBuilder("# HStore configuration. Environment variables HSTORE_<SETTING> and --setting flags override these values.\n");
        for (Setting setting : Setting.values()) {
            out.append("\n# ").append(setting.description()).append('\n');
            String value = overrides.getOrDefault(setting, setting.fallback());
            out.append(overrides.containsKey(setting) ? "" : "#").append(setting.key()).append(" = ").append(value).append('\n');
        }
        return out.toString();
    }
}

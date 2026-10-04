package io.hstore.server;

enum Setting {
    LISTEN_ADDRESS("listen_address", "127.0.0.1", "interface the TCP server binds; use 0.0.0.0 inside containers"),
    PORT("port", "7432", "TCP port of the HQL wire protocol"),
    MAX_CONNECTIONS("max_connections", "200", "concurrent client connections before new ones are refused"),
    AUTHENTICATION("authentication", "auto", "auto requires credentials once a user exists; on always; off never"),
    IDLE_TIMEOUT_SECONDS("idle_timeout_seconds", "0", "close connections idle for this long; 0 disables"),
    STUDIO("studio", "on", "serve the HStore Studio web console over HTTP"),
    STUDIO_PORT("studio_port", "7480", "HTTP port of HStore Studio"),
    STUDIO_SESSION_MINUTES("studio_session_minutes", "60", "idle minutes before a studio session is closed"),
    STUDIO_ASSETS("studio_assets", "", "serve Studio files from this directory instead of the binary; for Studio development"),
    PAGE_SIZE("page_size", "16384", "page size in bytes, fixed when the data directory is initialised"),
    DURABILITY("durability", "sync", "sync waits for fsync before acknowledging commits; async does not"),
    WAL_MODE("wal_mode", "references", "references logs page checksums and syncs data first; images logs full page images"),
    CACHE_NODES("cache_nodes", "65536", "decoded tree nodes kept in the page cache"),
    CHECKPOINT_WAL_MB("checkpoint_wal_mb", "256", "write-ahead log volume that triggers a background checkpoint"),
    HISTORY_LIMIT("history_limit", "64", "committed generations retained for time travel"),
    COMPACTION_LIVE_RATIO("compaction_live_ratio", "0.5", "sealed segments whose live node images fall below this ratio are compacted"),
    FEED_RETENTION_GENERATIONS("feed_retention_generations", "100000", "change-feed generations kept for HISTORY and subscribers"),
    QUERY_PAGE_BUDGET("query_page_budget", "2000000", "page visits one query may perform before it is aborted"),
    TRANSACTION_PAGE_BUDGET("transaction_page_budget", "0", "pages one transaction may write; 0 is unlimited"),
    EMBEDDING_PROVIDER("embedding_provider", "hashing", "hashing (built in), openai or ollama"),
    EMBEDDING_URL("embedding_url", "", "endpoint of an external embedding service"),
    EMBEDDING_MODEL("embedding_model", "", "model name sent to the embedding service"),
    EMBEDDING_DIMENSIONS("embedding_dimensions", "256", "vector dimensions produced by the encoder"),
    EMBEDDING_API_KEY("embedding_api_key", "", "bearer token for the embedding service"),
    LOG_LEVEL("log_level", "info", "debug, info, warning or error"),
    LOG_STATEMENT("log_statement", "none", "none, ddl, mod or all statements are logged"),
    LOG_MIN_DURATION_MS("log_min_duration_ms", "-1", "log statements slower than this many milliseconds; -1 disables"),
    LOG_CONNECTIONS("log_connections", "on", "log connection, authentication and disconnection events"),
    LOG_DIRECTORY("log_directory", "", "directory for rotating log files in addition to stderr; empty disables"),
    LOG_ROTATION_MB("log_rotation_mb", "64", "size of one log file before rotation"),
    LOG_FILE_COUNT("log_file_count", "8", "number of rotated log files kept");

    private final String key;
    private final String fallback;
    private final String description;

    Setting(String key, String fallback, String description) {
        this.key = key;
        this.fallback = fallback;
        this.description = description;
    }

    String key() {
        return key;
    }

    String fallback() {
        return fallback;
    }

    String description() {
        return description;
    }

    String environmentVariable() {
        return "HSTORE_" + key.toUpperCase();
    }

    static Setting of(String key) {
        String normalized = key.toLowerCase().replace('-', '_');
        for (Setting setting : values()) {
            if (setting.key.equals(normalized)) {
                return setting;
            }
        }
        throw new IllegalArgumentException("unknown setting '" + key + "'");
    }
}

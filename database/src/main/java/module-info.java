module io.hstore.db {
    requires transitive io.hstore.engine;
    requires java.net.http;

    exports io.hstore.db;
    exports io.hstore.db.evidence;
    exports io.hstore.db.hora;
    exports io.hstore.db.payload;
    exports io.hstore.db.property;
    exports io.hstore.db.query;
    exports io.hstore.db.schema;
    exports io.hstore.db.security;
    exports io.hstore.db.semantic;
    exports io.hstore.db.signal;
    exports io.hstore.db.stats;
    exports io.hstore.db.temporal;
    exports io.hstore.db.value;
    exports io.hstore.db.view;
}

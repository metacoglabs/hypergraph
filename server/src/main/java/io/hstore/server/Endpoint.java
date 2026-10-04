package io.hstore.server;

import io.hstore.db.HypergraphDatabase;
import io.hstore.db.query.Ast.Statement;
import io.hstore.db.query.Parser;
import io.hstore.db.query.Session;
import io.hstore.engine.HStoreException;

import java.util.ArrayList;
import java.util.List;

sealed interface Endpoint extends AutoCloseable permits Endpoint.Local, RemoteEndpoint {

    String execute(String script);

    String describe();

    @Override
    void close();

    static String render(Session session, String script) {
        List<String> rendered = new ArrayList<>();
        try {
            for (Statement statement : Parser.script(script)) {
                rendered.add(session.render(session.execute(statement)));
            }
        } catch (HStoreException failure) {
            rendered.add("error [" + failure.code() + (failure.retryable() ? ", retryable" : "") + "]: " + failure.getMessage());
        } catch (RuntimeException failure) {
            rendered.add("error: " + failure);
        }
        return String.join("\n\n", rendered);
    }

    final class Local implements Endpoint {
        private final HypergraphDatabase database;
        private final Session session;
        private final String location;

        Local(HypergraphDatabase database, String location) {
            this.database = database;
            this.session = new Session(database);
            this.location = location;
        }

        @Override
        public String execute(String script) {
            return render(session, script);
        }

        @Override
        public String describe() {
            return location + (session.inTransaction() ? " (in transaction)" : "");
        }

        @Override
        public void close() {
            session.close();
            database.close();
        }
    }
}

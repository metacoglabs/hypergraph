// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server.studio;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.query.Session;
import io.hstore.engine.EngineOptions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class SessionsTest {

    @TempDir
    Path directory;

    @Test
    void idleSessionsGiveBackTheirConnectionsWithoutAnyRequest() {
        try (HypergraphDatabase database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
             Sessions sessions = new Sessions(Duration.ofMillis(200))) {
            try (Session setup = new Session(database)) {
                setup.execute("CREATE USER admin PASSWORD 'admin-password' ROLE admin");
                setup.execute("CREATE USER ops PASSWORD 'ops-password' ROLE reader CONNECTION LIMIT 1");
            }
            Session ops = new Session(database);
            assertTrue(ops.authenticate("ops", "ops-password"));
            sessions.open(ops);
            assertEquals(1, database.connections("ops"));
            assertTimeoutPreemptively(Duration.ofSeconds(10), () -> {
                while (database.connections("ops") > 0) {
                    Thread.onSpinWait();
                }
            });
            assertEquals(0, sessions.size());
            try (Session again = new Session(database)) {
                assertTrue(again.authenticate("ops", "ops-password"));
            }
        }
    }
}

// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.query;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HierarchyStatementTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;
    Session session;

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        session = new Session(database);
        StringBuilder script = new StringBuilder("CREATE NODE TYPE Note;\n");
        String[] topics = {"claims storage moves to hstore", "alice reviews the budget", "router learns regions", "meeting notes about search"};
        for (int i = 0; i < 24; i++) {
            script.append("INSERT NODE Note 'n").append(i).append("' AS $n").append(i).append(";\n");
            script.append("REVISE $n").append(i).append(" MARKDOWN '# Topic ").append(i).append("\n").append(topics[i % 4]).append(" ")
                    .append(i).append("\n' EMBED;\n");
        }
        session.executeAll(script.toString());
    }

    @AfterEach
    void close() {
        session.close();
        database.close();
    }

    private List<String> column(QueryResult result, String name) {
        int index = result.columns().indexOf(name);
        return result.rows().stream().map(row -> row.get(index).render()).toList();
    }

    @Test
    void hierarchiesAreCreatedShownRoutedAndDropped() {
        String model = database.semantic().encoder().model();
        session.execute("CREATE HIERARCHY memory ON MODEL '" + model + "' VIEW 'content' FANOUT 3 LEAVES 4");
        QueryResult shown = session.execute("SHOW HIERARCHIES");
        assertEquals(List.of("memory"), column(shown, "name"));
        assertEquals(List.of("24"), column(shown, "leaves"));
        QueryResult regions = session.execute("REGIONS OF HIERARCHY memory");
        assertTrue(regions.rows().size() >= 6);
        QueryResult routed = session.execute("ROUTE 'alice reviews the budget' IN HIERARCHY memory MASS 1.0 REGIONS 1000 MAX 64 RECALL TOP 3");
        assertEquals(3, routed.rows().size());
        assertTrue(routed.message().contains("recall 1.00"), routed.message());
        assertTrue(column(routed, "path").getFirst().startsWith("Topic "));
        QueryResult narrow = session.execute("ROUTE 'alice reviews the budget' IN HIERARCHY memory MASS 0.2 FALLBACK 1.1 TOP 3");
        assertTrue(narrow.message().contains("leaves examined"), narrow.message());
        session.execute("CALIBRATE HIERARCHY memory TEMPERATURE 0.05 LEVEL 0 SCALE 2 SHIFT 0");
        String root = column(session.execute("REGIONS OF HIERARCHY memory"), "region").getFirst();
        session.execute("REFERENCE REGION " + root + " OF HIERARCHY memory VECTORS ([" + "0.1, ".repeat(255) + "0.1])");
        session.execute("REVISE $n0 MARKDOWN '# Changed\nsomething new entirely\n' EMBED");
        session.execute("REFRESH HIERARCHY memory");
        assertEquals(List.of("24"), column(session.execute("SHOW HIERARCHIES"), "leaves"));
        session.execute("REBUILD HIERARCHY memory");
        session.execute("DROP HIERARCHY memory");
        assertEquals(List.of(), session.execute("SHOW HIERARCHIES").rows());
    }

    @Test
    void badHierarchyStatementsAreRefused() {
        String model = database.semantic().encoder().model();
        assertThrows(HStoreException.class, () -> session.execute("CREATE HIERARCHY h ON MODEL 'unknown' VIEW 'content'"));
        assertThrows(HStoreException.class, () -> session.execute("CREATE HIERARCHY h ON MODEL '" + model + "' VIEW 'content' FANOUT 1"));
        assertThrows(HStoreException.class, () -> session.execute("ROUTE 'x' IN HIERARCHY missing"));
        session.execute("CREATE HIERARCHY h ON MODEL '" + model + "' VIEW 'content'");
        assertThrows(HStoreException.class, () -> session.execute("ROUTE [1, 2] IN HIERARCHY h"));
        assertThrows(HStoreException.class, () -> session.execute("ROUTE 'x' IN HIERARCHY h MASS 0"));
        assertThrows(HStoreException.class, () -> session.execute("REFERENCE REGION 1 OF HIERARCHY h VECTORS ([1])"));
    }
}

// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.Map.entry;
import static org.junit.jupiter.api.Assertions.assertEquals;

class TenantBoundaryTest {

    static final Map<String, String> ENGINE_WIDE = Map.ofEntries(
            entry("io/hstore/db/Reader.java", "defines engineView and the tenant-checked lookups built on it"),
            entry("io/hstore/db/Writer.java", "index backfills and embedding checks that run for every tenant or on shared structures"),
            entry("io/hstore/db/HypergraphDatabase.java", "users, tenants, quotas and the SCRAM secret, which are not tenant data"),
            entry("io/hstore/db/query/Session.java", "the session's own user and tenant records"),
            entry("io/hstore/db/query/Executor.java", "user, tenant and quota administration and the shared schema listing"),
            entry("io/hstore/db/query/Planner.java", "property index estimates keyed by the reader's tenant"),
            entry("io/hstore/db/evidence/Provenance.java", "qualifier and evidence records filtered by their stamped tenant"),
            entry("io/hstore/db/signal/Signals.java", "signals of an atom that Reader.visible accepted"),
            entry("io/hstore/db/temporal/StateBindings.java", "state of an atom that Reader.visible accepted"),
            entry("io/hstore/db/temporal/Temporal.java", "DIFF over whole generations, for global admins only"),
            entry("io/hstore/db/temporal/BranchMerge.java", "branch merges, for global admins only"),
            entry("io/hstore/db/view/MaterializedViews.java", "view maintenance, filtered by the view's tenant"));

    static List<String> rawViewUsers(Path root) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(file -> file.toString().endsWith(".java"))
                    .filter(file -> {
                        try {
                            return Files.readString(file).contains("engineView()");
                        } catch (IOException e) {
                            throw new UncheckedIOException(e);
                        }
                    })
                    .map(file -> root.relativize(file).toString().replace(File.separatorChar, '/'))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void onlyEngineWideCodeReadsTheRawView() throws IOException {
        List<String> users = rawViewUsers(Path.of("src/main/java"));
        assertEquals(List.of(), users.stream().filter(file -> !ENGINE_WIDE.containsKey(file)).toList(),
                "query code must use the tenant-checked methods of Reader; engine-wide code belongs in ENGINE_WIDE with a reason");
        assertEquals(List.of(), ENGINE_WIDE.keySet().stream().filter(file -> !users.contains(file)).sorted().toList(),
                "these files no longer read the raw view; remove them from ENGINE_WIDE");
    }
}

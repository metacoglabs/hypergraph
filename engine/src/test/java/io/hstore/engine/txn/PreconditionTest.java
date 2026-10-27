// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.txn;

import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;
import io.hstore.engine.StorageEngine;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PreconditionTest {

    private static final Slot<Long> GUARDED = Slot.primary(120, "guarded", counters(240));
    private static final Slot<Long> WRITTEN = Slot.primary(121, "written", counters(241));

    @TempDir
    Path directory;

    private static TreeSchema<Long> counters(int id) {
        return new TreeSchema<>(id, "counters-" + id, FingerprintMode.SET,
                ValueCodec.rows(ByteCursor::signedVarLongSize, ByteCursor::putSignedVarLong, ByteCursor::getSignedVarLong),
                (key, value, into) -> into.entry(key, Hashing.of(key, value)));
    }

    private StorageEngine open() {
        return StorageEngine.open(directory, EngineOptions.defaults().withPageSize(1024).withPagesPerSegment(256)
                .withExtensions(List.of(GUARDED, WRITTEN), List.of()));
    }

    private static void put(StorageEngine engine, Slot<Long> slot, long key, long value) {
        engine.write(txn -> {
            txn.put(slot, key, value);
            return null;
        });
    }

    @Test
    void aPreconditionThatChangesNothingIsStillCheckedAtCommit() {
        try (StorageEngine engine = open()) {
            put(engine, GUARDED, 1, 1);
            Transaction guarded = engine.begin();
            guarded.merge(GUARDED, 1, current -> current.equals(Optional.of(1L)), current -> current);
            guarded.put(WRITTEN, 1, 1L);
            put(engine, GUARDED, 1, 2);
            assertThrows(HStoreException.Conflict.class, guarded::commit);

            Transaction unaffected = engine.begin();
            unaffected.merge(GUARDED, 1, current -> current.equals(Optional.of(2L)), current -> current);
            unaffected.put(WRITTEN, 2, 1L);
            put(engine, GUARDED, 5, 5);
            assertEquals(CommitResult.Outcome.REBASED, unaffected.commit().outcome());

            Transaction untouched = engine.begin();
            untouched.merge(GUARDED, 1, current -> current.equals(Optional.of(2L)), current -> current);
            untouched.put(WRITTEN, 3, 1L);
            put(engine, WRITTEN, 9, 9);
            assertEquals(CommitResult.Outcome.REBASED, untouched.commit().outcome());
        }
    }

    @Test
    void aWriteOfTheSameValueStillPreventsALostUpdate() {
        try (StorageEngine engine = open()) {
            put(engine, GUARDED, 1, 1);
            Transaction same = engine.begin();
            same.put(GUARDED, 1, 1L);
            same.put(WRITTEN, 1, 1L);
            put(engine, GUARDED, 1, 7);
            assertThrows(HStoreException.Conflict.class, same::commit);
        }
    }
}

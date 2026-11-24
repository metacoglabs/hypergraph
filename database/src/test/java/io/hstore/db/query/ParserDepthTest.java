// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.query;

import io.hstore.engine.HStoreException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

class ParserDepthTest {

    private static final List<String> DEEP = List.of(
            "MATCH NODE p:Patient WHERE " + "(".repeat(200) + "p.mrn = 'x'" + ")".repeat(200) + " RETURN p",
            "MATCH NODE p:Patient WHERE " + "NOT ".repeat(200) + "p.mrn = 'x' RETURN p");

    private static Throwable parseOnStack(String source, long stackBytes) throws InterruptedException {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(null, () -> {
            try {
                Parser.script(source);
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        }, "small-stack-parser", stackBytes);
        thread.start();
        thread.join();
        return failure.get();
    }

    @Test
    void deepStatementsOnASmallStackAreRefusedAsInvalid() throws InterruptedException {
        for (String source : DEEP) {
            Throwable thrown = parseOnStack(source, 64 * 1024);
            HStoreException.InvalidSchema invalid = assertInstanceOf(HStoreException.InvalidSchema.class, thrown, String.valueOf(thrown));
            assertEquals("statement is nested too deeply to parse", invalid.getMessage());
        }
    }

    @Test
    void deepStatementsStillParseOnALargeStack() throws InterruptedException {
        for (String source : DEEP) {
            assertNull(parseOnStack(source, 8L * 1024 * 1024));
        }
    }
}

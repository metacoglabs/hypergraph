// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotifClassesTest {

    @Test
    void thereAreThirtyConnectedClasses() {
        assertEquals(List.of(20, 21, 22, 23, 30, 31, 52, 53, 55, 63, 64, 65, 67, 68, 69, 71, 75, 76, 77, 79, 84, 85, 86, 87, 94, 95, 116, 117,
                119, 127), MotifClasses.CLASSES);
        assertEquals(Set.of(20, 21, 22, 23, 30, 31), MotifClasses.CLASSES.stream().filter(MotifClasses::open).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(64, 65, 68, 76), MotifClasses.CLASSES.stream().filter(MotifClasses::duplicated).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void theRunningExampleClassifiesAsDesigned() {
        assertEquals(94, MotifClasses.classify(4, 3, 3, 2, 2, 1, 1));
        assertEquals(31, MotifClasses.classify(4, 3, 2, 1, 1, 0, 0));
        assertEquals(30, MotifClasses.classify(3, 3, 2, 2, 1, 0, 0));
        assertEquals(64, MotifClasses.classify(2, 2, 2, 2, 2, 2, 2));
    }

    @Test
    void canonicalCodesAreInvariantUnderPermutation() {
        for (int code = 1; code < 128; code++) {
            int canonical = MotifClasses.canonical(code);
            assertTrue(canonical <= code);
            assertEquals(canonical, MotifClasses.canonical(canonical));
            for (int[] p : MotifClasses.PERMUTATIONS) {
                assertEquals(canonical, MotifClasses.canonical(MotifClasses.permute(code, p)));
            }
        }
        assertFalse(MotifClasses.CLASSES.contains(MotifClasses.canonical(1)));
    }
}

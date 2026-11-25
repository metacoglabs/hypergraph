// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RrfTest {

    @Test
    void ranksAddUpAcrossListsAndRepeatsCountOnce() {
        List<Rrf.Fused<String>> fused = Rrf.combine(List.of(List.of("a", "b", "c", "a"), List.of("c", "a"), List.of()));
        assertEquals(List.of("a", "c", "b"), fused.stream().map(Rrf.Fused::item).toList());
        assertEquals(1.0 / 61 + 1.0 / 62, fused.getFirst().score(), 1e-15);
        assertEquals(List.of(1, 2, 0), fused.getFirst().ranks());
        assertEquals(List.of(2, 0, 0), fused.get(2).ranks());
    }

    @Test
    void tiesKeepTheBestRankThenTheFirstList() {
        List<Rrf.Fused<String>> fused = Rrf.combine(List.of(List.of("x", "y"), List.of("y", "x")));
        assertEquals(List.of("x", "y"), fused.stream().map(Rrf.Fused::item).toList());
        assertEquals(fused.get(0).score(), fused.get(1).score());
    }
}

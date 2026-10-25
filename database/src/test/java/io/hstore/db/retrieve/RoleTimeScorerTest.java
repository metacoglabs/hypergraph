// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.value.Value;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.Weight;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RoleTimeScorerTest {

    private static Incidence valid(long from, long to) {
        return new Incidence(1, 0, Weight.ONE, from, to, 0, 0);
    }

    @Test
    void timeScoresAreExactAtTheEdgesOfTheLongRange() {
        long big = 1L << 60;
        assertEquals(1.0, RoleTimeScorer.time(new RankQuery.TimeSpec.At(big, 10), valid(big, big + 1)));
        assertEquals(Math.exp(-0.1), RoleTimeScorer.time(new RankQuery.TimeSpec.At(big + 1, 10), valid(big, big + 1)), 1e-12);
        assertEquals(0.0, RoleTimeScorer.time(new RankQuery.TimeSpec.At(big + 1, 0), valid(big, big + 1)));
        assertEquals(1.0, RoleTimeScorer.time(new RankQuery.TimeSpec.At(0, 5), valid(Long.MIN_VALUE, Long.MAX_VALUE)));
        assertEquals(0.0, RoleTimeScorer.time(new RankQuery.TimeSpec.At(Long.MAX_VALUE - 1, 1), valid(Long.MIN_VALUE, Long.MIN_VALUE + 1)));
        assertEquals(1.0, RoleTimeScorer.time(new RankQuery.TimeSpec.During(-big, big), valid(Long.MIN_VALUE, Long.MAX_VALUE)));
        assertEquals(0.25, RoleTimeScorer.time(new RankQuery.TimeSpec.During(0, 400), valid(100, 200)));
        assertEquals(0.0, RoleTimeScorer.time(new RankQuery.TimeSpec.During(0, 400), valid(400, 500)));
    }

    @Test
    void nonFiniteValuesScoreZero() {
        RankQuery.Preference near = new RankQuery.Preference.Near("amount", 1, 1, 1);
        RankQuery.Preference equals = new RankQuery.Preference.Equals("amount", new Value.Real(1), 1);
        for (double broken : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertEquals(0.0, RoleTimeScorer.similarity(near, new Value.Real(broken)));
            assertEquals(0.0, RoleTimeScorer.similarity(equals, new Value.Real(broken)));
        }
        assertEquals(1.0, RoleTimeScorer.similarity(equals, new Value.Decimal(BigDecimal.ONE)));
        assertEquals(0.5, RoleTimeScorer.similarity(near, new Value.Real(1.5)), 1e-12);
        assertEquals(0.0, RoleTimeScorer.similarity(near, new Value.Int(2)));
        assertEquals(0.0, RoleTimeScorer.similarity(near, new Value.Text("2")));
    }
}

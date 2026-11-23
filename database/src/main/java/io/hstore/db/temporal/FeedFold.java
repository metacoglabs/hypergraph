// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.temporal;

import io.hstore.db.hora.Budget;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.feed.CommitEvent;
import io.hstore.engine.topology.MemberChange;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

public final class FeedFold {

    public sealed interface Change permits Change.Membership, Change.Edge {
        long edge();

        record Membership(long edge, long member) implements Change {
        }

        record Edge(long edge) implements Change {
        }
    }

    public static final Comparator<Change> ORDER = Comparator.comparingLong(Change::edge)
            .thenComparingLong(change -> change instanceof Change.Membership(long _, long member) ? member : Long.MIN_VALUE);

    public record Span(boolean before, boolean after) {
        public Span then(Span next) {
            return new Span(before, next.after());
        }
    }

    public record Folded(Map<Change, Span> changes, Map<Long, AtomRecord> records) {
    }

    private FeedFold() {
    }

    public static Folded fold(Stream<CommitEvent> events, Lineage lineage, long through, Budget.Meter meter) {
        Map<Change, Span> seen = new LinkedHashMap<>();
        Map<Long, AtomRecord> records = new HashMap<>();
        try (events) {
            events.takeWhile(event -> event.generation() <= through)
                    .peek(_ -> meter.consume(1))
                    .filter(event -> lineage.includes(event.branch(), event.generation()))
                    .forEach(event -> {
                        event.members().forEach(change -> seen.merge(new Change.Membership(change.edge(), change.member()),
                                new Span(!(change instanceof MemberChange.Added), !(change instanceof MemberChange.Removed)), Span::then));
                        event.slots().stream()
                                .filter(change -> change.slot().equals(EngineSlots.CATALOG))
                                .forEach(change -> {
                                    record(records, change.key(), change.before());
                                    record(records, change.key(), change.after());
                                    seen.merge(new Change.Edge(change.key()),
                                            new Span(change.before().isPresent(), change.after().isPresent()), Span::then);
                                });
                    });
        }
        return new Folded(seen, records);
    }

    private static void record(Map<Long, AtomRecord> records, long atom, Optional<?> value) {
        value.filter(AtomRecord.class::isInstance).map(AtomRecord.class::cast).ifPresent(found -> records.putIfAbsent(atom, found));
    }
}

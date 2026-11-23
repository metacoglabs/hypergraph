// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.temporal;

import io.hstore.db.hora.Budget;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.catalog.SlotChange;
import io.hstore.engine.feed.CommitEvent;
import io.hstore.engine.topology.MemberChange;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongFunction;
import java.util.function.LongPredicate;
import java.util.function.Predicate;
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
        return fold(events, lineage, through, meter, _ -> true, _ -> Optional.empty());
    }

    public static Folded fold(Stream<CommitEvent> events, Lineage lineage, long through, Budget.Meter meter,
                              Predicate<AtomRecord> admits, LongFunction<Optional<AtomRecord>> current) {
        Map<Change, Span> seen = new LinkedHashMap<>();
        Map<Long, AtomRecord> records = new HashMap<>();
        try (events) {
            events.takeWhile(event -> event.generation() <= through)
                    .filter(event -> {
                        if (!meter.tryConsume(0)) {
                            throw HStoreException.limit("the change-feed fold passed its time limit");
                        }
                        return lineage.includes(event.branch(), event.generation());
                    })
                    .forEach(event -> {
                        Map<Long, AtomRecord> local = new HashMap<>();
                        List<SlotChange<?>> catalog = event.slots().stream().filter(change -> change.slot().equals(EngineSlots.CATALOG)).toList();
                        catalog.forEach(change -> {
                            record(local, change.key(), change.before());
                            record(local, change.key(), change.after());
                        });
                        Map<Long, Boolean> kept = new HashMap<>();
                        LongPredicate keep = atom -> kept.computeIfAbsent(atom, id -> Optional.ofNullable(local.get(id)).or(() -> current.apply(id))
                                .map(admits::test).orElse(true));
                        event.members().forEach(change -> {
                            if (!keep.test(change.edge())) {
                                return;
                            }
                            meter.consume(1);
                            seen.merge(new Change.Membership(change.edge(), change.member()),
                                    new Span(!(change instanceof MemberChange.Added), !(change instanceof MemberChange.Removed)), Span::then);
                        });
                        catalog.forEach(change -> {
                            if (!keep.test(change.key())) {
                                return;
                            }
                            meter.consume(1);
                            record(records, change.key(), change.before());
                            record(records, change.key(), change.after());
                            seen.merge(new Change.Edge(change.key()), new Span(change.before().isPresent(), change.after().isPresent()), Span::then);
                        });
                    });
        }
        return new Folded(seen, records);
    }

    public static Map<Long, AtomRecord> records(Stream<CommitEvent> events, Lineage lineage, long through, Budget.Meter meter, Set<Long> wanted) {
        Map<Long, AtomRecord> records = new HashMap<>();
        try (events) {
            events.takeWhile(event -> event.generation() <= through)
                    .filter(event -> {
                        if (!meter.tryConsume(0)) {
                            throw HStoreException.limit("the change-feed fold passed its time limit");
                        }
                        return lineage.includes(event.branch(), event.generation());
                    })
                    .forEach(event -> event.slots().stream()
                            .filter(change -> change.slot().equals(EngineSlots.CATALOG) && wanted.contains(change.key()))
                            .forEach(change -> {
                                meter.consume(1);
                                record(records, change.key(), change.before());
                                record(records, change.key(), change.after());
                            }));
        }
        return records;
    }

    private static void record(Map<Long, AtomRecord> records, long atom, Optional<?> value) {
        value.filter(AtomRecord.class::isInstance).map(AtomRecord.class::cast).ifPresent(found -> records.putIfAbsent(atom, found));
    }
}

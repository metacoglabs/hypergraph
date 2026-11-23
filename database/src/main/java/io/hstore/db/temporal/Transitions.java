// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.temporal;

import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Reader;
import io.hstore.db.hora.Budget;
import io.hstore.db.temporal.FeedFold.Change;
import io.hstore.db.temporal.FeedFold.Span;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.AtomRecord.EdgeRecord;
import io.hstore.engine.feed.ChangeFeed;
import io.hstore.engine.feed.CommitEvent;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.txn.Snapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.function.LongFunction;

public final class Transitions {

    public enum Direction { APPEARED, DISAPPEARED }

    public enum Route { AUTO, DIFF, FEED }

    private Transitions() {
    }

    public static List<Change> of(Reader reader, OptionalInt edgeType, long after, long through, Direction direction,
                                  boolean members, Budget.Meter meter) {
        return of(reader, edgeType, after, through, direction, members, meter, Route.AUTO);
    }

    static List<Change> of(Reader reader, OptionalInt edgeType, long after, long through, Direction direction,
                           boolean members, Budget.Meter meter, Route route) {
        if (after >= through) {
            throw HStoreException.invalid("TRANSITIONS needs FROM before TO, got generations " + after + " and " + through);
        }
        if (through > reader.generation()) {
            throw HStoreException.invalid("generation " + through + " is after this snapshot's generation " + reader.generation());
        }
        HypergraphDatabase database = reader.database();
        Lineage lineage = Lineage.of(database, reader.engineView().branch());
        Route chosen = route == Route.AUTO ? (retained(database, lineage, after) && retained(database, lineage, through) ? Route.DIFF : Route.FEED) : route;
        Map<Change, Span> spans = new HashMap<>();
        Map<Long, AtomRecord> records = new HashMap<>();
        if (chosen == Route.DIFF) {
            try {
                diff(database, lineage, after, through, spans, records, meter);
            } catch (HStoreException.InvalidSchema released) {
                if (route == Route.DIFF) {
                    throw released;
                }
                spans.clear();
                records.clear();
                fold(database, reader, lineage, after, through, spans, records, meter);
            }
        } else {
            fold(database, reader, lineage, after, through, spans, records, meter);
        }
        LongFunction<Optional<AtomRecord>> record = atom -> Optional.ofNullable(records.get(atom));
        TreeSet<Change> result = new TreeSet<>(FeedFold.ORDER);
        spans.forEach((change, span) -> {
            boolean kept = direction == Direction.DISAPPEARED ? span.before() && !span.after() : !span.before() && span.after();
            if (!kept || (change instanceof Change.Membership) != members) {
                return;
            }
            Optional<AtomRecord> edge = record.apply(change.edge());
            if (edge.isEmpty() || !(edge.get() instanceof EdgeRecord) || !reader.admits(edge.get())
                    || edgeType.stream().anyMatch(type -> type != edge.get().type())) {
                return;
            }
            if (change instanceof Change.Membership(long _, long member)
                    && record.apply(member).map(found -> !reader.admits(found)).orElse(true)) {
                return;
            }
            result.add(change);
        });
        return List.copyOf(result);
    }

    public static OptionalLong generationAt(Reader reader, long wallTime) {
        HypergraphDatabase database = reader.database();
        long current = database.engine().transactions().current().id();
        Optional<Long> retained = Temporal.resolve(database, wallTime).map(generation -> generation.id())
                .filter(found -> found == current || database.engine().transactions().history().stream()
                        .anyMatch(generation -> generation.id() == found + 1));
        if (retained.isPresent()) {
            return OptionalLong.of(retained.get());
        }
        OptionalLong found = OptionalLong.empty();
        Budget.Meter meter = Budget.DEFAULT.start();
        try (var events = database.engine().feed().replay(0)) {
            var iterator = events.iterator();
            while (iterator.hasNext()) {
                CommitEvent event = iterator.next();
                meter.consume(1);
                if (event.wallTime() > wallTime) {
                    break;
                }
                found = OptionalLong.of(event.generation());
            }
        }
        return found;
    }

    public static String plan(Reader reader, long after, long through) {
        HypergraphDatabase database = reader.database();
        Lineage lineage = Lineage.of(database, reader.engineView().branch());
        if (retained(database, lineage, after) && retained(database, lineage, through)) {
            return "snapshot diff of generations " + after + " and " + through + " (both snapshots are retained)";
        }
        ChangeFeed feed = database.engine().feed();
        if (feed.covers(after)) {
            return "change-feed fold from generation " + after + " to " + through + " (a snapshot is no longer retained; the feed starts at "
                    + feed.firstGeneration() + ")";
        }
        return "fails: generation " + after + " is neither retained nor covered by the change feed, which starts at " + feed.firstGeneration();
    }

    private static boolean retained(HypergraphDatabase database, Lineage lineage, long generation) {
        try (Snapshot _ = database.engine().snapshotAt(generation, lineage.branchAt(generation))) {
            return true;
        } catch (HStoreException.InvalidSchema notRetained) {
            return false;
        }
    }

    private static void diff(HypergraphDatabase database, Lineage lineage, long after, long through,
                             Map<Change, Span> spans, Map<Long, AtomRecord> records, Budget.Meter meter) {
        try (Snapshot before = database.engine().snapshotAt(after, lineage.branchAt(after));
             Snapshot now = database.engine().snapshotAt(through, lineage.branchAt(through))) {
            Reader left = database.reader(before);
            Reader right = database.reader(now);
            Temporal.diff(left, right).forEach(delta -> {
                meter.consume(1);
                switch (delta) {
                    case Temporal.Delta.AtomCreated(long atom, AtomRecord created) -> {
                        records.putIfAbsent(atom, created);
                        spans.put(new Change.Edge(atom), new Span(false, true));
                    }
                    case Temporal.Delta.AtomDeleted(long atom, AtomRecord deleted) -> {
                        records.putIfAbsent(atom, deleted);
                        spans.put(new Change.Edge(atom), new Span(true, false));
                        if (deleted instanceof EdgeRecord) {
                            before.edge(atom).ifPresent(edge -> edge.members().stream().forEach(entry -> {
                                meter.consume(1);
                                spans.put(new Change.Membership(atom, entry.value().member()), new Span(true, false));
                            }));
                        }
                    }
                    case Temporal.Delta.MembersChanged(long edge, List<MemberChange> changes) -> changes.forEach(change -> {
                        switch (change) {
                            case MemberChange.Added _ -> spans.put(new Change.Membership(edge, change.member()), new Span(false, true));
                            case MemberChange.Removed _ -> spans.put(new Change.Membership(edge, change.member()), new Span(true, false));
                            case MemberChange.Updated _ -> {
                            }
                        }
                    });
                }
            });
            List<Long> atoms = new ArrayList<>();
            spans.keySet().forEach(change -> {
                atoms.add(change.edge());
                if (change instanceof Change.Membership(long _, long member)) {
                    atoms.add(member);
                }
            });
            atoms.forEach(atom -> records.computeIfAbsent(atom, id -> now.atom(id).or(() -> before.atom(id)).orElse(null)));
        }
    }

    private static void fold(HypergraphDatabase database, Reader reader, Lineage lineage, long after, long through,
                             Map<Change, Span> spans, Map<Long, AtomRecord> records, Budget.Meter meter) {
        ChangeFeed feed = database.engine().feed();
        if (!feed.covers(after)) {
            throw HStoreException.invalid("generation " + after + " precedes the change feed (first retained " + feed.firstGeneration() + ")");
        }
        database.engine().transactions().awaitPublished(reader.generation());
        FeedFold.Folded folded = FeedFold.fold(feed.replay(after), lineage, through, meter);
        spans.putAll(folded.changes());
        records.putAll(folded.records());
        TreeSet<Long> unknown = new TreeSet<>();
        spans.keySet().forEach(change -> {
            unknown.add(change.edge());
            if (change instanceof Change.Membership(long _, long member)) {
                unknown.add(member);
            }
        });
        unknown.removeIf(records::containsKey);
        unknown.removeIf(atom -> reader.engineView().atom(atom).map(found -> records.put(atom, found) == null).orElse(false));
        if (!unknown.isEmpty() && through < reader.generation()) {
            records.putAll(FeedFold.fold(feed.replay(through), Lineage.of(database, reader.engineView().branch()),
                    reader.generation(), meter).records());
        }
    }
}

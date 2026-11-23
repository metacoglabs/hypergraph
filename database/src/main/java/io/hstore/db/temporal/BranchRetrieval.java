// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.temporal;

import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Reader;
import io.hstore.db.hora.Budget;
import io.hstore.db.property.PropertyBag;
import io.hstore.db.property.PropertyBag.Property;
import io.hstore.db.property.PropertySlots;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.feed.ChangeFeed;
import io.hstore.engine.feed.CommitEvent;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeDiff;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.View;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.LongFunction;

public final class BranchRetrieval {

    public sealed interface Key permits Key.Member, Key.Atom, Key.Property {
        long atom();

        record Member(long edge, long member) implements Key {
            public long atom() {
                return edge;
            }
        }

        record Atom(long atom) implements Key {
        }

        record Property(long atom, int key) implements Key {
        }
    }

    public static final Comparator<Key> KEY_ORDER = Comparator.comparingLong(Key::atom)
            .thenComparingInt(key -> switch (key) {
                case Key.Atom _ -> 0;
                case Key.Property _ -> 1;
                case Key.Member _ -> 2;
            })
            .thenComparingLong(key -> switch (key) {
                case Key.Member(long _, long member) -> member;
                case Key.Property(long _, int property) -> property;
                case Key.Atom _ -> 0;
            });

    public record AtomValue(int tenant, int type, boolean edge, String canonicalKey) {
        static AtomValue of(AtomRecord record) {
            return new AtomValue(record.tenant(), record.type(), record instanceof AtomRecord.EdgeRecord,
                    record instanceof AtomRecord.NodeRecord node && node.hasCanonicalKey() ? node.canonicalKey() : "");
        }
    }

    public record Net(Key key, Optional<Object> before, Optional<Object> after) {
    }

    public enum Side { ONLY_LEFT, ONLY_RIGHT, BOTH_SAME, BOTH_DIFFERENT }

    public record Compared(Key key, Side side, Optional<Net> left, Optional<Net> right) {
    }

    public record AncestorPoint(int branch, long generation) {
    }

    public record Distance(int branch, long distance, long lowerBound) {
    }

    public record Succession(long generation, int branch, List<Net> changes) {
    }

    public sealed interface Scope {
        record Everything() implements Scope {
        }

        record Incidences() implements Scope {
        }

        record OfType(int tenant, int type) implements Scope {
        }

        record Around(long atom) implements Scope {
        }
    }

    public enum Route { AUTO, SNAPSHOT, FEED }

    private final HypergraphDatabase database;
    private final Budget.Meter meter;

    private BranchRetrieval(HypergraphDatabase database, Budget.Meter meter) {
        this.database = database;
        this.meter = meter;
    }

    public static BranchRetrieval on(HypergraphDatabase database, Budget.Meter meter) {
        return new BranchRetrieval(database, meter);
    }

    private Map<Integer, Branch> branches() {
        return new HashMap<>(database.engine().transactions().current().branches());
    }

    private Branch branch(int id) {
        Branch found = branches().get(id);
        if (found == null) {
            throw HStoreException.invalid("branch " + id + " does not exist");
        }
        return found;
    }

    public AncestorPoint ancestor(int left, int right) {
        Map<Integer, Branch> branches = branches();
        Map<Integer, Long> leftPath = new LinkedHashMap<>();
        long limit = Long.MAX_VALUE;
        for (int id = left; ; id = branches.get(id).parent()) {
            leftPath.put(id, limit);
            if (id == Branch.MAIN) {
                break;
            }
            limit = Math.min(limit, branches.get(id).baseGeneration());
        }
        limit = Long.MAX_VALUE;
        for (int id = right; ; id = branches.get(id).parent()) {
            if (leftPath.containsKey(id)) {
                return new AncestorPoint(id, Math.min(limit, leftPath.get(id)));
            }
            limit = Math.min(limit, branches.get(id).baseGeneration());
        }
    }

    private AncestorPoint fork(int branch) {
        Branch found = branch(branch);
        return branch == Branch.MAIN ? new AncestorPoint(Branch.MAIN, 0) : new AncestorPoint(found.parent(), found.baseGeneration());
    }

    public List<Net> delta(int branch, OptionalLong since, Route route) {
        return delta(branch, since, route, new Scope.Everything());
    }

    public List<Net> delta(int branch, OptionalLong since, Route route, Scope scope) {
        long current = database.engine().transactions().current().id();
        if (since.isPresent() && since.getAsLong() > current) {
            throw HStoreException.invalid("generation " + since.getAsLong() + " is after the current generation " + current);
        }
        AncestorPoint from = since.isPresent()
                ? new AncestorPoint(Lineage.of(database, branch).branchAt(since.getAsLong()), since.getAsLong())
                : fork(branch);
        Map<Key, Net> nets = net(from, branch, route);
        Map<Long, AtomValue> records = new HashMap<>();
        try (Snapshot head = database.engine().snapshot(branch)) {
            return nets.values().stream()
                    .filter(net -> inScope(scope, net.key(), atom -> records.computeIfAbsent(atom, id -> {
                        AtomValue found = record(id, head);
                        Net atomNet = nets.get(new Key.Atom(id));
                        return found != null || atomNet == null ? found : (AtomValue) atomNet.before().or(atomNet::after).orElse(null);
                    })))
                    .sorted(Comparator.comparing(Net::key, KEY_ORDER))
                    .toList();
        }
    }

    public List<Key> survivors(int branch, Scope scope) {
        Set<Key> changed = new TreeSet<>(KEY_ORDER);
        net(fork(branch), branch, Route.AUTO).keySet().forEach(changed::add);
        List<Key> result = new ArrayList<>();
        try (Snapshot head = database.engine().snapshot(branch)) {
            state(head, scope, (key, _) -> {
                if (!changed.contains(key)) {
                    result.add(key);
                }
            });
        }
        result.sort(KEY_ORDER);
        return result;
    }

    public List<Compared> compare(int left, int right, Scope scope) {
        if (left == right) {
            branch(left);
            return List.of();
        }
        AncestorPoint point = ancestor(left, right);
        Map<Key, Net> leftSide = net(point, left, Route.AUTO);
        Map<Key, Net> rightSide = net(point, right, Route.AUTO);
        Set<Key> keys = new TreeSet<>(KEY_ORDER);
        keys.addAll(leftSide.keySet());
        keys.addAll(rightSide.keySet());
        Map<Long, AtomValue> records = new HashMap<>();
        List<Compared> result = new ArrayList<>();
        try (Snapshot leftHead = database.engine().snapshot(left);
             Snapshot rightHead = database.engine().snapshot(right);
             Snapshot base = snapshotOrNull(point)) {
            for (Key key : keys) {
                if (!inScope(scope, key, atom -> records.computeIfAbsent(atom, id -> record(id, leftHead, rightHead, base, leftSide, rightSide)))) {
                    continue;
                }
                Optional<Net> l = Optional.ofNullable(leftSide.get(key));
                Optional<Net> r = Optional.ofNullable(rightSide.get(key));
                Side side = l.isEmpty() ? Side.ONLY_RIGHT : r.isEmpty() ? Side.ONLY_LEFT
                        : Objects.equals(l.get().after(), r.get().after()) ? Side.BOTH_SAME : Side.BOTH_DIFFERENT;
                result.add(new Compared(key, side, l, r));
            }
        }
        return result;
    }

    private Snapshot snapshotOrNull(AncestorPoint point) {
        try {
            return database.engine().snapshotAt(point.generation(), point.branch());
        } catch (HStoreException.InvalidSchema notRetained) {
            return null;
        }
    }

    private static AtomValue record(long atom, View... views) {
        for (View view : views) {
            if (view != null) {
                Optional<AtomRecord> found = view.atom(atom);
                if (found.isPresent()) {
                    return AtomValue.of(found.get());
                }
            }
        }
        return null;
    }

    private static AtomValue record(long atom, View left, View right, View base, Map<Key, Net> leftSide, Map<Key, Net> rightSide) {
        AtomValue found = record(atom, left, right, base);
        if (found != null) {
            return found;
        }
        for (Map<Key, Net> side : List.of(leftSide, rightSide)) {
            Net net = side.get(new Key.Atom(atom));
            if (net != null) {
                return (AtomValue) net.before().or(net::after).orElse(null);
            }
        }
        return null;
    }

    private static boolean inScope(Scope scope, Key key, LongFunction<AtomValue> record) {
        return switch (scope) {
            case Scope.Everything _ -> true;
            case Scope.Incidences _ -> key instanceof Key.Member;
            case Scope.Around(long atom) -> key.atom() == atom || (key instanceof Key.Member(long _, long member) && member == atom);
            case Scope.OfType(int tenant, int type) -> {
                AtomValue found = record.apply(key.atom());
                yield found != null && found.tenant() == tenant && found.type() == type;
            }
        };
    }

    public List<Distance> nearest(int target, List<Integer> candidates, Scope scope, int k) {
        List<Integer> others = candidates.stream().filter(candidate -> candidate != target).distinct().toList();
        Map<Integer, Snapshot> heads = new HashMap<>();
        try (Snapshot head = database.engine().snapshot(target)) {
            for (int candidate : others) {
                heads.put(candidate, database.engine().snapshot(candidate));
            }
            long targetSize = size(head, scope);
            List<Distance> bounded = new ArrayList<>();
            for (int candidate : others) {
                bounded.add(new Distance(candidate, -1, Math.abs(targetSize - size(heads.get(candidate), scope))));
            }
            bounded.sort(Comparator.comparingLong(Distance::lowerBound).thenComparingInt(Distance::branch));
            List<Distance> best = new ArrayList<>();
            for (Distance candidate : bounded) {
                if (best.size() >= k && candidate.lowerBound() > best.get(k - 1).distance()) {
                    break;
                }
                best.add(new Distance(candidate.branch(), distance(head, heads.get(candidate.branch()), scope), candidate.lowerBound()));
                best.sort(Comparator.comparingLong(Distance::distance).thenComparingInt(Distance::branch));
                if (best.size() > k) {
                    best.removeLast();
                }
            }
            return List.copyOf(best);
        } finally {
            heads.values().forEach(Snapshot::close);
        }
    }

    private long size(View view, Scope scope) {
        return switch (scope) {
            case Scope.Incidences _ -> EngineSlots.REVERSE_INDEX.total(view.scan(EngineSlots.REVERSE));
            case Scope.OfType(int tenant, int type) -> EngineSlots.TYPE_INDEX.count(view.scan(EngineSlots.TYPES), EngineSlots.typeKey(tenant, type));
            case Scope.Everything _, Scope.Around _ -> 0;
        };
    }

    private long distance(Snapshot left, Snapshot right, Scope scope) {
        Map<Key, Net> changes = snapshotNet(left, right);
        Map<Long, AtomValue> records = new HashMap<>();
        long distance = 0;
        for (Net net : changes.values()) {
            if (inScope(scope, net.key(), atom -> records.computeIfAbsent(atom, id -> record(id, left, right)))) {
                distance += net.before().isPresent() && net.after().isPresent() ? 2 : 1;
            }
        }
        return distance;
    }

    public List<Succession> after(int branch, long generation, long window) {
        if (window < 0) {
            throw HStoreException.invalid("WINDOW must not be negative");
        }
        ChangeFeed feed = database.engine().feed();
        if (!feed.covers(generation - 1)) {
            throw HStoreException.invalid("generation " + generation + " precedes the change feed (first retained " + feed.firstGeneration() + ")");
        }
        database.engine().transactions().awaitPublished(database.engine().transactions().current().id());
        Lineage lineage = Lineage.of(database, branch);
        Set<Long> touched = new TreeSet<>();
        List<Succession> result = new ArrayList<>();
        long last = generation > Long.MAX_VALUE - window ? Long.MAX_VALUE : generation + window;
        try (var events = feed.replay(generation - 1)) {
            var iterator = events.iterator();
            boolean seen = false;
            while (iterator.hasNext()) {
                CommitEvent event = iterator.next();
                meter.consume(1);
                if (event.generation() > last) {
                    break;
                }
                if (event.generation() == generation) {
                    if (!lineage.includes(event.branch(), generation)) {
                        throw HStoreException.invalid("generation " + generation + " is not on the history of branch " + branch);
                    }
                    seen = true;
                    touched.addAll(atoms(event));
                    continue;
                }
                if (!lineage.includes(event.branch(), event.generation())) {
                    continue;
                }
                List<Net> hits = changes(event).stream()
                        .filter(net -> touched.contains(net.key().atom())
                                || (net.key() instanceof Key.Member(long _, long member) && touched.contains(member)))
                        .sorted(Comparator.comparing(Net::key, KEY_ORDER))
                        .toList();
                if (!hits.isEmpty()) {
                    result.add(new Succession(event.generation(), event.branch(), hits));
                }
            }
            if (!seen) {
                return List.of();
            }
        }
        return result;
    }

    private static Set<Long> atoms(CommitEvent event) {
        Set<Long> atoms = new TreeSet<>();
        changes(event).forEach(net -> {
            atoms.add(net.key().atom());
            if (net.key() instanceof Key.Member(long _, long member)) {
                atoms.add(member);
            }
        });
        return atoms;
    }

    private static List<Net> changes(CommitEvent event) {
        List<Net> changes = new ArrayList<>();
        event.members().forEach(change -> changes.add(switch (change) {
            case MemberChange.Added(long edge, Incidence incidence, long _) ->
                    new Net(new Key.Member(edge, incidence.member()), Optional.empty(), Optional.of(incidence));
            case MemberChange.Removed(long edge, Incidence incidence, long _) ->
                    new Net(new Key.Member(edge, incidence.member()), Optional.of(incidence), Optional.empty());
            case MemberChange.Updated(long edge, Incidence before, Incidence after, long _, long _) ->
                    new Net(new Key.Member(edge, after.member()), Optional.of(before), Optional.of(after));
        }));
        event.slots().forEach(change -> {
            if (change.slot().equals(EngineSlots.CATALOG)) {
                Optional<Object> before = change.before().map(record -> (Object) AtomValue.of((AtomRecord) record));
                Optional<Object> after = change.after().map(record -> (Object) AtomValue.of((AtomRecord) record));
                if (!before.equals(after)) {
                    changes.add(new Net(new Key.Atom(change.key()), before, after));
                }
            } else if (change.slot().equals(PropertySlots.PROPERTIES)) {
                PropertyBag was = change.before().map(PropertyBag.class::cast).orElse(PropertyBag.EMPTY);
                PropertyBag now = change.after().map(PropertyBag.class::cast).orElse(PropertyBag.EMPTY);
                properties(change.key(), was, now, changes::add);
            }
        });
        return changes;
    }

    private static void properties(long atom, PropertyBag was, PropertyBag now, Consumer<Net> sink) {
        TreeSet<Integer> keys = new TreeSet<>(was.entries().keySet());
        keys.addAll(now.entries().keySet());
        for (int key : keys) {
            Optional<Property> before = was.get(key);
            Optional<Property> after = now.get(key);
            if (!before.equals(after)) {
                sink.accept(new Net(new Key.Property(atom, key), before.map(Object.class::cast), after.map(Object.class::cast)));
            }
        }
    }

    private Map<Key, Net> net(AncestorPoint from, int branch, Route route) {
        Route chosen = route;
        Snapshot base = null;
        if (route != Route.FEED) {
            base = snapshotOrNull(from);
            if (base == null && route == Route.SNAPSHOT) {
                throw HStoreException.invalid("ancestor generation " + from.generation() + " is no longer retained");
            }
            chosen = base == null ? Route.FEED : Route.SNAPSHOT;
        }
        if (chosen == Route.SNAPSHOT) {
            try (Snapshot ancestor = base; Snapshot head = database.engine().snapshot(branch)) {
                return snapshotNet(ancestor, head);
            }
        }
        return feedNet(from, branch);
    }

    private Map<Key, Net> snapshotNet(Snapshot before, Snapshot after) {
        Map<Key, Net> result = new TreeMap<>(KEY_ORDER);
        Reader left = database.reader(before);
        Reader right = database.reader(after);
        Temporal.diff(left, right).forEach(delta -> {
            meter.consume(1);
            switch (delta) {
                case Temporal.Delta.AtomCreated(long atom, AtomRecord record) ->
                        result.put(new Key.Atom(atom), new Net(new Key.Atom(atom), Optional.empty(), Optional.of(AtomValue.of(record))));
                case Temporal.Delta.AtomDeleted(long atom, AtomRecord record) -> {
                    result.put(new Key.Atom(atom), new Net(new Key.Atom(atom), Optional.of(AtomValue.of(record)), Optional.empty()));
                    if (record instanceof AtomRecord.EdgeRecord) {
                        before.edge(atom).ifPresent(edge -> edge.members().stream().forEach(entry -> {
                            meter.consume(1);
                            Key key = new Key.Member(atom, entry.value().member());
                            result.put(key, new Net(key, Optional.of(entry.value()), Optional.empty()));
                        }));
                    }
                }
                case Temporal.Delta.MembersChanged(long edge, List<MemberChange> changes) -> changes.forEach(change -> {
                    meter.consume(1);
                    Key key = new Key.Member(edge, change.member());
                    result.put(key, switch (change) {
                        case MemberChange.Added(long _, Incidence incidence, long _) -> new Net(key, Optional.empty(), Optional.of(incidence));
                        case MemberChange.Removed(long _, Incidence incidence, long _) -> new Net(key, Optional.of(incidence), Optional.empty());
                        case MemberChange.Updated(long _, Incidence was, Incidence now, long _, long _) -> new Net(key, Optional.of(was), Optional.of(now));
                    });
                });
            }
        });
        Tree<PropertyBag> leftBags = before.scan(PropertySlots.PROPERTIES);
        Tree<PropertyBag> rightBags = after.scan(PropertySlots.PROPERTIES);
        TreeDiff.diff(leftBags, rightBags).forEach(delta -> {
            meter.consume(1);
            PropertyBag was = switch (delta) {
                case TreeDiff.Delta.Added<PropertyBag> _ -> PropertyBag.EMPTY;
                case TreeDiff.Delta.Removed<PropertyBag>(long _, PropertyBag bag) -> bag;
                case TreeDiff.Delta.Changed<PropertyBag>(long _, PropertyBag bag, PropertyBag _) -> bag;
            };
            PropertyBag now = switch (delta) {
                case TreeDiff.Delta.Added<PropertyBag>(long _, PropertyBag bag) -> bag;
                case TreeDiff.Delta.Removed<PropertyBag> _ -> PropertyBag.EMPTY;
                case TreeDiff.Delta.Changed<PropertyBag>(long _, PropertyBag _, PropertyBag bag) -> bag;
            };
            properties(delta.key(), was, now, net -> result.put(net.key(), net));
        });
        result.values().removeIf(net -> net.before().equals(net.after()));
        return result;
    }

    private Map<Key, Net> feedNet(AncestorPoint from, int branch) {
        ChangeFeed feed = database.engine().feed();
        if (!feed.covers(from.generation())) {
            throw HStoreException.invalid("ancestor generation " + from.generation() + " is neither retained nor covered by the change feed (first retained "
                    + feed.firstGeneration() + ")");
        }
        database.engine().transactions().awaitPublished(database.engine().transactions().current().id());
        Lineage lineage = Lineage.of(database, branch);
        Map<Key, Net> folded = new TreeMap<>(KEY_ORDER);
        try (var events = feed.replay(from.generation())) {
            events.peek(_ -> meter.consume(1))
                    .filter(event -> lineage.includes(event.branch(), event.generation()))
                    .forEach(event -> changes(event).forEach(net -> {
                        meter.consume(1);
                        folded.merge(net.key(), net, (first, next) -> new Net(first.key(), first.before(), next.after()));
                    }));
        }
        folded.values().removeIf(net -> net.before().equals(net.after()));
        return folded;
    }

    private void state(Snapshot head, Scope scope, BiConsumer<Key, Object> sink) {
        Map<Long, AtomValue> records = new HashMap<>();
        head.scan(EngineSlots.CATALOG).stream().forEach(entry -> {
            meter.consume(1);
            AtomValue value = AtomValue.of(entry.value());
            records.put(entry.key(), value);
        });
        records.forEach((atom, value) -> {
            if (inScope(scope, new Key.Atom(atom), records::get)) {
                sink.accept(new Key.Atom(atom), value);
            }
            if (value.edge()) {
                head.edge(atom).ifPresent(edge -> edge.members().stream().forEach(member -> {
                    meter.consume(1);
                    Key key = new Key.Member(atom, member.value().member());
                    if (inScope(scope, key, records::get)) {
                        sink.accept(key, member.value());
                    }
                }));
            }
        });
        head.scan(PropertySlots.PROPERTIES).stream().forEach(entry -> entry.value().entries().forEach((key, property) -> {
            meter.consume(1);
            Key found = new Key.Property(entry.key(), key);
            if (inScope(scope, found, records::get)) {
                sink.accept(found, property);
            }
        }));
    }
}

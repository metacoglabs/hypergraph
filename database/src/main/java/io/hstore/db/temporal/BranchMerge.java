package io.hstore.db.temporal;

import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Reader;
import io.hstore.db.property.PropertyBag.Property;
import io.hstore.db.property.PropertyBag;
import io.hstore.db.property.PropertySlots;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeDiff;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.TxnOptions;
import io.hstore.engine.txn.View;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

public final class BranchMerge {

    public enum Policy { FAIL, SOURCE, TARGET }

    public record Conflict(String kind, long atom, long detail) {
        @Override
        public String toString() {
            return kind + " @" + atom + (detail == 0 ? "" : "/" + detail);
        }
    }

    public record Outcome(int source, int target, long atomsCreated, long atomsDeleted, long memberChanges,
                          long propertyChanges, List<Conflict> conflicts) {
    }

    private record Side(List<Temporal.Delta> topology, Map<Long, Map<Integer, Optional<Property>>> properties) {

        Set<Long> deleted() {
            return topology.stream().filter(delta -> delta instanceof Temporal.Delta.AtomDeleted)
                    .map(Temporal.Delta::atom).collect(Collectors.toSet());
        }

        Map<Long, Set<Long>> members() {
            Map<Long, Set<Long>> touched = new HashMap<>();
            topology.forEach(delta -> {
                if (delta instanceof Temporal.Delta.MembersChanged(long edge, List<MemberChange> changes)) {
                    changes.forEach(change -> touched.computeIfAbsent(edge, _ -> new HashSet<>()).add(change.member()));
                }
            });
            return touched;
        }
    }

    private static final int REPORTED_CONFLICTS = 20;

    private BranchMerge() {
    }

    public static Outcome merge(HypergraphDatabase database, int source, int target, Policy policy) {
        Branch branch = database.engine().transactions().current().branch(source);
        if (source == target) {
            throw HStoreException.invalid("a branch cannot be merged into itself");
        }
        if (branch.parent() != target) {
            throw HStoreException.invalid("branch " + branch.name() + " forked from #" + branch.parent() + " and can only merge into it");
        }
        Side incoming;
        Side local;
        try (Snapshot base = database.engine().snapshotAt(branch.baseGeneration(), branch.parent());
             Snapshot head = database.engine().snapshot(source);
             Snapshot current = database.engine().snapshot(target)) {
            incoming = side(database.reader(base), database.reader(head), base, head);
            local = side(database.reader(base), database.reader(current), base, current);
        } catch (HStoreException.InvalidSchema missingBase) {
            throw HStoreException.invalid("cannot merge branch " + branch.name() + ": its fork generation "
                    + branch.baseGeneration() + " is no longer retained (" + missingBase.getMessage() + ")");
        }
        requireMergeable(branch, conflicts(incoming, local), policy);
        Outcome outcome = database.write(TxnOptions.defaults().onBranch(target), writer -> {
            try (Snapshot base = database.engine().snapshotAt(branch.baseGeneration(), branch.parent());
                 Snapshot head = database.engine().snapshot(source)) {
                Side changes = side(database.reader(base), database.reader(head), base, head);
                List<Conflict> conflicts = conflicts(changes, side(database.reader(base), writer, base, writer.view()));
                requireMergeable(branch, conflicts, policy);
                Set<Conflict> skipped = policy == Policy.TARGET ? new HashSet<>(conflicts) : Set.of();
                return apply(writer.transaction(), database.reader(head), changes, skipped, source, target, conflicts);
            }
        });
        database.engine().markMerged(source);
        return outcome;
    }

    private static void requireMergeable(Branch branch, List<Conflict> conflicts, Policy policy) {
        if (!conflicts.isEmpty() && policy == Policy.FAIL) {
            throw HStoreException.conflict("merging " + branch.name() + " conflicts on " + conflicts.size() + " keys: "
                    + conflicts.stream().limit(REPORTED_CONFLICTS).map(Conflict::toString).collect(Collectors.joining(", ")));
        }
    }

    private static Side side(Reader before, Reader after, View base, View head) {
        List<Temporal.Delta> topology = Temporal.diff(before, after).toList();
        Map<Long, Map<Integer, Optional<Property>>> properties = new HashMap<>();
        Tree<PropertyBag> left = base.scan(PropertySlots.PROPERTIES);
        Tree<PropertyBag> right = head.scan(PropertySlots.PROPERTIES);
        TreeDiff.diff(left, right).forEach(delta -> {
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
            TreeSet<Integer> keys = new TreeSet<>(was.entries().keySet());
            keys.addAll(now.entries().keySet());
            Map<Integer, Optional<Property>> changed = new HashMap<>();
            keys.stream().filter(key -> !was.get(key).equals(now.get(key))).forEach(key -> changed.put(key, now.get(key)));
            if (!changed.isEmpty()) {
                properties.put(delta.key(), changed);
            }
        });
        return new Side(topology, properties);
    }

    private static List<Conflict> conflicts(Side incoming, Side local) {
        List<Conflict> conflicts = new ArrayList<>();
        Set<Long> localDeleted = local.deleted();
        Map<Long, Set<Long>> localMembers = local.members();
        for (Temporal.Delta delta : incoming.topology()) {
            switch (delta) {
                case Temporal.Delta.AtomDeleted(long atom, var _) -> {
                    if (localMembers.containsKey(atom) || local.properties().containsKey(atom)) {
                        conflicts.add(new Conflict("deleted-while-modified", atom, 0));
                    }
                }
                case Temporal.Delta.MembersChanged(long edge, List<MemberChange> changes) -> {
                    if (localDeleted.contains(edge)) {
                        conflicts.add(new Conflict("modified-while-deleted", edge, 0));
                    }
                    Set<Long> touched = localMembers.getOrDefault(edge, Set.of());
                    changes.stream().filter(change -> touched.contains(change.member()))
                            .forEach(change -> conflicts.add(new Conflict("membership", edge, change.member())));
                }
                case Temporal.Delta.AtomCreated _ -> {
                }
            }
        }
        incoming.properties().forEach((owner, keys) -> {
            Map<Integer, Optional<Property>> theirs = local.properties().getOrDefault(owner, Map.of());
            keys.forEach((key, value) -> {
                if (theirs.containsKey(key) && !theirs.get(key).equals(value)) {
                    conflicts.add(new Conflict("property", owner, key));
                }
            });
            if (localDeleted.contains(owner)) {
                conflicts.add(new Conflict("modified-while-deleted", owner, 0));
            }
        });
        return conflicts;
    }

    private static Outcome apply(Transaction txn, Reader source, Side incoming, Set<Conflict> skipped,
                                 int sourceId, int targetId, List<Conflict> conflicts) {
        long created = 0;
        long deleted = 0;
        long members = 0;
        for (Temporal.Delta delta : incoming.topology()) {
            switch (delta) {
                case Temporal.Delta.AtomCreated(long atom, var record) -> {
                    if (!txn.exists(atom)) {
                        txn.adopt(atom, record);
                        created++;
                    }
                }
                case Temporal.Delta.AtomDeleted(long atom, var _) -> {
                    if (txn.exists(atom) && !skipped.contains(new Conflict("deleted-while-modified", atom, 0))) {
                        txn.delete(PropertySlots.PROPERTIES, atom);
                        txn.delete(atom);
                        deleted++;
                    }
                }
                case Temporal.Delta.MembersChanged(long edge, List<MemberChange> changes) -> {
                    if (!txn.exists(edge)) {
                        continue;
                    }
                    if (source.edge(edge).kind() == EdgeKind.ORDERED) {
                        boolean contested = changes.stream().anyMatch(change -> skipped.contains(new Conflict("membership", edge, change.member())));
                        if (!contested) {
                            txn.load(edge, source.edge(edge).stream().toList());
                            members += changes.size();
                        }
                        continue;
                    }
                    for (MemberChange change : changes) {
                        if (skipped.contains(new Conflict("membership", edge, change.member()))) {
                            continue;
                        }
                        switch (change) {
                            case MemberChange.Added(long _, Incidence incidence, long _) -> txn.upsert(edge, incidence.member(), _ -> incidence);
                            case MemberChange.Updated(long _, Incidence _, Incidence now, long _, long _) -> txn.upsert(edge, now.member(), _ -> now);
                            case MemberChange.Removed(long _, Incidence incidence, long _) -> txn.remove(edge, incidence.member());
                        }
                        members++;
                    }
                }
            }
        }
        long properties = 0;
        for (Map.Entry<Long, Map<Integer, Optional<Property>>> owner : incoming.properties().entrySet()) {
            if (!txn.exists(owner.getKey())) {
                continue;
            }
            Map<Integer, Optional<Property>> accepted = new HashMap<>(owner.getValue());
            accepted.keySet().removeIf(key -> skipped.contains(new Conflict("property", owner.getKey(), key)));
            if (accepted.isEmpty()) {
                continue;
            }
            txn.update(PropertySlots.PROPERTIES, owner.getKey(), current -> {
                PropertyBag bag = current.orElse(PropertyBag.EMPTY);
                for (Map.Entry<Integer, Optional<Property>> change : accepted.entrySet()) {
                    bag = change.getValue().isPresent() ? bag.with(change.getKey(), change.getValue().get()) : bag.without(change.getKey());
                }
                return bag.isEmpty() ? Optional.empty() : Optional.of(bag);
            });
            properties += accepted.size();
        }
        return new Outcome(sourceId, targetId, created, deleted, members, properties, conflicts);
    }
}

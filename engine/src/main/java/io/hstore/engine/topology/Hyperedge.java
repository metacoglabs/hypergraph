// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.topology;

import io.hstore.engine.HStoreException;
import io.hstore.engine.tree.BulkBuilder;
import io.hstore.engine.tree.Entry;
import io.hstore.engine.tree.NodeSource;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.Summary;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeDiff;
import io.hstore.engine.tree.WriteScope;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public record Hyperedge(long id, EdgeKind kind, Tree<Incidence> members, Tree<Long> order) {

    static final long LABEL_FLOOR = 0;
    static final long LABEL_CEILING = 1L << 62;
    static final long APPEND_STEP = 1L << 24;
    static final long MIN_SPACING = 1L << 8;

    public static Hyperedge of(long id, EdgeKind kind, NodeSource source, Ref members, Ref order) {
        return new Hyperedge(id, kind, new Tree<>(TopologySchemas.members(kind), source, members),
                new Tree<>(TopologySchemas.ORDER_INDEX, source, order));
    }

    public static Hyperedge empty(long id, EdgeKind kind, NodeSource source) {
        return of(id, kind, source, Ref.EMPTY, Ref.EMPTY);
    }

    public static Hyperedge build(long id, EdgeKind kind, NodeSource source, WriteScope scope, List<Incidence> incidences) {
        BinaryOperator<Incidence> rejectDuplicate = (first, _) -> {
            throw HStoreException.invalid("atom " + first.member() + " appears twice in bulk load of hyperedge " + id);
        };
        BulkBuilder<Long> index = Tree.empty(TopologySchemas.ORDER_INDEX, source).builder(scope);
        if (kind == EdgeKind.SET) {
            BulkBuilder<Incidence> members = Tree.empty(TopologySchemas.SET_MEMBERS, source).builder(scope).mergingWith(rejectDuplicate);
            incidences.stream().sorted(Comparator.comparingLong(Incidence::member)).forEach(incidence -> members.add(incidence.member(), incidence));
            return new Hyperedge(id, kind, members.build(), index.build());
        }
        long spacing = Math.min(APPEND_STEP, (LABEL_CEILING - LABEL_FLOOR) / (incidences.size() + 1L));
        BulkBuilder<Incidence> members = Tree.empty(TopologySchemas.ORDERED_MEMBERS, source).builder(scope);
        long[][] locators = new long[incidences.size()][];
        for (int i = 0; i < incidences.size(); i++) {
            long label = LABEL_FLOOR + (i + 1L) * spacing;
            members.add(label, incidences.get(i));
            locators[i] = new long[]{incidences.get(i).member(), label};
        }
        Arrays.sort(locators, Comparator.comparingLong(pair -> pair[0]));
        index.mergingWith((first, _) -> {
            throw HStoreException.invalid("an atom appears twice in bulk load of ordered hyperedge " + id + " at label " + first);
        });
        for (long[] pair : locators) {
            index.add(pair[0], pair[1]);
        }
        return new Hyperedge(id, kind, members.build(), index.build());
    }

    public long size() {
        return members.size();
    }

    public Summary summary() {
        return members.summary();
    }

    public Tree<?> membership() {
        return kind == EdgeKind.SET ? members : order;
    }

    public boolean contains(long member) {
        return membership().contains(member);
    }

    public Optional<Incidence> get(long member) {
        return switch (kind) {
            case SET -> members.get(member);
            case ORDERED -> order.get(member).flatMap(members::get);
        };
    }

    public OptionalLong locator(long member) {
        return switch (kind) {
            case SET -> members.contains(member) ? OptionalLong.of(member) : OptionalLong.empty();
            case ORDERED -> order.get(member).map(OptionalLong::of).orElse(OptionalLong.empty());
        };
    }

    public Incidence at(long index) {
        return members.at(index).value();
    }

    public long indexOf(long member) {
        return switch (kind) {
            case SET -> Math.max(-1, members.rankOf(member));
            case ORDERED -> order.get(member).map(members::rankOf).orElse(-1L);
        };
    }

    public long positionOfLocator(long locator) {
        return members.rankOf(locator);
    }

    public Stream<Incidence> stream() {
        return members.values();
    }

    public LongStream memberIds() {
        return switch (kind) {
            case SET -> members.keys();
            case ORDERED -> members.values().mapToLong(Incidence::member);
        };
    }

    public Stream<Incidence> validAt(long instant) {
        return members.scan(summary -> summary.mayBeValidAt(instant))
                .map(Entry::value)
                .filter(incidence -> incidence.validAt(instant));
    }

    public Stream<Incidence> withRoleSets(Set<Integer> roleSets) {
        long mask = roleSets.stream().mapToLong(Incidence::roleBit).reduce(0, (a, b) -> a | b);
        return members.scan(summary -> summary.mayHaveRoles(mask))
                .map(Entry::value)
                .filter(incidence -> roleSets.contains(incidence.roleSet()));
    }

    public Stream<Incidence> weightedBetween(long low, long high) {
        return members.scan(summary -> summary.mayHaveWeightIn(low, high))
                .map(Entry::value)
                .filter(incidence -> incidence.weight() >= low && incidence.weight() <= high);
    }

    public Stream<Incidence> overlapping(long from, long to) {
        return members.scan(summary -> summary.mayOverlapInterval(from, to))
                .map(Entry::value)
                .filter(incidence -> incidence.validity().overlaps(from, to));
    }

    public Hyperedge insert(WriteScope scope, Incidence incidence, Consumer<MemberChange> sink) {
        return switch (kind) {
            case SET -> {
                requireAbsent(incidence.member());
                sink.accept(new MemberChange.Added(id, incidence, incidence.member()));
                yield withMembers(members.put(scope, incidence.member(), incidence));
            }
            case ORDERED -> insertAt(scope, size(), incidence, sink);
        };
    }

    public Hyperedge upsert(WriteScope scope, long member, UnaryOperator<Incidence> patch, Consumer<MemberChange> sink) {
        Optional<Incidence> before = get(member);
        Incidence after = patch.apply(before.orElseGet(() -> Incidence.of(member)));
        if (after.member() != member) {
            throw HStoreException.invalid("an incidence patch may not change the member atom");
        }
        if (before.isEmpty()) {
            return insert(scope, after, sink);
        }
        if (before.get().equals(after)) {
            return this;
        }
        long locator = locator(member).orElseThrow();
        sink.accept(new MemberChange.Updated(id, before.get(), after, locator, locator));
        return withMembers(members.put(scope, locator, after));
    }

    public Hyperedge replace(WriteScope scope, Incidence incidence, Consumer<MemberChange> sink) {
        if (!contains(incidence.member())) {
            throw HStoreException.invalid("member " + incidence.member() + " is not part of hyperedge " + id);
        }
        return upsert(scope, incidence.member(), _ -> incidence, sink);
    }

    public Hyperedge remove(WriteScope scope, long member, Consumer<MemberChange> sink) {
        OptionalLong locator = locator(member);
        if (locator.isEmpty()) {
            return this;
        }
        Incidence before = members.get(locator.getAsLong()).orElseThrow();
        sink.accept(new MemberChange.Removed(id, before, locator.getAsLong()));
        Hyperedge removed = withMembers(members.remove(scope, locator.getAsLong()));
        return kind == EdgeKind.ORDERED ? removed.withOrder(order.remove(scope, member)) : removed;
    }

    public Hyperedge insertAt(WriteScope scope, long index, Incidence incidence, Consumer<MemberChange> sink) {
        requireOrdered();
        Objects.checkIndex(index, size() + 1);
        requireAbsent(incidence.member());
        Hyperedge edge = this;
        OptionalLong label = between(index);
        if (label.isEmpty()) {
            Relabeled relabeled = relabel(scope, index, sink);
            edge = relabeled.edge();
            label = OptionalLong.of(relabeled.label());
        }
        long assigned = label.getAsLong();
        sink.accept(new MemberChange.Added(id, incidence, assigned));
        return new Hyperedge(id, kind, edge.members.put(scope, assigned, incidence), edge.order.put(scope, incidence.member(), assigned));
    }

    public Hyperedge removeAt(WriteScope scope, long index, Consumer<MemberChange> sink) {
        requireOrdered();
        return remove(scope, at(index).member(), sink);
    }

    public Hyperedge updateAt(WriteScope scope, long index, UnaryOperator<Incidence> patch, Consumer<MemberChange> sink) {
        requireOrdered();
        return upsert(scope, at(index).member(), patch, sink);
    }

    public static void diff(Hyperedge before, Hyperedge after, Consumer<MemberChange> sink) {
        long id = after.id();
        if (after.kind() == EdgeKind.SET) {
            TreeDiff.diff(before.members(), after.members()).forEach(delta -> sink.accept(switch (delta) {
                case TreeDiff.Delta.Added<Incidence>(long key, Incidence value) -> new MemberChange.Added(id, value, key);
                case TreeDiff.Delta.Removed<Incidence>(long key, Incidence value) -> new MemberChange.Removed(id, value, key);
                case TreeDiff.Delta.Changed<Incidence>(long key, Incidence was, Incidence now) -> new MemberChange.Updated(id, was, now, key, key);
            }));
            return;
        }
        Set<Long> relocated = new HashSet<>();
        TreeDiff.diff(before.order(), after.order()).forEach(delta -> {
            relocated.add(delta.key());
            sink.accept(switch (delta) {
                case TreeDiff.Delta.Added<Long>(long _, Long label) -> new MemberChange.Added(id, after.members().get(label).orElseThrow(), label);
                case TreeDiff.Delta.Removed<Long>(long _, Long label) -> new MemberChange.Removed(id, before.members().get(label).orElseThrow(), label);
                case TreeDiff.Delta.Changed<Long>(long _, Long was, Long now) -> new MemberChange.Updated(id,
                        before.members().get(was).orElseThrow(), after.members().get(now).orElseThrow(), was, now);
            });
        });
        TreeDiff.diff(before.members(), after.members()).forEach(delta -> {
            if (delta instanceof TreeDiff.Delta.Changed<Incidence>(long label, Incidence was, Incidence now)
                    && was.member() == now.member() && !relocated.contains(now.member())) {
                sink.accept(new MemberChange.Updated(id, was, now, label, label));
            }
        });
    }

    public Hyperedge withRoots(Ref newMembers, Ref newOrder) {
        return new Hyperedge(id, kind, members.withRoot(newMembers), order.withRoot(newOrder));
    }

    private Hyperedge withMembers(Tree<Incidence> newMembers) {
        return new Hyperedge(id, kind, newMembers, order);
    }

    private Hyperedge withOrder(Tree<Long> newOrder) {
        return new Hyperedge(id, kind, members, newOrder);
    }

    private void requireAbsent(long member) {
        if (contains(member)) {
            throw HStoreException.invalid("atom " + member + " already participates in hyperedge " + id);
        }
    }

    private void requireOrdered() {
        if (kind != EdgeKind.ORDERED) {
            throw HStoreException.invalid("positional operations require an ORDERED hyperedge, " + id + " is " + kind);
        }
    }

    private OptionalLong between(long index) {
        long count = size();
        long previous = index == 0 ? LABEL_FLOOR : members.at(index - 1).key();
        long next = index == count ? LABEL_CEILING : members.at(index).key();
        long gap = next - previous;
        if (gap < 2) {
            return OptionalLong.empty();
        }
        if (index == count && gap > APPEND_STEP) {
            return OptionalLong.of(previous + APPEND_STEP);
        }
        if (index == 0 && gap > APPEND_STEP) {
            return OptionalLong.of(next - APPEND_STEP);
        }
        return OptionalLong.of(previous + gap / 2);
    }

    private record Relabeled(Hyperedge edge, long label) {
    }

    private Relabeled relabel(WriteScope scope, long index, Consumer<MemberChange> sink) {
        long count = size();
        for (long width = 8; ; width *= 2) {
            long high = Math.min(count, Math.max(0, index - width / 2) + width);
            long low = Math.max(0, high - width);
            long left = low == 0 ? LABEL_FLOOR : members.at(low - 1).key();
            long right = high == count ? LABEL_CEILING : members.at(high).key();
            long spacing = (right - left) / (high - low + 2);
            boolean whole = low == 0 && high == count;
            if (spacing >= MIN_SPACING || whole) {
                if (spacing < 1) {
                    throw HStoreException.limit("label space of ordered hyperedge " + id + " is exhausted");
                }
                return spread(scope, low, high, index, left, spacing, sink);
            }
        }
    }

    private Relabeled spread(WriteScope scope, long low, long high, long index, long left, long spacing, Consumer<MemberChange> sink) {
        List<Entry<Incidence>> window = high > low
                ? members.range(members.at(low).key(), members.at(high - 1).key()).toList()
                : List.of();
        Tree<Incidence> relabeledMembers = members;
        for (Entry<Incidence> entry : window) {
            relabeledMembers = relabeledMembers.remove(scope, entry.key());
        }
        Tree<Long> relabeledOrder = order;
        long assigned = -1;
        int next = 0;
        for (long position = low; position <= high; position++) {
            long label = left + (position - low + 1) * spacing;
            if (position == index) {
                assigned = label;
                continue;
            }
            Entry<Incidence> entry = window.get(next++);
            relabeledMembers = relabeledMembers.put(scope, label, entry.value());
            relabeledOrder = relabeledOrder.put(scope, entry.value().member(), label);
            sink.accept(new MemberChange.Updated(id, entry.value(), entry.value(), entry.key(), label));
        }
        return new Relabeled(new Hyperedge(id, kind, relabeledMembers, relabeledOrder), assigned);
    }
}

// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

public final class TreeDiff {

    public sealed interface Delta<V> {
        long key();

        record Added<V>(long key, V value) implements Delta<V> {
        }

        record Removed<V>(long key, V value) implements Delta<V> {
        }

        record Changed<V>(long key, V before, V after) implements Delta<V> {
        }
    }

    private TreeDiff() {
    }

    public static <V> Stream<Delta<V>> diff(Tree<V> before, Tree<V> after) {
        return diff(before, after, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public static <V> Stream<Delta<V>> diff(Tree<V> before, Tree<V> after, long low, long high) {
        return Tree.stream(new Walker<>(before, after, low, high)).map(Entry::value);
    }

    public static <V> boolean sameContent(Tree<V> a, Tree<V> b) {
        return sameContent(a, b, Long.MIN_VALUE, Long.MAX_VALUE);
    }

    public static <V> boolean sameContent(Tree<V> a, Tree<V> b, long low, long high) {
        return Ref.same(a.root(), b.root()) || diff(a, b, low, high).allMatch(delta ->
                delta instanceof Delta.Changed<V>(long _, V before, V after) && sameValue(a.schema(), a.source(), before, after));
    }

    public static <V> boolean sameValue(TreeSchema<V> schema, NodeSource source, V a, V b) {
        if (Objects.equals(a, b)) {
            return true;
        }
        ValueCodec<V> codec = schema.codec();
        if (!codec.holdsRefs() || !Objects.equals(codec.mapRefs(a, (_, _) -> Ref.EMPTY), codec.mapRefs(b, (_, _) -> Ref.EMPTY))) {
            return false;
        }
        List<Tree<?>> left = nested(source, codec, a);
        List<Tree<?>> right = nested(source, codec, b);
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).schema().equals(right.get(i).schema()) || !sameNested(left.get(i), right.get(i), Long.MIN_VALUE, Long.MAX_VALUE)) {
                return false;
            }
        }
        return true;
    }

    public static <V> List<Tree<?>> nested(NodeSource source, ValueCodec<V> codec, V value) {
        List<Tree<?>> trees = new ArrayList<>();
        codec.forEachRef(value, (ref, schema) -> trees.add(new Tree<>(schema, source, ref)));
        return trees;
    }

    @SuppressWarnings("unchecked")
    public static <V> boolean sameNested(Tree<V> a, Tree<?> b, long low, long high) {
        return sameContent(a, (Tree<V>) b, low, high);
    }

    public static <V> Optional<String> difference(Tree<V> a, Tree<V> b) {
        if (!a.summary().equals(b.summary())) {
            return Optional.of("summaries differ: " + a.summary() + " and " + b.summary());
        }
        Iterator<Entry<V>> left = a.stream().iterator();
        Iterator<Entry<V>> right = b.stream().iterator();
        while (left.hasNext() && right.hasNext()) {
            Entry<V> x = left.next();
            Entry<V> y = right.next();
            if (x.key() != y.key()) {
                return Optional.of("keys differ: " + x.key() + " and " + y.key());
            }
            Optional<String> value = valueDifference(a, b, x.value(), y.value());
            if (value.isPresent()) {
                return Optional.of("key " + x.key() + ": " + value.get());
            }
        }
        if (left.hasNext() || right.hasNext()) {
            return Optional.of("one tree has more entries");
        }
        return Optional.empty();
    }

    private static <V> Optional<String> valueDifference(Tree<V> a, Tree<V> b, V x, V y) {
        ValueCodec<V> codec = a.schema().codec();
        if (!codec.holdsRefs()) {
            return Objects.equals(x, y) ? Optional.empty() : Optional.of(x + " and " + y);
        }
        if (!Objects.equals(codec.mapRefs(x, (_, _) -> Ref.EMPTY), codec.mapRefs(y, (_, _) -> Ref.EMPTY))) {
            return Optional.of(x + " and " + y);
        }
        List<Tree<?>> nestedX = new ArrayList<>();
        List<Tree<?>> nestedY = new ArrayList<>();
        codec.forEachRef(x, (ref, schema) -> nestedX.add(new Tree<>(schema, a.source(), ref)));
        codec.forEachRef(y, (ref, schema) -> nestedY.add(new Tree<>(schema, b.source(), ref)));
        for (int i = 0; i < nestedX.size(); i++) {
            Optional<String> nested = nestedDifference(nestedX.get(i), nestedY.get(i));
            if (nested.isPresent()) {
                return Optional.of("nested tree " + i + ", " + nested.get());
            }
        }
        return Optional.empty();
    }

    @SuppressWarnings("unchecked")
    private static <V> Optional<String> nestedDifference(Tree<V> x, Tree<?> y) {
        return difference(x, (Tree<V>) y);
    }

    private sealed interface Item {
        record Subtree(Ref ref) implements Item {
        }

        final class Run implements Item {
            private final Leaf leaf;
            private int index;

            Run(Leaf leaf) {
                this.leaf = leaf;
            }

            long key() {
                return leaf.key(index);
            }

            Object value() {
                return leaf.value(index);
            }
        }
    }

    private static final class Walker<V> implements Iterator<Entry<Delta<V>>> {
        private final Tree<V> before;
        private final Tree<V> after;
        private final long low;
        private final long high;
        private final boolean bounded;
        private final Deque<Item> left = new ArrayDeque<>();
        private final Deque<Item> right = new ArrayDeque<>();
        private Delta<V> pending;

        Walker(Tree<V> before, Tree<V> after, long low, long high) {
            this.before = before;
            this.after = after;
            this.low = low;
            this.high = high;
            this.bounded = low != Long.MIN_VALUE || high != Long.MAX_VALUE;
            if (!before.isEmpty()) {
                left.push(new Item.Subtree(before.root()));
            }
            if (!after.isEmpty()) {
                right.push(new Item.Subtree(after.root()));
            }
        }

        @Override
        public boolean hasNext() {
            while (pending == null && (!left.isEmpty() || !right.isEmpty())) {
                pending = step();
            }
            return pending != null;
        }

        @Override
        public Entry<Delta<V>> next() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            Delta<V> delta = pending;
            pending = null;
            return new Entry<>(delta.key(), delta);
        }

        private Delta<V> step() {
            Item a = left.peek();
            Item b = right.peek();
            if (bounded && a != null && outside(a)) {
                skip(left, a);
                return null;
            }
            if (bounded && b != null && outside(b)) {
                skip(right, b);
                return null;
            }
            if (a == null) {
                return drain(right, after, false);
            }
            if (b == null) {
                return drain(left, before, true);
            }
            return switch (a) {
                case Item.Subtree(Ref ra) when b instanceof Item.Subtree(Ref rb) -> compareSubtrees(ra, rb);
                case Item.Subtree(Ref ra) when b instanceof Item.Run run -> {
                    if (ra.summary().min() > run.key()) {
                        yield new Delta.Added<>(run.key(), after.schema().cast(take(right, run)));
                    }
                    expand(left, before);
                    yield null;
                }
                case Item.Run run when b instanceof Item.Subtree(Ref rb) -> {
                    if (rb.summary().min() > run.key()) {
                        yield new Delta.Removed<>(run.key(), before.schema().cast(take(left, run)));
                    }
                    expand(right, after);
                    yield null;
                }
                case Item.Run ra when b instanceof Item.Run rb -> compareElements(ra, rb);
                default -> throw new IllegalStateException();
            };
        }

        private Delta<V> compareSubtrees(Ref ra, Ref rb) {
            if (Ref.same(ra, rb)) {
                left.pop();
                right.pop();
                return null;
            }
            Summary sa = ra.summary();
            Summary sb = rb.summary();
            if (sa.max() < sb.min()) {
                expand(left, before);
            } else if (sb.max() < sa.min()) {
                expand(right, after);
            } else if (sa.count() > sb.count()) {
                expand(left, before);
            } else if (sb.count() > sa.count()) {
                expand(right, after);
            } else {
                expand(left, before);
                expand(right, after);
            }
            return null;
        }

        private Delta<V> compareElements(Item.Run ra, Item.Run rb) {
            long ka = ra.key();
            long kb = rb.key();
            if (ka < kb) {
                return new Delta.Removed<>(ka, before.schema().cast(take(left, ra)));
            }
            if (kb < ka) {
                return new Delta.Added<>(kb, after.schema().cast(take(right, rb)));
            }
            Object va = take(left, ra);
            Object vb = take(right, rb);
            return Objects.equals(va, vb) ? null : new Delta.Changed<>(ka, before.schema().cast(va), after.schema().cast(vb));
        }

        private Delta<V> drain(Deque<Item> side, Tree<V> tree, boolean removed) {
            while (side.peek() instanceof Item.Subtree) {
                expand(side, tree);
                if (bounded) {
                    return null;
                }
            }
            if (!(side.peek() instanceof Item.Run run)) {
                return null;
            }
            long key = run.key();
            V value = tree.schema().cast(take(side, run));
            return removed ? new Delta.Removed<>(key, value) : new Delta.Added<>(key, value);
        }

        private boolean outside(Item item) {
            return switch (item) {
                case Item.Subtree(Ref ref) -> !ref.summary().overlaps(low, high);
                case Item.Run run -> run.key() < low || run.key() > high;
            };
        }

        private static void skip(Deque<Item> side, Item item) {
            switch (item) {
                case Item.Subtree _ -> side.pop();
                case Item.Run run -> take(side, run);
            }
        }

        private static Object take(Deque<Item> side, Item.Run run) {
            Object value = run.value();
            if (++run.index == run.leaf.size()) {
                side.pop();
            }
            return value;
        }

        private void expand(Deque<Item> side, Tree<V> tree) {
            Item.Subtree subtree = (Item.Subtree) side.pop();
            switch (tree.node(subtree.ref())) {
                case Leaf leaf -> {
                    if (leaf.size() > 0) {
                        side.push(new Item.Run(leaf));
                    }
                }
                case Branch branch -> {
                    for (int i = branch.size() - 1; i >= 0; i--) {
                        side.push(new Item.Subtree(branch.child(i)));
                    }
                }
            }
        }
    }
}

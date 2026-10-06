// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import java.util.Comparator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PrimitiveIterator;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.BinaryOperator;
import java.util.stream.LongStream;
import java.util.stream.StreamSupport;

public final class TreeAlgebra {

    public enum Strategy { SYNCHRONIZED, PROBE, AUTO }

    private static final int ESTIMATED_ENTRIES_PER_LEAF = 128;

    private TreeAlgebra() {
    }

    public static long countIntersect(Tree<?> a, Tree<?> b) {
        return countIntersect(a, b, Strategy.AUTO);
    }

    public static long countIntersect(Tree<?> a, Tree<?> b, Strategy strategy) {
        if (a.isEmpty() || b.isEmpty() || a.summary().disjointFrom(b.summary())) {
            return 0;
        }
        return switch (resolve(strategy, a, b)) {
            case PROBE -> probeCount(a.size() <= b.size() ? a : b, a.size() <= b.size() ? b : a);
            case SYNCHRONIZED, AUTO -> synchronizedCount(a, a.root(), b, b.root());
        };
    }

    public static Strategy resolve(Strategy requested, Tree<?> a, Tree<?> b) {
        if (requested != Strategy.AUTO) {
            return requested;
        }
        long small = Math.min(a.size(), b.size());
        long large = Math.max(a.size(), b.size());
        double synchronizedCost = pages(a.size()) + pages(b.size());
        double probeCost = pages(small) + small * Math.max(1, Math.log(large) / Math.log(ESTIMATED_ENTRIES_PER_LEAF));
        return probeCost < synchronizedCost ? Strategy.PROBE : Strategy.SYNCHRONIZED;
    }

    private static double pages(long entries) {
        return Math.ceil((double) entries / ESTIMATED_ENTRIES_PER_LEAF);
    }

    private static long probeCount(Tree<?> small, Tree<?> large) {
        TreeCursor<?> probe = large.cursor();
        return small.keys().filter(key -> probe.seek(key) && probe.key() == key).count();
    }

    private static long synchronizedCount(Tree<?> a, Ref ra, Tree<?> b, Ref rb) {
        Summary sa = ra.summary();
        Summary sb = rb.summary();
        if (sa.disjointFrom(sb)) {
            return 0;
        }
        if (Ref.same(ra, rb)) {
            return sa.count();
        }
        Node na = a.node(ra);
        Node nb = b.node(rb);
        if (na instanceof Leaf la && nb instanceof Leaf lb) {
            return mergeCount(la, lb);
        }
        long total = 0;
        if (na instanceof Branch ba && (na.height() >= nb.height() || !(nb instanceof Branch))) {
            for (int i = 0; i < ba.size(); i++) {
                total += synchronizedCount(a, ba.child(i), b, rb);
            }
        } else {
            Branch bb = (Branch) nb;
            for (int i = 0; i < bb.size(); i++) {
                total += synchronizedCount(a, ra, b, bb.child(i));
            }
        }
        return total;
    }

    private static long mergeCount(Leaf a, Leaf b) {
        long count = 0;
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            long x = a.key(i);
            long y = b.key(j);
            if (x == y) {
                count++;
                i++;
                j++;
            } else if (x < y) {
                i++;
            } else {
                j++;
            }
        }
        return count;
    }

    public static LongStream intersectKeys(List<? extends Tree<?>> trees) {
        if (trees.isEmpty() || trees.stream().anyMatch(Tree::isEmpty)) {
            return LongStream.empty();
        }
        long low = trees.stream().mapToLong(t -> t.summary().min()).max().orElseThrow();
        long high = trees.stream().mapToLong(t -> t.summary().max()).min().orElseThrow();
        if (low > high) {
            return LongStream.empty();
        }
        List<TreeCursor<?>> cursors = trees.stream()
                .sorted(Comparator.comparingLong(Tree::size))
                .<TreeCursor<?>>map(t -> t.cursor(summary -> summary.overlaps(low, high), false))
                .toList();
        PrimitiveIterator.OfLong leapfrog = new Leapfrog(cursors, low, high);
        return StreamSupport.longStream(Spliterators.spliteratorUnknownSize(leapfrog,
                Spliterator.ORDERED | Spliterator.DISTINCT | Spliterator.SORTED | Spliterator.NONNULL), false);
    }

    public static LongStream intersectKeys(Tree<?> a, Tree<?> b) {
        return intersectKeys(List.of(a, b));
    }

    public static <V> Tree<V> intersection(Tree<V> a, Tree<?> b, WriteScope scope) {
        BulkBuilder<V> builder = a.builder(scope);
        TreeCursor<V> values = a.cursor();
        intersectKeys(a, b).forEach(key -> {
            values.seek(key);
            builder.add(key, values.value());
        });
        return builder.build();
    }

    public static <V> Tree<V> union(Tree<V> a, Tree<V> b, WriteScope scope, BinaryOperator<V> onBoth) {
        if (b.isEmpty() || Ref.same(a.root(), b.root())) {
            return a;
        }
        if (a.isEmpty()) {
            return b;
        }
        Summary sa = a.summary();
        Summary sb = b.summary();
        if (sa.max() < sb.min()) {
            return a.join(scope, b);
        }
        if (sb.max() < sa.min()) {
            return b.join(scope, a);
        }
        if (equal(a, b)) {
            return a;
        }
        BulkBuilder<V> builder = a.builder(scope);
        TreeCursor<V> left = a.cursor();
        TreeCursor<V> right = b.cursor();
        while (left.hasNext() || right.hasNext()) {
            if (!right.hasNext() || (left.hasNext() && left.key() < right.key())) {
                builder.add(left.key(), left.value());
                left.advance();
            } else if (!left.hasNext() || right.key() < left.key()) {
                builder.add(right.key(), right.value());
                right.advance();
            } else {
                builder.add(left.key(), onBoth.apply(left.value(), right.value()));
                left.advance();
                right.advance();
            }
        }
        return builder.build();
    }

    public static <V> Tree<V> difference(Tree<V> a, Tree<?> b, WriteScope scope) {
        if (a.isEmpty() || b.isEmpty() || a.summary().disjointFrom(b.summary())) {
            return a;
        }
        if (Ref.same(a.root(), b.root())) {
            return a.withRoot(Ref.EMPTY);
        }
        BulkBuilder<V> builder = a.builder(scope);
        TreeCursor<?> probe = b.cursor();
        a.stream()
                .filter(entry -> !(probe.seek(entry.key()) && probe.key() == entry.key()))
                .forEach(entry -> builder.add(entry.key(), entry.value()));
        return builder.build();
    }

    public static boolean subset(Tree<?> a, Tree<?> b) {
        if (a.isEmpty()) {
            return true;
        }
        if (a.size() > b.size() || b.isEmpty()) {
            return false;
        }
        Summary sa = a.summary();
        Summary sb = b.summary();
        if (sa.max() < sb.min() || sa.min() > sb.max() || sa.min() < sb.min() || sa.max() > sb.max()) {
            return false;
        }
        return countIntersect(a, b) == a.size();
    }

    public static <V> boolean equal(Tree<V> a, Tree<V> b) {
        if (Ref.same(a.root(), b.root())) {
            return true;
        }
        Summary sa = a.summary();
        Summary sb = b.summary();
        if (sa.count() != sb.count() || sa.fingerprint() != sb.fingerprint() || sa.min() != sb.min() || sa.max() != sb.max()) {
            return false;
        }
        return TreeDiff.diff(a, b).findAny().isEmpty();
    }

    public static boolean sameKeys(Tree<?> a, Tree<?> b) {
        return a.size() == b.size() && countIntersect(a, b) == a.size();
    }

    public static double jaccard(Tree<?> a, Tree<?> b) {
        long intersection = countIntersect(a, b);
        long union = a.size() + b.size() - intersection;
        return union == 0 ? 1.0 : (double) intersection / union;
    }

    public static double containment(Tree<?> a, Tree<?> b) {
        long smaller = Math.min(a.size(), b.size());
        return smaller == 0 ? 0.0 : (double) countIntersect(a, b) / smaller;
    }

    private static final class Leapfrog implements PrimitiveIterator.OfLong {
        private final List<TreeCursor<?>> cursors;
        private final long high;
        private boolean ready;
        private boolean done;
        private long current;
        private long target;

        Leapfrog(List<TreeCursor<?>> cursors, long low, long high) {
            this.cursors = cursors;
            this.high = high;
            this.target = low;
        }

        @Override
        public boolean hasNext() {
            if (!ready && !done) {
                search();
            }
            return ready;
        }

        @Override
        public long nextLong() {
            if (!hasNext()) {
                throw new NoSuchElementException();
            }
            ready = false;
            if (current == Long.MAX_VALUE) {
                done = true;
            } else {
                target = current + 1;
            }
            return current;
        }

        private void search() {
            int agreeing = 0;
            int index = 0;
            long candidate = target;
            while (true) {
                TreeCursor<?> cursor = cursors.get(index);
                if (!cursor.seek(candidate) || cursor.key() > high) {
                    done = true;
                    return;
                }
                long key = cursor.key();
                if (key == candidate) {
                    agreeing++;
                } else {
                    candidate = key;
                    agreeing = 1;
                }
                if (agreeing == cursors.size()) {
                    current = candidate;
                    ready = true;
                    return;
                }
                index = (index + 1) % cursors.size();
            }
        }
    }
}

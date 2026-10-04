package io.hstore.engine.tree;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public record Tree<V>(TreeSchema<V> schema, NodeSource source, Ref root) {

    public Tree {
        Objects.requireNonNull(schema);
        Objects.requireNonNull(source);
        Objects.requireNonNull(root);
    }

    public static <V> Tree<V> empty(TreeSchema<V> schema, NodeSource source) {
        return new Tree<>(schema, source, Ref.EMPTY);
    }

    public Tree<V> withRoot(Ref newRoot) {
        return newRoot == root ? this : new Tree<>(schema, source, newRoot);
    }

    public long size() {
        return root.count();
    }

    public boolean isEmpty() {
        return root instanceof Ref.Empty;
    }

    public Summary summary() {
        return root.summary();
    }

    public int height() {
        return isEmpty() ? 0 : node(root).height() + 1;
    }

    public Optional<V> get(long key) {
        if (isEmpty()) {
            return Optional.empty();
        }
        Node node = node(root);
        while (node instanceof Branch branch) {
            Ref child = branch.child(branch.route(key));
            if (child instanceof Ref.Stored(long _, int _, Summary summary) && !summary.overlaps(key, key)) {
                return Optional.empty();
            }
            node = node(child);
        }
        Leaf leaf = (Leaf) node;
        int position = leaf.search(key);
        return position >= 0 ? Optional.of(schema.cast(leaf.value(position))) : Optional.empty();
    }

    public boolean contains(long key) {
        return get(key).isPresent();
    }

    public Entry<V> at(long rank) {
        Objects.checkIndex(rank, size());
        long remaining = rank;
        Node node = node(root);
        while (node instanceof Branch branch) {
            int slot = 0;
            long count;
            while (remaining >= (count = branch.child(slot).count())) {
                remaining -= count;
                slot++;
            }
            node = node(branch.child(slot));
        }
        Leaf leaf = (Leaf) node;
        int position = (int) remaining;
        return new Entry<>(leaf.key(position), schema.cast(leaf.value(position)));
    }

    public long rankOf(long key) {
        if (isEmpty()) {
            return -1;
        }
        long before = 0;
        Node node = node(root);
        while (node instanceof Branch branch) {
            int slot = branch.route(key);
            for (int i = 0; i < slot; i++) {
                before += branch.child(i).count();
            }
            node = node(branch.child(slot));
        }
        int position = ((Leaf) node).search(key);
        return position >= 0 ? before + position : -(before + (-position - 1)) - 1;
    }

    public Summary summarize(long low, long high) {
        if (isEmpty() || low > high) {
            return Summary.EMPTY;
        }
        Summary.Builder builder = Summary.builder(schema.fingerprint());
        summarize(root, low, high, builder);
        return builder.build();
    }

    private void summarize(Ref ref, long low, long high, Summary.Builder into) {
        Summary summary = ref.summary();
        if (!summary.overlaps(low, high)) {
            return;
        }
        if (summary.min() >= low && summary.max() <= high) {
            into.merge(summary);
            return;
        }
        switch (node(ref)) {
            case Leaf leaf -> {
                for (int i = 0; i < leaf.size(); i++) {
                    if (leaf.key(i) >= low && leaf.key(i) <= high) {
                        schema.measureEntry(leaf.key(i), leaf.value(i), into);
                    }
                }
            }
            case Branch branch -> {
                for (int i = 0; i < branch.size(); i++) {
                    summarize(branch.child(i), low, high, into);
                }
            }
        }
    }

    public Optional<Entry<V>> first() {
        TreeCursor<V> cursor = cursor();
        return cursor.hasNext() ? Optional.of(cursor.next()) : Optional.empty();
    }

    public Optional<Entry<V>> last() {
        TreeCursor<V> cursor = new TreeCursor<>(this, _ -> true, true);
        return cursor.hasNext() ? Optional.of(cursor.next()) : Optional.empty();
    }

    public Optional<Entry<V>> ceiling(long key) {
        TreeCursor<V> cursor = cursor();
        return cursor.seek(key) ? Optional.of(cursor.next()) : Optional.empty();
    }

    public Optional<Entry<V>> floor(long key) {
        TreeCursor<V> cursor = new TreeCursor<>(this, _ -> true, true);
        return cursor.seek(key) ? Optional.of(cursor.next()) : Optional.empty();
    }

    public TreeCursor<V> cursor() {
        return new TreeCursor<>(this, _ -> true, false);
    }

    public TreeCursor<V> cursor(Predicate<Summary> admit, boolean descending) {
        return new TreeCursor<>(this, admit, descending);
    }

    public Stream<Entry<V>> stream() {
        return stream(cursor());
    }

    public Stream<Entry<V>> descending() {
        return stream(new TreeCursor<>(this, _ -> true, true));
    }

    public Stream<Entry<V>> scan(Predicate<Summary> admit) {
        return stream(new TreeCursor<>(this, admit, false));
    }

    public Stream<Entry<V>> range(long low, long high) {
        if (low > high) {
            return Stream.empty();
        }
        TreeCursor<V> cursor = new TreeCursor<>(this, summary -> summary.overlaps(low, high), false);
        if (!cursor.seek(low)) {
            return Stream.empty();
        }
        return stream(cursor).takeWhile(entry -> entry.key() <= high);
    }

    public LongStream keys() {
        return stream().mapToLong(Entry::key);
    }

    public Stream<V> values() {
        return stream().map(Entry::value);
    }

    public Keyset.Slice<V> slice(Keyset keyset, int limit) {
        TreeCursor<V> cursor = new TreeCursor<>(this, _ -> true, keyset.descending());
        boolean positioned = !keyset.started() || seekPast(cursor, keyset);
        List<Entry<V>> entries = new ArrayList<>(Math.min(limit, 1024));
        while (positioned && cursor.hasNext() && entries.size() < limit) {
            entries.add(cursor.next());
        }
        Optional<Keyset> next = cursor.hasNext() && !entries.isEmpty()
                ? Optional.of(keyset.advancedTo(entries.getLast().key()))
                : Optional.empty();
        return new Keyset.Slice<>(List.copyOf(entries), next);
    }

    private static boolean seekPast(TreeCursor<?> cursor, Keyset keyset) {
        long last = keyset.lastKey();
        if (keyset.descending()) {
            return last != Long.MIN_VALUE && cursor.seek(last - 1);
        }
        return last != Long.MAX_VALUE && cursor.seek(last + 1);
    }

    public Tree<V> put(WriteScope scope, long key, V value) {
        return update(scope, key, _ -> Optional.of(value));
    }

    public Tree<V> remove(WriteScope scope, long key) {
        return update(scope, key, _ -> Optional.empty());
    }

    public Tree<V> update(WriteScope scope, long key, UnaryOperator<Optional<V>> change) {
        return withRoot(new TreeWriter<>(schema, source, scope).update(root, key, change));
    }

    public Tree<V> join(WriteScope scope, Tree<V> greater) {
        if (!isEmpty() && !greater.isEmpty() && summary().max() >= greater.summary().min()) {
            throw new IllegalArgumentException("join requires disjoint ascending key ranges");
        }
        scope.freeze();
        return withRoot(new TreeWriter<>(schema, source, scope).join(root, greater.root()));
    }

    public BulkBuilder<V> builder(WriteScope scope) {
        return new BulkBuilder<>(schema, source, scope);
    }

    Node node(Ref ref) {
        return Trees.node(source, schema, ref);
    }

    static <V> Stream<Entry<V>> stream(Iterator<Entry<V>> iterator) {
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator,
                Spliterator.ORDERED | Spliterator.NONNULL | Spliterator.DISTINCT), false);
    }
}

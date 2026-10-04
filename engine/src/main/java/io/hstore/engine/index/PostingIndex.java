package io.hstore.engine.index;

import io.hstore.engine.tree.BulkBuilder;
import io.hstore.engine.tree.Entry;
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;
import io.hstore.engine.tree.WriteScope;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.ToLongFunction;
import java.util.stream.IntStream;
import java.util.stream.Stream;

public final class PostingIndex<P> {

    private final TreeSchema<Postings<P>> directory;
    private final TreeSchema<P> postings;
    private final PostingsCodec<P> codec;
    private final int inlineLimit;

    private PostingIndex(TreeSchema<Postings<P>> directory, TreeSchema<P> postings, PostingsCodec<P> codec, int inlineLimit) {
        this.directory = directory;
        this.postings = postings;
        this.codec = codec;
        this.inlineLimit = inlineLimit;
    }

    public static <P> PostingIndex<P> of(int directoryId, int postingsId, String name, ValueCodec<P> codec,
                                         ToLongFunction<P> hash, int inlineLimit) {
        TreeSchema<P> inner = new TreeSchema<>(postingsId, name + "-postings", FingerprintMode.SET, codec, EntryMeasure.keyed(hash));
        EntryMeasure<Postings<P>> outerMeasure = (key, value, into) ->
                into.entry(key, Hashing.combine(Hashing.mix(key), fingerprint(value))).weight(value.size());
        PostingsCodec<P> postingsCodec = new PostingsCodec<>(inner, hash);
        TreeSchema<Postings<P>> outer = new TreeSchema<>(directoryId, name, FingerprintMode.SET, postingsCodec, outerMeasure);
        return new PostingIndex<>(outer, inner, postingsCodec, inlineLimit);
    }

    private static <P> long fingerprint(Postings<P> value) {
        return switch (value) {
            case Postings.Inline<P> inline -> inline.fingerprint();
            case Postings.Promoted<P>(Ref root) -> root.summary().fingerprint();
        };
    }

    public TreeSchema<Postings<P>> schema() {
        return directory;
    }

    public TreeSchema<P> postingSchema() {
        return postings;
    }

    public Tree<Postings<P>> add(Tree<Postings<P>> index, WriteScope scope, long key, long posting, P value) {
        return index.update(scope, key, current -> Optional.of(switch (current.orElse(null)) {
            case null -> codec.inline(new long[]{posting}, List.of(value));
            case Postings.Inline<P> inline -> insertInline(index, scope, inline, posting, value);
            case Postings.Promoted<P>(Ref root) -> new Postings.Promoted<>(subtree(index, root).put(scope, posting, value).root());
        }));
    }

    public Tree<Postings<P>> remove(Tree<Postings<P>> index, WriteScope scope, long key, long posting) {
        return index.update(scope, key, current -> current.flatMap(value -> switch (value) {
            case Postings.Inline<P> inline -> removeInline(inline, posting);
            case Postings.Promoted<P>(Ref root) -> {
                Tree<P> remaining = subtree(index, root).remove(scope, posting);
                yield remaining.isEmpty() ? Optional.empty()
                        : Optional.of(remaining.size() <= inlineLimit / 2 ? demote(remaining) : new Postings.Promoted<>(remaining.root()));
            }
        }));
    }

    public long countRange(Tree<Postings<P>> index, long low, long high) {
        return index.summarize(low, high).weightSum();
    }

    public long total(Tree<Postings<P>> index) {
        return index.summary().weightSum();
    }

    public long count(Tree<Postings<P>> index, long key) {
        return index.get(key).map(Postings::size).orElse(0L);
    }

    public Optional<P> get(Tree<Postings<P>> index, long key, long posting) {
        return index.get(key).flatMap(value -> switch (value) {
            case Postings.Inline<P> inline -> {
                int found = inline.search(posting);
                yield found >= 0 ? Optional.of(inline.values().get(found)) : Optional.empty();
            }
            case Postings.Promoted<P>(Ref root) -> subtree(index, root).get(posting);
        });
    }

    public Stream<Entry<P>> stream(Tree<Postings<P>> index, long key) {
        return index.get(key).map(value -> switch (value) {
            case Postings.Inline<P>(long[] keys, List<P> values, long _, int _) ->
                    IntStream.range(0, keys.length).mapToObj(i -> new Entry<>(keys[i], values.get(i)));
            case Postings.Promoted<P>(Ref root) -> subtree(index, root).stream();
        }).orElseGet(Stream::empty);
    }

    public Tree<P> tree(Tree<Postings<P>> index, long key) {
        return index.get(key).map(value -> switch (value) {
            case Postings.Inline<P>(long[] keys, List<P> values, long _, int _) -> {
                BulkBuilder<P> builder = Tree.empty(postings, index.source()).builder(new WriteScope());
                for (int i = 0; i < keys.length; i++) {
                    builder.add(keys[i], values.get(i));
                }
                yield builder.build();
            }
            case Postings.Promoted<P>(Ref root) -> subtree(index, root);
        }).orElseGet(() -> Tree.empty(postings, index.source()));
    }

    private Tree<P> subtree(Tree<Postings<P>> index, Ref root) {
        return new Tree<>(postings, index.source(), root);
    }

    private Postings<P> insertInline(Tree<Postings<P>> index, WriteScope scope, Postings.Inline<P> inline, long posting, P value) {
        int found = inline.search(posting);
        List<P> values = new ArrayList<>(inline.values());
        long[] keys;
        if (found >= 0) {
            keys = inline.keys();
            values.set(found, value);
        } else {
            int at = -found - 1;
            keys = new long[inline.keys().length + 1];
            System.arraycopy(inline.keys(), 0, keys, 0, at);
            System.arraycopy(inline.keys(), at, keys, at + 1, inline.keys().length - at);
            keys[at] = posting;
            values.add(at, value);
        }
        Postings.Inline<P> grown = codec.inline(keys, values);
        if (keys.length <= inlineLimit && directory.codec().maxSize(grown) <= index.source().layout().maxValueBytes() / 2) {
            return grown;
        }
        BulkBuilder<P> builder = Tree.empty(postings, index.source()).builder(scope);
        for (int i = 0; i < keys.length; i++) {
            builder.add(keys[i], values.get(i));
        }
        return new Postings.Promoted<>(builder.build().root());
    }

    private Optional<Postings<P>> removeInline(Postings.Inline<P> inline, long posting) {
        int found = inline.search(posting);
        if (found < 0) {
            return Optional.of(inline);
        }
        if (inline.keys().length == 1) {
            return Optional.empty();
        }
        long[] keys = new long[inline.keys().length - 1];
        System.arraycopy(inline.keys(), 0, keys, 0, found);
        System.arraycopy(inline.keys(), found + 1, keys, found, keys.length - found);
        List<P> values = new ArrayList<>(inline.values());
        values.remove(found);
        return Optional.of(codec.inline(keys, values));
    }

    private Postings<P> demote(Tree<P> remaining) {
        List<Entry<P>> entries = remaining.stream().toList();
        long[] keys = entries.stream().mapToLong(Entry::key).toArray();
        return codec.inline(keys, entries.stream().map(Entry::value).toList());
    }

    @Override
    public String toString() {
        return directory.name() + Arrays.asList(directory.id(), postings.id());
    }
}

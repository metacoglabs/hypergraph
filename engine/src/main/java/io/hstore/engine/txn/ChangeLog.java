// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.txn;

import io.hstore.engine.catalog.RootVector;
import io.hstore.engine.catalog.SlotRegistry;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.NodeSource;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeDiff;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;
import io.hstore.engine.tree.WriteScope;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

public final class ChangeLog {

    static final int MAX_DELTA = 4096;

    private static final int DELTA = 0;
    private static final int ROOT = 1;
    private static final int UNCHANGED = 2;
    private static final int REMOVE = 0;
    private static final int PUT = 1;

    @FunctionalInterface
    public interface Durable {
        Ref of(Ref.Pending pending, TreeSchema<?> schema);
    }

    private record Nested(Ref ref, TreeSchema<?> schema) {
    }

    private ChangeLog() {
    }

    public static byte[] encode(RootVector before, RootVector after, SlotRegistry slots, NodeSource source, Durable durable) {
        TreeSet<Integer> ids = new TreeSet<>(before.roots().keySet());
        ids.addAll(after.roots().keySet());
        List<Integer> changed = ids.stream().filter(id -> !Ref.same(before.get(id), after.get(id))).toList();
        ByteCursor out = ByteCursor.growable(256);
        out.putVarInt(changed.size());
        for (int id : changed) {
            out.putVarInt(id);
            writeTree(out, slots.slot(id).schema(), before.get(id), after.get(id), source, durable);
        }
        return out.toByteArray();
    }

    public static RootVector apply(RootVector roots, byte[] payload, SlotRegistry slots, NodeSource source, WriteScope scope) {
        ByteCursor in = ByteCursor.wrap(payload);
        RootVector applied = roots;
        for (int count = in.getVarInt(); count > 0; count--) {
            int id = in.getVarInt();
            applied = applied.with(id, readTree(in, slots.slot(id).schema(), applied.get(id), source, scope));
        }
        return applied;
    }

    private static <V> void writeTree(ByteCursor out, TreeSchema<V> schema, Ref base, Ref after, NodeSource source, Durable durable) {
        if (Ref.same(base, after)) {
            out.putByte(UNCHANGED);
            return;
        }
        if (!(after instanceof Ref.Pending pending)) {
            out.putByte(ROOT);
            Ref.write(out, after);
            return;
        }
        if (Math.abs(after.count() - base.count()) > MAX_DELTA) {
            out.putByte(ROOT);
            Ref.write(out, durable.of(pending, schema));
            return;
        }
        Optional<List<TreeDiff.Delta<V>>> bounded = TreeDiff.bounded(new Tree<>(schema, source, base), new Tree<>(schema, source, after), MAX_DELTA);
        if (bounded.isEmpty()) {
            out.putByte(ROOT);
            Ref.write(out, durable.of(pending, schema));
            return;
        }
        List<TreeDiff.Delta<V>> deltas = bounded.get();
        out.putByte(DELTA).putVarInt(deltas.size());
        for (TreeDiff.Delta<V> delta : deltas) {
            out.putLong(delta.key());
            switch (delta) {
                case TreeDiff.Delta.Removed<V> _ -> out.putByte(REMOVE);
                case TreeDiff.Delta.Added<V>(long key, V value) -> {
                    out.putByte(PUT);
                    writeValue(out, schema, key, null, value, source, durable);
                }
                case TreeDiff.Delta.Changed<V>(long key, V previous, V value) -> {
                    out.putByte(PUT);
                    writeValue(out, schema, key, previous, value, source, durable);
                }
            }
        }
    }

    private static <V> void writeValue(ByteCursor out, TreeSchema<V> schema, long key, V previous, V value, NodeSource source, Durable durable) {
        ValueCodec<V> codec = schema.codec();
        boolean nested = codec.holdsRefs();
        ByteCursor encoded = ByteCursor.growable(64);
        codec.encode(encoded, new long[]{key}, List.of(nested ? codec.mapRefs(value, (_, _) -> Ref.EMPTY) : value));
        out.putBlob(encoded.toByteArray());
        if (nested) {
            List<Nested> bases = nested(codec, previous);
            int[] position = {0};
            codec.forEachRef(value, (ref, nestedSchema) ->
                    writeTree(out, nestedSchema, baseAt(bases, position[0]++, nestedSchema), ref, source, durable));
        }
    }

    private static <V> Ref readTree(ByteCursor in, TreeSchema<V> schema, Ref base, NodeSource source, WriteScope scope) {
        int kind = in.getUnsignedByte();
        return switch (kind) {
            case UNCHANGED -> base;
            case ROOT -> Ref.read(in, schema);
            case DELTA -> {
                Tree<V> tree = new Tree<>(schema, source, base);
                for (int count = in.getVarInt(); count > 0; count--) {
                    long key = in.getLong();
                    int op = in.getUnsignedByte();
                    tree = switch (op) {
                        case REMOVE -> tree.remove(scope, key);
                        case PUT -> tree.put(scope, key, readValue(in, schema, key, tree.get(key).orElse(null), source, scope));
                        default -> throw new IllegalStateException("unknown change operation " + op);
                    };
                }
                yield tree.root();
            }
            default -> throw new IllegalStateException("unknown tree change " + kind);
        };
    }

    private static <V> V readValue(ByteCursor in, TreeSchema<V> schema, long key, V previous, NodeSource source, WriteScope scope) {
        ValueCodec<V> codec = schema.codec();
        Object[] decoded = new Object[1];
        codec.decode(ByteCursor.wrap(in.getBlob()), new long[]{key}, decoded);
        V value = schema.cast(decoded[0]);
        if (!codec.holdsRefs()) {
            return value;
        }
        List<Nested> bases = nested(codec, previous);
        int[] position = {0};
        return codec.mapRefs(value, (_, nestedSchema) -> readTree(in, nestedSchema, baseAt(bases, position[0]++, nestedSchema), source, scope));
    }

    private static <V> List<Nested> nested(ValueCodec<V> codec, V value) {
        List<Nested> refs = new ArrayList<>(2);
        if (value != null) {
            codec.forEachRef(value, (ref, schema) -> refs.add(new Nested(ref, schema)));
        }
        return refs;
    }

    private static Ref baseAt(List<Nested> bases, int position, TreeSchema<?> schema) {
        return position < bases.size() && bases.get(position).schema().equals(schema) ? bases.get(position).ref() : Ref.EMPTY;
    }
}

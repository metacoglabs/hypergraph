// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import java.util.Map;
import java.util.function.LongPredicate;

public final class TreeWalker {

    private static final int UNKNOWN_HEIGHT = -1;

    private final NodeSource source;

    public TreeWalker(NodeSource source) {
        this.source = source;
    }

    public void visit(Ref root, TreeSchema<?> schema, Map<Long, Integer> visited) {
        visit(root, schema, UNKNOWN_HEIGHT, visited);
    }

    private void visit(Ref ref, TreeSchema<?> schema, int height, Map<Long, Integer> visited) {
        if (!(ref instanceof Ref.Stored(long pageId, int units, Summary _)) || visited.putIfAbsent(pageId, units) != null) {
            return;
        }
        if (height == 0 && !schema.codec().holdsRefs()) {
            return;
        }
        switch (source.load(pageId, schema)) {
            case Leaf leaf -> {
                for (int i = 0; i < leaf.size(); i++) {
                    mapNested(schema, leaf.value(i), (nested, nestedSchema) -> {
                        visit(nested, nestedSchema, UNKNOWN_HEIGHT, visited);
                        return nested;
                    });
                }
            }
            case Branch branch -> {
                for (int i = 0; i < branch.size(); i++) {
                    visit(branch.child(i), schema, branch.height() - 1, visited);
                }
            }
        }
    }

    public Ref relocate(Ref root, TreeSchema<?> schema, LongPredicate moving, WriteScope scope) {
        return relocate(root, schema, UNKNOWN_HEIGHT, moving, scope);
    }

    private Ref relocate(Ref ref, TreeSchema<?> schema, int height, LongPredicate inVictim, WriteScope scope) {
        if (!(ref instanceof Ref.Stored(long pageId, int _, Summary _))) {
            return ref;
        }
        boolean moving = inVictim.test(pageId);
        if (height == 0 && !moving && !schema.codec().holdsRefs()) {
            return ref;
        }
        return switch (source.load(pageId, schema)) {
            case Leaf leaf -> {
                Object[] values = leaf.valueList().toArray();
                boolean changed = moving;
                for (int i = 0; i < values.length; i++) {
                    Object mapped = mapNested(schema, values[i], (nested, nestedSchema) ->
                            relocate(nested, nestedSchema, UNKNOWN_HEIGHT, inVictim, scope));
                    changed |= mapped != values[i];
                    values[i] = mapped;
                }
                yield changed ? new Ref.Pending(Leaf.owned(schema, scope.token(), leaf.keyArray(), values, values.length)) : ref;
            }
            case Branch branch -> {
                Ref[] children = branch.childArray();
                boolean changed = moving;
                for (int i = 0; i < children.length; i++) {
                    Ref moved = relocate(children[i], schema, branch.height() - 1, inVictim, scope);
                    changed |= moved != children[i];
                    children[i] = moved;
                }
                yield changed
                        ? new Ref.Pending(Branch.owned(schema, scope.token(), branch.separatorArray(), children, children.length, branch.height()))
                        : ref;
            }
        };
    }

    private static <V> Object mapNested(TreeSchema<V> schema, Object value, ValueCodec.RefMapper mapper) {
        if (!schema.codec().holdsRefs()) {
            return value;
        }
        V typed = schema.cast(value);
        V mapped = schema.codec().mapRefs(typed, mapper);
        return mapped.equals(typed) ? value : mapped;
    }
}

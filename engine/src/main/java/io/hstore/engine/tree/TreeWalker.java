package io.hstore.engine.tree;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;
import java.util.function.UnaryOperator;

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
        return relocate(root, schema, moving, scope, _ -> {
        });
    }

    public Ref relocate(Ref root, TreeSchema<?> schema, LongPredicate moving, WriteScope scope, LongConsumer reached) {
        return relocate(root, schema, moving, scope, reached, leaf -> leaf);
    }

    public Ref relocate(Ref root, TreeSchema<?> schema, LongPredicate moving, WriteScope scope, LongConsumer reached,
                        UnaryOperator<Ref.Stored> copyLeaf) {
        return relocate(root, schema, UNKNOWN_HEIGHT, moving, scope, reached, copyLeaf);
    }

    private Ref relocate(Ref ref, TreeSchema<?> schema, int height, LongPredicate inVictim, WriteScope scope, LongConsumer reached,
                         UnaryOperator<Ref.Stored> copyLeaf) {
        if (!(ref instanceof Ref.Stored stored)) {
            return ref;
        }
        long pageId = stored.pageId();
        reached.accept(pageId);
        boolean moving = inVictim.test(pageId);
        if (!schema.codec().holdsRefs()) {
            if (height == 0 && !moving) {
                return ref;
            }
            if (moving && height <= 0) {
                Ref.Stored copy = copyLeaf.apply(stored);
                if (copy != stored) {
                    return copy;
                }
            }
        }
        return rebuild(ref, pageId, schema, moving, scope, (child, childSchema, childHeight) ->
                relocate(child, childSchema, childHeight, inVictim, scope, reached, copyLeaf));
    }

    public void pairMoves(Ref before, Ref after, TreeSchema<?> schema, Map<Long, Ref> moved) {
        pairMoves(before, after, schema, UNKNOWN_HEIGHT, moved);
    }

    private void pairMoves(Ref before, Ref after, TreeSchema<?> schema, int height, Map<Long, Ref> moved) {
        if (!(before instanceof Ref.Stored(long oldId, int _, Summary _)) || !(after instanceof Ref.Stored(long newId, int _, Summary _))
                || oldId == newId || moved.putIfAbsent(oldId, after) != null) {
            return;
        }
        if (height == 0 && !schema.codec().holdsRefs()) {
            return;
        }
        switch (source.load(oldId, schema)) {
            case Branch branch -> {
                Branch copy = (Branch) source.load(newId, schema);
                for (int i = 0; i < branch.size(); i++) {
                    pairMoves(branch.child(i), copy.child(i), schema, branch.height() - 1, moved);
                }
            }
            case Leaf leaf -> {
                Leaf copy = (Leaf) source.load(newId, schema);
                for (int i = 0; i < leaf.size(); i++) {
                    List<Ref> oldRefs = new ArrayList<>();
                    List<Ref> newRefs = new ArrayList<>();
                    List<TreeSchema<?>> schemas = new ArrayList<>();
                    mapNested(schema, leaf.value(i), (nested, nestedSchema) -> {
                        oldRefs.add(nested);
                        schemas.add(nestedSchema);
                        return nested;
                    });
                    mapNested(schema, copy.value(i), (nested, _) -> {
                        newRefs.add(nested);
                        return nested;
                    });
                    for (int k = 0; k < oldRefs.size(); k++) {
                        pairMoves(oldRefs.get(k), newRefs.get(k), schemas.get(k), UNKNOWN_HEIGHT, moved);
                    }
                }
            }
        }
    }

    public Ref adopt(Ref root, TreeSchema<?> schema, Map<Long, Ref> moved, LongPredicate existed, WriteScope scope) {
        return adopt(root, schema, UNKNOWN_HEIGHT, moved, existed, scope);
    }

    private Ref adopt(Ref ref, TreeSchema<?> schema, int height, Map<Long, Ref> moved, LongPredicate existed, WriteScope scope) {
        if (!(ref instanceof Ref.Stored(long pageId, int _, Summary _))) {
            return ref;
        }
        Ref copy = moved.get(pageId);
        if (copy != null) {
            return copy;
        }
        if (existed.test(pageId) || (height == 0 && !schema.codec().holdsRefs())) {
            return ref;
        }
        return rebuild(ref, pageId, schema, false, scope, (child, childSchema, childHeight) ->
                adopt(child, childSchema, childHeight, moved, existed, scope));
    }

    @FunctionalInterface
    private interface ChildMapper {
        Ref map(Ref child, TreeSchema<?> schema, int height);
    }

    private Ref rebuild(Ref ref, long pageId, TreeSchema<?> schema, boolean force, WriteScope scope, ChildMapper mapper) {
        return switch (source.load(pageId, schema)) {
            case Leaf leaf -> {
                Object[] values = leaf.valueList().toArray();
                boolean changed = force;
                for (int i = 0; i < values.length; i++) {
                    Object mapped = mapNested(schema, values[i], (nested, nestedSchema) -> mapper.map(nested, nestedSchema, UNKNOWN_HEIGHT));
                    changed |= mapped != values[i];
                    values[i] = mapped;
                }
                yield changed ? new Ref.Pending(Leaf.owned(schema, scope.token(), leaf.keyArray(), values, values.length)) : ref;
            }
            case Branch branch -> {
                Ref[] children = branch.childArray();
                boolean changed = force;
                for (int i = 0; i < children.length; i++) {
                    Ref mapped = mapper.map(children[i], schema, branch.height() - 1);
                    changed |= mapped != children[i];
                    children[i] = mapped;
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

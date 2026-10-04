package io.hstore.db.property;

import io.hstore.db.property.PropertySlots.IndexRoot;
import io.hstore.db.value.TypeTag;
import io.hstore.db.value.Value;
import io.hstore.db.value.Values;
import io.hstore.engine.index.Postings;
import io.hstore.engine.tree.Entry;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.txn.Workspace;

import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

public final class PropertyIndex {

    private PropertyIndex() {
    }

    public static void add(Workspace workspace, int tenant, int propertyKey, Value value, long owner) {
        if (value instanceof Value.Null) {
            return;
        }
        change(workspace, PropertySlots.directoryKey(tenant, propertyKey, value.tag()), values ->
                PropertySlots.VALUE_POSTINGS.add(values, workspace.scope(), Values.orderKey(value), owner, value));
    }

    public static void remove(Workspace workspace, int tenant, int propertyKey, Value value, long owner) {
        if (value instanceof Value.Null) {
            return;
        }
        change(workspace, PropertySlots.directoryKey(tenant, propertyKey, value.tag()), values ->
                PropertySlots.VALUE_POSTINGS.remove(values, workspace.scope(), Values.orderKey(value), owner));
    }

    private static void change(Workspace workspace, long directoryKey, UnaryOperator<Tree<Postings<Value>>> update) {
        Tree<IndexRoot> directory = workspace.tree(PropertySlots.PROPERTY_INDEX);
        Ref current = directory.get(directoryKey).map(IndexRoot::root).orElse(Ref.EMPTY);
        Tree<Postings<Value>> values = new Tree<>(PropertySlots.postingSchema(), workspace.source(), current);
        Ref updated = update.apply(values).root();
        workspace.replace(PropertySlots.PROPERTY_INDEX, updated instanceof Ref.Empty
                ? directory.remove(workspace.scope(), directoryKey)
                : directory.put(workspace.scope(), directoryKey, new IndexRoot(updated)));
    }

    public static Tree<Postings<Value>> values(Tree<IndexRoot> directory, int tenant, int propertyKey, TypeTag tag) {
        Ref root = directory.get(PropertySlots.directoryKey(tenant, propertyKey, tag)).map(IndexRoot::root).orElse(Ref.EMPTY);
        return new Tree<>(PropertySlots.postingSchema(), directory.source(), root);
    }

    public static Stream<Entry<Value>> candidates(Tree<IndexRoot> directory, int tenant, int propertyKey, TypeTag tag, long lowKey, long highKey) {
        Tree<Postings<Value>> values = values(directory, tenant, propertyKey, tag);
        return values.range(lowKey, highKey)
                .flatMap(bucket -> PropertySlots.VALUE_POSTINGS.stream(values, bucket.key()));
    }

    public static long estimate(Tree<IndexRoot> directory, int tenant, int propertyKey, TypeTag tag, long lowKey, long highKey) {
        return PropertySlots.VALUE_POSTINGS.countRange(values(directory, tenant, propertyKey, tag), lowKey, highKey);
    }

    public static long lowerKey(TypeTag tag, Optional<Value> bound) {
        return bound.map(value -> boundKey(tag, value, true)).orElse(Long.MIN_VALUE);
    }

    public static long upperKey(TypeTag tag, Optional<Value> bound) {
        return bound.map(value -> boundKey(tag, value, false)).orElse(Long.MAX_VALUE);
    }

    private static long boundKey(TypeTag tag, Value bound, boolean lower) {
        if (tag == bound.tag()) {
            return Values.orderKey(bound);
        }
        return switch (tag) {
            case INT, TIMESTAMP -> bound.number().stream()
                    .mapToLong(number -> lower ? (long) Math.floor(number) : (long) Math.ceil(number))
                    .findFirst().orElse(lower ? Long.MIN_VALUE : Long.MAX_VALUE);
            case FLOAT, DECIMAL -> bound.number().stream()
                    .mapToLong(number -> Values.orderKey(new Value.Real(number)))
                    .findFirst().orElse(lower ? Long.MIN_VALUE : Long.MAX_VALUE);
            default -> lower ? Long.MIN_VALUE : Long.MAX_VALUE;
        };
    }
}

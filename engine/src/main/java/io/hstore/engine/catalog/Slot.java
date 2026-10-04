package io.hstore.engine.catalog;

import io.hstore.engine.tree.TreeSchema;

public record Slot<V>(int id, String name, TreeSchema<V> schema, Kind kind) {

    public enum Kind { PRIMARY, DERIVED }

    public static <V> Slot<V> primary(int id, String name, TreeSchema<V> schema) {
        return new Slot<>(id, name, schema, Kind.PRIMARY);
    }

    public static <V> Slot<V> derived(int id, String name, TreeSchema<V> schema) {
        return new Slot<>(id, name, schema, Kind.DERIVED);
    }

    public boolean isPrimary() {
        return kind == Kind.PRIMARY;
    }

    @Override
    public String toString() {
        return name + "@" + id;
    }
}

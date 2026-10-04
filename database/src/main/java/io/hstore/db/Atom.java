package io.hstore.db;

import io.hstore.db.schema.TypeDef;
import io.hstore.engine.catalog.AtomRecord;

import java.util.Optional;

public record Atom(long id, TypeDef type, AtomRecord record) {

    public boolean isEdge() {
        return record instanceof AtomRecord.EdgeRecord;
    }

    public Optional<String> key() {
        return record instanceof AtomRecord.NodeRecord node ? Optional.ofNullable(node.canonicalKey()) : Optional.empty();
    }

    public long cardinality() {
        return record instanceof AtomRecord.EdgeRecord edge ? edge.cardinality() : 0;
    }
}

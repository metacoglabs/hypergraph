// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.schema;

import io.hstore.db.schema.SchemaSlots.TypeName;
import io.hstore.db.schema.TypeDef.JsonIndex;
import io.hstore.db.schema.TypeDef.PropertyDef;
import io.hstore.engine.Dictionary;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.Symbol;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.View;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

public final class Schema {

    public static final int PROPERTY_KEY = 10;
    public static final String DOCUMENT = "$document";

    private final Dictionary dictionary;

    public Schema(Dictionary dictionary) {
        this.dictionary = dictionary;
    }

    public Optional<TypeDef> type(View view, String name) {
        return view.get(SchemaSlots.TYPE_NAMES, SchemaSlots.nameKey(name))
                .filter(found -> found.name().equalsIgnoreCase(name))
                .flatMap(found -> type(view, found.id()));
    }

    public Optional<TypeDef> type(View view, int id) {
        return view.get(SchemaSlots.TYPES, id);
    }

    public TypeDef require(View view, String name) {
        return type(view, name).orElseThrow(() -> HStoreException.invalid("unknown type '" + name + "'"));
    }

    public TypeDef require(View view, int id) {
        return type(view, id).orElseThrow(() -> HStoreException.invalid("unknown type id " + id));
    }

    public List<TypeDef> types(View view) {
        return view.scan(SchemaSlots.TYPES).values().toList();
    }

    public TypeDef define(Transaction txn, String name, AtomKind kind, List<PropertyDef> properties, List<String> roles) {
        if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw HStoreException.invalid("invalid type name '" + name + "'");
        }
        if (type(txn, name).isPresent()) {
            throw HStoreException.invalid("type '" + name + "' already exists");
        }
        int id = txn.scan(SchemaSlots.TYPES).last().map(entry -> (int) entry.key() + 1).orElse(1);
        TypeDef type = new TypeDef(id, name, kind, properties, roles, List.of(), 1);
        properties.forEach(property -> propertyKey(property.name()));
        txn.merge(SchemaSlots.TYPE_NAMES, SchemaSlots.nameKey(name), Optional::isEmpty, _ -> Optional.of(new TypeName(name, id)));
        txn.merge(SchemaSlots.TYPES, id, Optional::isEmpty, _ -> Optional.of(type));
        return type;
    }

    public TypeDef alter(Transaction txn, TypeDef updated) {
        TypeDef current = require(txn, updated.id());
        if (current.kind() != updated.kind() || !current.name().equals(updated.name())) {
            throw HStoreException.invalid("a type's name and kind are immutable");
        }
        updated.properties().forEach(property -> propertyKey(property.name()));
        updated.jsonIndexes().stream().map(JsonIndex::path).forEach(this::propertyKey);
        txn.put(SchemaSlots.TYPES, updated.id(), updated);
        return updated;
    }

    public int propertyKey(String name) {
        return dictionary.name(PROPERTY_KEY, name);
    }

    public OptionalInt existingPropertyKey(String name) {
        return dictionary.lookup(new Symbol.Name(PROPERTY_KEY, name));
    }

    public String propertyName(int key) {
        return dictionary.nameOf(key);
    }

    public Dictionary dictionary() {
        return dictionary;
    }
}

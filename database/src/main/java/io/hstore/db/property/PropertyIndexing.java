// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.property;

import io.hstore.db.payload.PayloadStore;
import io.hstore.db.property.PropertyBag.Property;
import io.hstore.db.schema.Schema;
import io.hstore.db.schema.SchemaSlots;
import io.hstore.db.schema.TypeDef;
import io.hstore.db.value.Json;
import io.hstore.db.value.Value;
import io.hstore.db.value.Values;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.catalog.SlotChange;
import io.hstore.engine.txn.Derivation;
import io.hstore.engine.txn.Workspace;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeSet;
import java.util.function.Supplier;
import java.util.stream.Stream;

public final class PropertyIndexing implements Derivation {

    private final Supplier<Schema> schema;
    private final Supplier<PayloadStore> payloads;

    public PropertyIndexing(Supplier<Schema> schema, Supplier<PayloadStore> payloads) {
        this.schema = schema;
        this.payloads = payloads;
    }

    @Override
    public void onSlot(SlotChange<?> change, Workspace workspace) {
        if (change.slot() != PropertySlots.PROPERTIES) {
            return;
        }
        long owner = change.key();
        Optional<AtomRecord> record = workspace.get(EngineSlots.CATALOG, owner);
        Optional<TypeDef> type = record.flatMap(found -> workspace.get(SchemaSlots.TYPES, found.type()));
        if (type.isEmpty()) {
            return;
        }
        int tenant = record.get().tenant();
        PropertyBag before = change.before().map(PropertyBag.class::cast).orElse(PropertyBag.EMPTY);
        PropertyBag after = change.after().map(PropertyBag.class::cast).orElse(PropertyBag.EMPTY);
        TreeSet<Integer> keys = new TreeSet<>(before.entries().keySet());
        keys.addAll(after.entries().keySet());
        for (int key : keys) {
            Optional<Property> was = before.get(key);
            Optional<Property> now = after.get(key);
            if (was.equals(now)) {
                continue;
            }
            String name = schema.get().propertyName(key);
            if (name.equals(Schema.DOCUMENT)) {
                reindexDocument(workspace, tenant, type.get(), owner, was, now);
            } else if (type.get().property(name).map(TypeDef.PropertyDef::indexed).orElse(false)) {
                was.ifPresent(property -> PropertyIndex.remove(workspace, tenant, key, property.value(), owner));
                now.ifPresent(property -> PropertyIndex.add(workspace, tenant, key, property.value(), owner));
            }
        }
    }

    private void reindexDocument(Workspace workspace, int tenant, TypeDef type, long owner, Optional<Property> was, Optional<Property> now) {
        for (TypeDef.JsonIndex index : type.jsonIndexes()) {
            OptionalInt key = schema.get().existingPropertyKey(index.path());
            if (key.isEmpty()) {
                continue;
            }
            List<Value> old = extract(was, index).toList();
            List<Value> fresh = extract(now, index).toList();
            old.stream().filter(value -> !fresh.contains(value)).forEach(value -> PropertyIndex.remove(workspace, tenant, key.getAsInt(), value, owner));
            fresh.stream().filter(value -> !old.contains(value)).forEach(value -> PropertyIndex.add(workspace, tenant, key.getAsInt(), value, owner));
        }
    }

    private Stream<Value> extract(Optional<Property> document, TypeDef.JsonIndex index) {
        return document.map(Property::value)
                .filter(value -> value instanceof Value.Payload)
                .map(value -> Json.parse(payloads.get().readText(((Value.Payload) value).ref())))
                .stream()
                .flatMap(json -> json.select(index.path()))
                .filter(value -> !(value instanceof Value.Null))
                .flatMap(value -> coerced(value, index).stream());
    }

    private static Optional<Value> coerced(Value value, TypeDef.JsonIndex index) {
        try {
            return Optional.of(Values.coerce(value, index.type()));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }
}

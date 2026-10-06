// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.schema;

import io.hstore.engine.catalog.Slot;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;

import java.util.List;

public final class SchemaSlots {

    public record TypeName(String name, int id) {
    }

    public static final TreeSchema<TypeDef> TYPE_SCHEMA =
            new TreeSchema<>(64, "types", FingerprintMode.SET, TypeDef.CODEC, EntryMeasure.keyed(TypeDef::hash));

    public static final TreeSchema<TypeName> NAME_SCHEMA = new TreeSchema<>(65, "type-names", FingerprintMode.SET,
            ValueCodec.rows(name -> ByteCursor.stringSize(name.name()) + 5,
                    (out, name) -> out.putString(name.name()).putVarInt(name.id()),
                    in -> new TypeName(in.getString(), in.getVarInt())),
            EntryMeasure.keyed(name -> Hashing.of(Hashing.of(name.name()), name.id())));

    public static final Slot<TypeDef> TYPES = Slot.primary(32, "types", TYPE_SCHEMA);
    public static final Slot<TypeName> TYPE_NAMES = Slot.primary(33, "type-names", NAME_SCHEMA);

    public static final List<Slot<?>> ALL = List.of(TYPES, TYPE_NAMES);

    private SchemaSlots() {
    }

    public static long nameKey(String name) {
        return Hashing.of(name.toLowerCase());
    }
}

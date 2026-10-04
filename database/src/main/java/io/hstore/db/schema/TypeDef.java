package io.hstore.db.schema;

import io.hstore.db.value.TypeTag;
import io.hstore.engine.HStoreException;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.ValueCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public record TypeDef(int id, String name, AtomKind kind, List<PropertyDef> properties, List<String> roles,
                      List<JsonIndex> jsonIndexes, int version) {

    public record PropertyDef(String name, TypeTag type, boolean indexed, boolean required) {
    }

    public record JsonIndex(String path, TypeTag type) {
    }

    public TypeDef {
        properties = List.copyOf(properties);
        roles = List.copyOf(roles);
        jsonIndexes = List.copyOf(jsonIndexes);
        if (properties.stream().map(PropertyDef::name).distinct().count() != properties.size()) {
            throw HStoreException.invalid("type " + name + " declares a property twice");
        }
    }

    public Optional<PropertyDef> property(String property) {
        return properties.stream().filter(def -> def.name().equals(property)).findFirst();
    }

    public boolean allowsRole(String role) {
        return roles.isEmpty() || roles.contains(role);
    }

    public TypeDef withProperty(PropertyDef property) {
        List<PropertyDef> next = new ArrayList<>(properties.stream().filter(def -> !def.name().equals(property.name())).toList());
        next.add(property);
        return new TypeDef(id, name, kind, next, roles, jsonIndexes, version + 1);
    }

    public TypeDef withJsonIndex(JsonIndex index) {
        List<JsonIndex> next = new ArrayList<>(jsonIndexes);
        next.add(index);
        return new TypeDef(id, name, kind, properties, roles, next, version + 1);
    }

    public long hash() {
        ByteCursor canonical = ByteCursor.growable(128);
        write(canonical, this);
        return Hashing.of(canonical.toByteArray());
    }

    static final ValueCodec<TypeDef> CODEC = ValueCodec.rows(TypeDef::encodedSize, TypeDef::write, TypeDef::read);

    private static int encodedSize(TypeDef type) {
        ByteCursor probe = ByteCursor.growable(128);
        write(probe, type);
        return Math.toIntExact(probe.position());
    }

    private static void write(ByteCursor out, TypeDef type) {
        out.putVarInt(type.id).putString(type.name).putByte(type.kind.ordinal()).putVarInt(type.version);
        out.putVarInt(type.properties.size());
        type.properties.forEach(property -> out.putString(property.name()).putByte(property.type().ordinal())
                .putByte((property.indexed() ? 1 : 0) | (property.required() ? 2 : 0)));
        out.putVarInt(type.roles.size());
        type.roles.forEach(out::putString);
        out.putVarInt(type.jsonIndexes.size());
        type.jsonIndexes.forEach(index -> out.putString(index.path()).putByte(index.type().ordinal()));
    }

    private static TypeDef read(ByteCursor in) {
        int id = in.getVarInt();
        String name = in.getString();
        AtomKind kind = AtomKind.values()[in.getUnsignedByte()];
        int version = in.getVarInt();
        List<PropertyDef> properties = new ArrayList<>();
        for (int i = in.getVarInt(); i > 0; i--) {
            String property = in.getString();
            TypeTag tag = TypeTag.values()[in.getUnsignedByte()];
            int flags = in.getUnsignedByte();
            properties.add(new PropertyDef(property, tag, (flags & 1) != 0, (flags & 2) != 0));
        }
        List<String> roles = new ArrayList<>();
        for (int i = in.getVarInt(); i > 0; i--) {
            roles.add(in.getString());
        }
        List<JsonIndex> indexes = new ArrayList<>();
        for (int i = in.getVarInt(); i > 0; i--) {
            indexes.add(new JsonIndex(in.getString(), TypeTag.values()[in.getUnsignedByte()]));
        }
        return new TypeDef(id, name, kind, properties, roles, indexes, version);
    }
}

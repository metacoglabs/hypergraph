package io.hstore.db.property;

import io.hstore.db.value.Value;
import io.hstore.db.value.Values;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.ValueCodec;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;

public record PropertyBag(SortedMap<Integer, Property> entries) {

    public record Property(Value value, long validFrom, long validTo) {
        public static Property of(Value value) {
            return new Property(value, Long.MIN_VALUE, Long.MAX_VALUE);
        }

        public boolean validAt(long instant) {
            return validFrom <= instant && instant < validTo;
        }
    }

    public static final PropertyBag EMPTY = new PropertyBag(new TreeMap<>());

    public PropertyBag {
        entries = Collections.unmodifiableSortedMap(new TreeMap<>(entries));
    }

    public Optional<Property> get(int key) {
        return Optional.ofNullable(entries.get(key));
    }

    public PropertyBag with(int key, Property property) {
        TreeMap<Integer, Property> next = new TreeMap<>(entries);
        next.put(key, property);
        return new PropertyBag(next);
    }

    public PropertyBag without(int key) {
        if (!entries.containsKey(key)) {
            return this;
        }
        TreeMap<Integer, Property> next = new TreeMap<>(entries);
        next.remove(key);
        return new PropertyBag(next);
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public long hash() {
        long hash = 0;
        for (Map.Entry<Integer, Property> entry : entries.entrySet()) {
            Property property = entry.getValue();
            hash = Hashing.combine(hash, Hashing.of(entry.getKey(), Values.hash(property.value()), property.validFrom(), property.validTo()));
        }
        return hash;
    }

    static final ValueCodec<PropertyBag> CODEC = ValueCodec.rows(PropertyBag::encodedSize, PropertyBag::write, PropertyBag::read);

    private static int encodedSize(PropertyBag bag) {
        int size = 5;
        for (Map.Entry<Integer, Property> entry : bag.entries.entrySet()) {
            Property property = entry.getValue();
            size += 5 + Values.maxSize(property.value()) + ByteCursor.signedVarLongSize(property.validFrom())
                    + ByteCursor.signedVarLongSize(property.validTo());
        }
        return size;
    }

    private static void write(ByteCursor out, PropertyBag bag) {
        out.putVarInt(bag.entries.size());
        bag.entries.forEach((key, property) -> {
            out.putVarInt(key);
            Values.write(out, property.value());
            out.putSignedVarLong(property.validFrom()).putSignedVarLong(property.validTo());
        });
    }

    private static PropertyBag read(ByteCursor in) {
        TreeMap<Integer, Property> entries = new TreeMap<>();
        for (int count = in.getVarInt(); count > 0; count--) {
            int key = in.getVarInt();
            entries.put(key, new Property(Values.read(in), in.getSignedVarLong(), in.getSignedVarLong()));
        }
        return new PropertyBag(entries);
    }
}

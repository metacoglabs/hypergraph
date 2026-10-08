package io.hstore.db.property;

import io.hstore.db.value.TypeTag;
import io.hstore.db.value.Value;
import io.hstore.db.value.Values;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.index.PostingIndex;
import io.hstore.engine.index.Postings;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;

import java.util.List;

public final class PropertySlots {

    public record IndexRoot(Ref root) {
    }

    public static final ValueCodec<Value> VALUE_CODEC = ValueCodec.rows(Values::maxSize, Values::write, Values::read);

    public static final PostingIndex<Value> VALUE_POSTINGS =
            PostingIndex.of(67, 68, "property-values", VALUE_CODEC, Values::hash, 24);

    public static final TreeSchema<PropertyBag> BAG_SCHEMA =
            new TreeSchema<>(66, "properties", FingerprintMode.SET, PropertyBag.CODEC, EntryMeasure.keyed(PropertyBag::hash));

    public static final TreeSchema<IndexRoot> DIRECTORY_SCHEMA = new TreeSchema<>(69, "property-index", FingerprintMode.SET,
            new ValueCodec<>() {
                @Override
                public int maxSize(IndexRoot value) {
                    return Ref.encodedSize(value.root());
                }

                @Override
                public void encode(ByteCursor out, long[] keys, List<IndexRoot> values) {
                    values.forEach(value -> Ref.write(out, value.root()));
                }

                @Override
                public void decode(ByteCursor in, long[] keys, Object[] into) {
                    for (int i = 0; i < into.length; i++) {
                        into[i] = new IndexRoot(Ref.read(in));
                    }
                }

                @Override
                public boolean holdsRefs() {
                    return true;
                }

                @Override
                public IndexRoot mapRefs(IndexRoot value, RefMapper mapper) {
                    Ref mapped = mapper.map(value.root(), VALUE_POSTINGS.schema());
                    return mapped == value.root() ? value : new IndexRoot(mapped);
                }

                @Override
                public void forEachRef(IndexRoot value, RefVisitor visitor) {
                    visitor.visit(value.root(), VALUE_POSTINGS.schema());
                }
            },
            EntryMeasure.keyed(root -> root.root().summary().fingerprint()));

    public static final Slot<PropertyBag> PROPERTIES = Slot.primary(34, "properties", BAG_SCHEMA);
    public static final Slot<IndexRoot> PROPERTY_INDEX = Slot.derived(35, "property-index", DIRECTORY_SCHEMA);

    public static final List<Slot<?>> ALL = List.of(PROPERTIES, PROPERTY_INDEX);

    private PropertySlots() {
    }

    public static long directoryKey(int tenant, int propertyKey, TypeTag tag) {
        return ((long) tenant << 40) | ((long) propertyKey << 8) | tag.ordinal();
    }

    public static TreeSchema<Postings<Value>> postingSchema() {
        return VALUE_POSTINGS.schema();
    }
}

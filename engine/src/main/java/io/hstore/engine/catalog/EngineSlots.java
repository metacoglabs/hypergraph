package io.hstore.engine.catalog;

import io.hstore.engine.index.IndexValues.CanonicalName;
import io.hstore.engine.index.IndexValues.Incident;
import io.hstore.engine.index.IndexValues.Marker;
import io.hstore.engine.index.IndexValues;
import io.hstore.engine.index.PostingIndex;
import io.hstore.engine.index.Postings;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;

import java.util.List;

public final class EngineSlots {

    public record RequestRecord(String requestId, long txnId, long generation) {
    }

    public static final int FIRST_EXTENSION_SLOT = 32;
    public static final int FIRST_EXTENSION_SCHEMA = 64;

    public static final TreeSchema<AtomRecord> ATOM_SCHEMA =
            new TreeSchema<>(1, "atoms", FingerprintMode.SET, AtomCodec.INSTANCE, EntryMeasure.keyed(AtomRecord::contentHash));

    public static final PostingIndex<Incident> REVERSE_INDEX =
            PostingIndex.of(5, 6, "reverse", IndexValues.INCIDENT, Incident::hash, 48);

    public static final PostingIndex<Marker> TYPE_INDEX =
            PostingIndex.of(7, 8, "type-index", IndexValues.MARKER, _ -> 1, 32);

    public static final PostingIndex<CanonicalName> CANONICAL_INDEX =
            PostingIndex.of(9, 10, "canonical", IndexValues.CANONICAL_NAME, CanonicalName::hash, 8);

    public static final TreeSchema<Symbol> SYMBOL_SCHEMA =
            new TreeSchema<>(11, "symbols", FingerprintMode.SET, Symbol.CODEC, EntryMeasure.keyed(Symbol::hash));

    public static final PostingIndex<Marker> SYMBOL_INDEX =
            PostingIndex.of(12, 13, "symbol-lookup", IndexValues.MARKER, _ -> 1, 8);

    public static final TreeSchema<RequestRecord> REQUEST_SCHEMA = new TreeSchema<>(14, "requests", FingerprintMode.SET,
            ValueCodec.rows(request -> ByteCursor.stringSize(request.requestId()) + 20,
                    (out, request) -> out.putString(request.requestId()).putVarLong(request.txnId()).putVarLong(request.generation()),
                    in -> new RequestRecord(in.getString(), in.getVarLong(), in.getVarLong())),
            EntryMeasure.keyed(request -> Hashing.of(request.requestId())));

    public static final Slot<AtomRecord> CATALOG = Slot.primary(1, "catalog", ATOM_SCHEMA);
    public static final Slot<Postings<Incident>> REVERSE = Slot.derived(2, "reverse", REVERSE_INDEX.schema());
    public static final Slot<Postings<Marker>> TYPES = Slot.derived(3, "type-index", TYPE_INDEX.schema());
    public static final Slot<Postings<CanonicalName>> CANONICAL = Slot.derived(4, "canonical", CANONICAL_INDEX.schema());
    public static final Slot<Symbol> SYMBOLS = Slot.primary(5, "symbols", SYMBOL_SCHEMA);
    public static final Slot<Postings<Marker>> SYMBOL_LOOKUP = Slot.derived(6, "symbol-lookup", SYMBOL_INDEX.schema());
    public static final Slot<RequestRecord> REQUESTS = Slot.primary(7, "requests", REQUEST_SCHEMA);

    public static final List<Slot<?>> ALL = List.of(CATALOG, REVERSE, TYPES, CANONICAL, SYMBOLS, SYMBOL_LOOKUP, REQUESTS);

    private EngineSlots() {
    }

    public static long canonicalKey(int tenant, int type, String key) {
        return Hashing.of(tenant, type, Hashing.of(key));
    }

    public static long typeKey(int tenant, int type) {
        return ((long) tenant << 32) | Integer.toUnsignedLong(type);
    }
}

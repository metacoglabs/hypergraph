// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.catalog.SlotChange;
import io.hstore.engine.index.IndexValues;
import io.hstore.engine.index.IndexValues.Marker;
import io.hstore.engine.index.PostingIndex;
import io.hstore.engine.index.Postings;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;
import io.hstore.engine.txn.Derivation;
import io.hstore.engine.txn.View;
import io.hstore.engine.txn.Workspace;

import java.util.List;
import java.util.regex.Pattern;

public record VectorRecord(long id, long owner, String view, int model, int version, int dimensions, long payload, int tenant, boolean indexed) {

    public static final int MAX_VIEW = 64;
    private static final Pattern VIEW_NAME = Pattern.compile("[A-Za-z0-9_.:/-]{1," + MAX_VIEW + "}");

    public record Hit(long vector, long owner, String view, float distance) {
    }

    public static String requireView(String view) {
        if (!VIEW_NAME.matcher(view).matches()) {
            throw HStoreException.invalid("a vector view is 1 to " + MAX_VIEW + " letters, digits or _ . : / -, not '" + view + "'");
        }
        return view;
    }

    public static long key(long owner, String view, int model) {
        return Hashing.of(owner, Hashing.of(view), model, 0x56454354L);
    }

    public static long bytes(VectorRecord record) {
        return 4L * record.dimensions();
    }

    long hash() {
        return Hashing.of(Hashing.of(id, owner, Hashing.of(view), model, version, dimensions), Hashing.of(payload, tenant, indexed ? 1 : 0));
    }

    static final ValueCodec<VectorRecord> CODEC = ValueCodec.rows(record -> 70 + ByteCursor.stringSize(record.view()),
            (out, record) -> out.putVarLong(record.id()).putVarLong(record.owner()).putString(record.view()).putVarInt(record.model())
                    .putVarInt(record.version()).putVarInt(record.dimensions()).putVarLong(record.payload()).putVarInt(record.tenant())
                    .putByte(record.indexed() ? 1 : 0),
            in -> new VectorRecord(in.getVarLong(), in.getVarLong(), in.getString(), in.getVarInt(), in.getVarInt(), in.getVarInt(),
                    in.getVarLong(), in.getVarInt(), in.getByte() != 0));

    static final PostingIndex<Marker> OWNERS = PostingIndex.of(122, 123, "owner-vectors", IndexValues.MARKER, _ -> 1, 16);

    public static final Slot<VectorRecord> VECTORS = Slot.primary(60, "vectors",
            new TreeSchema<>(121, "vectors", FingerprintMode.SET, CODEC, EntryMeasure.keyed(VectorRecord::hash)));
    public static final Slot<Postings<Marker>> OWNER_VECTORS = Slot.derived(61, "owner-vectors", OWNERS.schema());
    public static final List<Slot<?>> SLOTS = List.of(VECTORS, OWNER_VECTORS);

    public static final Derivation INDEXING = new Derivation() {
        @Override
        public void onSlot(SlotChange<?> change, Workspace workspace) {
            if (change.slot() != VECTORS) {
                return;
            }
            change.before().map(VectorRecord.class::cast).ifPresent(record ->
                    workspace.replace(OWNER_VECTORS, OWNERS.remove(workspace.tree(OWNER_VECTORS), workspace.scope(), record.owner(), record.id())));
            change.after().map(VectorRecord.class::cast).ifPresent(record ->
                    workspace.replace(OWNER_VECTORS, OWNERS.add(workspace.tree(OWNER_VECTORS), workspace.scope(), record.owner(), record.id(), Marker.PRESENT)));
        }
    };

    public static List<VectorRecord> ofOwner(View view, long owner) {
        return OWNERS.stream(view.scan(OWNER_VECTORS), owner)
                .flatMap(entry -> view.get(VECTORS, entry.key()).stream())
                .toList();
    }
}

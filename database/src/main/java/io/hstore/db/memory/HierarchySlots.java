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
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;
import io.hstore.engine.txn.Derivation;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.View;
import io.hstore.engine.txn.Workspace;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

public final class HierarchySlots {

    static final PostingIndex<Marker> CHILD_INDEX = PostingIndex.of(124, 125, "region-children", IndexValues.MARKER, _ -> 1, 16);
    static final PostingIndex<Marker> DOCUMENT_INDEX = PostingIndex.of(126, 127, "document-leaves", IndexValues.MARKER, _ -> 1, 16);
    static final PostingIndex<Marker> REGION_INDEX = PostingIndex.of(128, 129, "hierarchy-regions", IndexValues.MARKER, _ -> 1, 16);

    public static final Slot<Hierarchy> HIERARCHIES = Slot.primary(64, "hierarchies",
            new TreeSchema<>(131, "hierarchies", FingerprintMode.SET, Hierarchy.CODEC, EntryMeasure.keyed(Hierarchy::hash)));
    public static final Slot<Region> REGIONS = Slot.primary(65, "regions",
            new TreeSchema<>(130, "regions", FingerprintMode.SET, Region.CODEC, EntryMeasure.keyed(Region::hash)));
    public static final Slot<Postings<Marker>> CHILDREN = Slot.derived(66, "region-children", CHILD_INDEX.schema());
    public static final Slot<Region.Leaf> LEAVES = Slot.primary(68, "leaf-regions",
            new TreeSchema<>(132, "leaf-regions", FingerprintMode.SET, Region.Leaf.CODEC, EntryMeasure.keyed(Region.Leaf::hash)));
    public static final Slot<Postings<Marker>> DOCUMENT_LEAVES = Slot.derived(69, "document-leaves", DOCUMENT_INDEX.schema());
    public static final Slot<Postings<Marker>> HIERARCHY_REGIONS = Slot.derived(70, "hierarchy-regions", REGION_INDEX.schema());
    public record Chunk(int dimensions, float[] values) {

        long hash() {
            long hash = Hashing.of(dimensions, values.length);
            for (float value : values) {
                hash = Hashing.combine(hash, Float.floatToIntBits(value));
            }
            return hash;
        }

        static final ValueCodec<Chunk> CODEC = ValueCodec.rows(chunk -> 10 + 4 * chunk.values().length,
                (out, chunk) -> {
                    out.putVarInt(chunk.dimensions()).putVarInt(chunk.values().length);
                    for (float value : chunk.values()) {
                        out.putInt(Float.floatToIntBits(value));
                    }
                },
                in -> {
                    int dimensions = in.getVarInt();
                    float[] values = new float[in.getVarInt()];
                    for (int i = 0; i < values.length; i++) {
                        values[i] = Float.intBitsToFloat(in.getInt());
                    }
                    return new Chunk(dimensions, values);
                });
    }

    public static final int CHUNK = 64;
    public static final int MAX_DIMENSIONS = CHUNK * CHUNK;
    public static final int CENTROID = 0;
    public static final int MAX_REFERENCES = 16;

    public static final Slot<Chunk> REGION_VECTORS = Slot.primary(72, "region-vectors",
            new TreeSchema<>(135, "region-vectors", FingerprintMode.SET, Chunk.CODEC, EntryMeasure.keyed(Chunk::hash)));
    public static final List<Slot<?>> SLOTS = List.of(HIERARCHIES, REGIONS, CHILDREN, LEAVES, DOCUMENT_LEAVES, HIERARCHY_REGIONS, REGION_VECTORS);

    static long vectorKey(long region, int slot, int chunk) {
        return (region * (MAX_REFERENCES + 1) + slot) * CHUNK + chunk;
    }

    static void writeVector(Transaction txn, long region, int slot, float[] vector) {
        if (vector.length > MAX_DIMENSIONS) {
            throw HStoreException.invalid("a region vector has at most " + MAX_DIMENSIONS + " dimensions");
        }
        clearVector(txn, region, slot);
        for (int chunk = 0; chunk * CHUNK < vector.length; chunk++) {
            int from = chunk * CHUNK;
            txn.put(REGION_VECTORS, vectorKey(region, slot, chunk), new Chunk(vector.length, Arrays.copyOfRange(vector, from, Math.min(vector.length, from + CHUNK))));
        }
    }

    static Optional<float[]> readVector(View view, long region, int slot) {
        Optional<Chunk> first = view.get(REGION_VECTORS, vectorKey(region, slot, 0));
        if (first.isEmpty()) {
            return Optional.empty();
        }
        float[] vector = new float[first.get().dimensions()];
        for (int chunk = 0; chunk * CHUNK < vector.length; chunk++) {
            Chunk part = chunk == 0 ? first.get() : view.get(REGION_VECTORS, vectorKey(region, slot, chunk)).orElse(null);
            if (part == null) {
                return Optional.empty();
            }
            System.arraycopy(part.values(), 0, vector, chunk * CHUNK, part.values().length);
        }
        return Optional.of(vector);
    }

    static void clearVector(Transaction txn, long region, int slot) {
        txn.get(REGION_VECTORS, vectorKey(region, slot, 0)).ifPresent(first -> {
            for (int chunk = 0; chunk * CHUNK < first.dimensions(); chunk++) {
                txn.delete(REGION_VECTORS, vectorKey(region, slot, chunk));
            }
        });
    }

    static void clearVectors(Transaction txn, long region) {
        for (int slot = 0; slot <= MAX_REFERENCES; slot++) {
            clearVector(txn, region, slot);
        }
    }

    public static final Derivation INDEXING = new Derivation() {
        @Override
        public void onSlot(SlotChange<?> change, Workspace workspace) {
            if (change.slot() == REGIONS) {
                change.before().map(Region.class::cast).ifPresent(region -> {
                    edit(workspace, HIERARCHY_REGIONS, REGION_INDEX, region.hierarchy(), region.id(), false);
                    if (region.parent() != 0) {
                        edit(workspace, CHILDREN, CHILD_INDEX, region.parent(), region.id(), false);
                    }
                });
                change.after().map(Region.class::cast).ifPresent(region -> {
                    edit(workspace, HIERARCHY_REGIONS, REGION_INDEX, region.hierarchy(), region.id(), true);
                    if (region.parent() != 0) {
                        edit(workspace, CHILDREN, CHILD_INDEX, region.parent(), region.id(), true);
                    }
                });
            } else if (change.slot() == LEAVES) {
                change.before().map(Region.Leaf.class::cast).ifPresent(leaf -> {
                    edit(workspace, CHILDREN, CHILD_INDEX, leaf.region(), leaf.section(), false);
                    edit(workspace, DOCUMENT_LEAVES, DOCUMENT_INDEX, documentKey(leaf.hierarchy(), leaf.document()), leaf.section(), false);
                });
                change.after().map(Region.Leaf.class::cast).ifPresent(leaf -> {
                    edit(workspace, CHILDREN, CHILD_INDEX, leaf.region(), leaf.section(), true);
                    edit(workspace, DOCUMENT_LEAVES, DOCUMENT_INDEX, documentKey(leaf.hierarchy(), leaf.document()), leaf.section(), true);
                });
            }
        }
    };

    private static void edit(Workspace workspace, Slot<Postings<Marker>> slot, PostingIndex<Marker> index, long key, long posting, boolean add) {
        workspace.replace(slot, add ? index.add(workspace.tree(slot), workspace.scope(), key, posting, Marker.PRESENT)
                : index.remove(workspace.tree(slot), workspace.scope(), key, posting));
    }

    private HierarchySlots() {
    }

    static long leafKey(long hierarchy, long section) {
        return Hashing.of(hierarchy, section);
    }

    static long documentKey(long hierarchy, long document) {
        return Hashing.of(hierarchy, document, 1);
    }
}

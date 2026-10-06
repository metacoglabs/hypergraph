// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.semantic;

import io.hstore.engine.catalog.Slot;
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

public record Embedding(int model, int version, int dimensions, long payloadRef) {

    private static final ValueLayout.OfFloat FLOAT = ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    public static final TreeSchema<Embedding> SCHEMA = new TreeSchema<>(72, "embeddings", FingerprintMode.SET,
            ValueCodec.rows(embedding -> 25,
                    (out, embedding) -> out.putVarInt(embedding.model()).putVarInt(embedding.version())
                            .putVarInt(embedding.dimensions()).putVarLong(embedding.payloadRef()),
                    in -> new Embedding(in.getVarInt(), in.getVarInt(), in.getVarInt(), in.getVarLong())),
            EntryMeasure.keyed(embedding -> Hashing.of(embedding.model(), embedding.version(), embedding.dimensions(), embedding.payloadRef())));

    public static final Slot<Embedding> EMBEDDINGS = Slot.primary(36, "embeddings", SCHEMA);

    public static byte[] encode(float[] vector) {
        byte[] bytes = new byte[vector.length * Float.BYTES];
        MemorySegment segment = MemorySegment.ofArray(bytes);
        for (int i = 0; i < vector.length; i++) {
            segment.setAtIndex(FLOAT, i, vector[i]);
        }
        return bytes;
    }

    public static float[] decode(byte[] bytes) {
        MemorySegment segment = MemorySegment.ofArray(bytes);
        float[] vector = new float[bytes.length / Float.BYTES];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = segment.getAtIndex(FLOAT, i);
        }
        return vector;
    }

    public static float[] normalized(float[] vector) {
        double norm = 0;
        for (float component : vector) {
            norm += component * component;
        }
        float scale = norm == 0 ? 0 : (float) (1 / Math.sqrt(norm));
        float[] unit = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            unit[i] = vector[i] * scale;
        }
        return unit;
    }

    public static float cosineDistance(float[] a, float[] b) {
        float dot = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
        }
        return 1 - dot;
    }
}

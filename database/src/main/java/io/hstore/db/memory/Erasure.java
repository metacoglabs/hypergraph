// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.ValueCodec;

public record Erasure(int tenant, long bytes) {

    long hash() {
        return Hashing.of(tenant, bytes);
    }

    static final ValueCodec<Erasure> CODEC = ValueCodec.rows(_ -> 15,
            (out, erasure) -> out.putVarInt(erasure.tenant()).putVarLong(erasure.bytes()),
            in -> new Erasure(in.getVarInt(), in.getVarLong()));
}

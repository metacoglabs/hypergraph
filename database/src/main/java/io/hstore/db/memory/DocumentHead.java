// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.ValueCodec;

public record DocumentHead(int revisions, long latest, int tenant) {

    long hash() {
        return Hashing.of(revisions, latest, tenant);
    }

    static final ValueCodec<DocumentHead> CODEC = ValueCodec.rows(_ -> 20,
            (out, head) -> out.putVarInt(head.revisions()).putVarLong(head.latest()).putVarInt(head.tenant()),
            in -> new DocumentHead(in.getVarInt(), in.getVarLong(), in.getVarInt()));
}

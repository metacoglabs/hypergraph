// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.ValueCodec;

public record Region(long id, long hierarchy, long parent, int level, long count, int tenant) {

    Region withParent(long newParent) {
        return new Region(id, hierarchy, newParent, level, count, tenant);
    }

    Region withCount(long newCount) {
        return new Region(id, hierarchy, parent, level, newCount, tenant);
    }

    long hash() {
        return Hashing.of(id, hierarchy, parent, level, count, tenant);
    }

    static final ValueCodec<Region> CODEC = ValueCodec.rows(_ -> 50,
            (out, region) -> out.putVarLong(region.id()).putVarLong(region.hierarchy()).putVarLong(region.parent()).putVarInt(region.level())
                    .putVarLong(region.count()).putVarInt(region.tenant()),
            in -> new Region(in.getVarLong(), in.getVarLong(), in.getVarLong(), in.getVarInt(), in.getVarLong(), in.getVarInt()));

    public record Leaf(long hierarchy, long section, long document, long region, long vector, long payload) {

        long hash() {
            return Hashing.of(hierarchy, section, document, region, vector, payload);
        }

        static final ValueCodec<Leaf> CODEC = ValueCodec.rows(_ -> 60,
                (out, leaf) -> out.putVarLong(leaf.hierarchy()).putVarLong(leaf.section()).putVarLong(leaf.document()).putVarLong(leaf.region())
                        .putVarLong(leaf.vector()).putVarLong(leaf.payload()),
                in -> new Leaf(in.getVarLong(), in.getVarLong(), in.getVarLong(), in.getVarLong(), in.getVarLong(), in.getVarLong()));
    }
}

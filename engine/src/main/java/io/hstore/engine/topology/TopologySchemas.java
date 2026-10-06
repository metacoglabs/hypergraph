// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.topology;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;

public final class TopologySchemas {

    private static final EntryMeasure<Incidence> INCIDENCE_MEASURE = (key, incidence, into) -> into
            .entry(key, incidence.contentHash())
            .weight(incidence.weight())
            .validity(incidence.validFrom(), incidence.validTo())
            .roles(Incidence.roleBit(incidence.roleSet()));

    public static final TreeSchema<Incidence> SET_MEMBERS =
            new TreeSchema<>(2, "set-members", FingerprintMode.SET, IncidenceCodec.KEYED, INCIDENCE_MEASURE);

    public static final TreeSchema<Incidence> ORDERED_MEMBERS =
            new TreeSchema<>(3, "ordered-members", FingerprintMode.SEQUENCE, IncidenceCodec.SEQUENCED, INCIDENCE_MEASURE);

    public static final ValueCodec<Long> LONG_CODEC =
            ValueCodec.rows(ByteCursor::signedVarLongSize, ByteCursor::putSignedVarLong, ByteCursor::getSignedVarLong);

    public static final TreeSchema<Long> ORDER_INDEX =
            new TreeSchema<>(4, "order-index", FingerprintMode.SET, LONG_CODEC, EntryMeasure.keyed(Hashing::mix));

    private TopologySchemas() {
    }

    public static TreeSchema<Incidence> members(EdgeKind kind) {
        return switch (kind) {
            case SET -> SET_MEMBERS;
            case ORDERED -> ORDERED_MEMBERS;
        };
    }
}

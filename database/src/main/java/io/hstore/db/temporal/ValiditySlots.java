// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.temporal;

import io.hstore.db.property.PropertySlots;
import io.hstore.db.property.PropertySlots.IndexRoot;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.index.PostingIndex;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;

import java.util.List;

public final class ValiditySlots {

    public static final int VALID_FROM = 0;
    public static final int VALID_TO = 1;

    static final ValueCodec<Long> COUNT = ValueCodec.rows(ByteCursor::varLongSize, ByteCursor::putVarLong, ByteCursor::getVarLong);
    static final PostingIndex<Long> ENDPOINTS = PostingIndex.of(97, 98, "validity-instants", COUNT, Hashing::of, 24);
    static final TreeSchema<IndexRoot> DIRECTORY_SCHEMA = PropertySlots.directorySchema(96, "validity-endpoints", ENDPOINTS.schema());

    public static final Slot<IndexRoot> ENDPOINT_INDEX = Slot.derived(53, "validity-endpoints", DIRECTORY_SCHEMA);
    public static final List<Slot<?>> SLOTS = List.of(ENDPOINT_INDEX);

    private ValiditySlots() {
    }

    public static long endpointKey(int tenant, int edgeType, int side) {
        return ((long) tenant << 40) | ((long) edgeType << 8) | side;
    }
}

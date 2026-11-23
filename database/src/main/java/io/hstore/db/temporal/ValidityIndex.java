// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.temporal;

import io.hstore.db.property.PropertySlots.IndexRoot;
import io.hstore.engine.index.Postings;
import io.hstore.engine.tree.Entry;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.txn.View;

import java.util.stream.LongStream;

public final class ValidityIndex {

    private ValidityIndex() {
    }

    public static LongStream edges(View view, int tenant, int edgeType, int side, long lowExclusive, long highInclusive) {
        if (lowExclusive >= highInclusive) {
            return LongStream.empty();
        }
        Tree<Postings<Long>> instants = instants(view, tenant, edgeType, side, lowExclusive + 1, highInclusive);
        return instants.range(lowExclusive + 1, highInclusive)
                .flatMapToLong(bucket -> ValiditySlots.ENDPOINTS.stream(instants, bucket.key()).mapToLong(Entry::key))
                .sorted()
                .distinct();
    }

    public static long count(View view, int tenant, int edgeType, int side, long lowExclusive, long highInclusive) {
        if (lowExclusive >= highInclusive) {
            return 0;
        }
        return ValiditySlots.ENDPOINTS.countRange(instants(view, tenant, edgeType, side, lowExclusive + 1, highInclusive),
                lowExclusive + 1, highInclusive);
    }

    private static Tree<Postings<Long>> instants(View view, int tenant, int edgeType, int side, long low, long high) {
        Ref root = view.nested(ValiditySlots.ENDPOINT_INDEX, ValiditySlots.endpointKey(tenant, edgeType, side), low, high)
                .map(IndexRoot::root).orElse(Ref.EMPTY);
        return new Tree<>(ValiditySlots.ENDPOINTS.schema(), view.source(), root);
    }
}

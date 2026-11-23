// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import io.hstore.engine.catalog.Slot;
import io.hstore.engine.index.PostingIndex;
import io.hstore.engine.index.Postings;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.ValueCodec;

import java.util.List;

public final class NestingSlots {

    public record ChildLink(int roleSet, long weight, long validFrom, long validTo, long locator) {
        public static ChildLink of(Incidence incidence, long locator) {
            return new ChildLink(incidence.roleSet(), incidence.weight(), incidence.validFrom(), incidence.validTo(), locator);
        }

        public long hash() {
            return Hashing.of(roleSet, weight, validFrom, validTo, locator);
        }

        public boolean validAt(long instant) {
            return validFrom <= instant && instant < validTo;
        }
    }

    static final ValueCodec<ChildLink> LINK_CODEC = ValueCodec.rows(
            link -> ByteCursor.varLongSize(link.roleSet()) + ByteCursor.signedVarLongSize(link.weight())
                    + ByteCursor.signedVarLongSize(link.validFrom()) + ByteCursor.signedVarLongSize(link.validTo())
                    + ByteCursor.signedVarLongSize(link.locator()),
            (out, link) -> out.putVarInt(link.roleSet()).putSignedVarLong(link.weight()).putSignedVarLong(link.validFrom())
                    .putSignedVarLong(link.validTo()).putSignedVarLong(link.locator()),
            in -> new ChildLink(in.getVarInt(), in.getSignedVarLong(), in.getSignedVarLong(), in.getSignedVarLong(), in.getSignedVarLong()));

    public static final PostingIndex<ChildLink> CHILD_INDEX = PostingIndex.of(104, 105, "child-edges", LINK_CODEC, ChildLink::hash, 24,
            (_, link, into) -> into.weight(link.weight()).validity(link.validFrom(), link.validTo()).roles(Incidence.roleBit(link.roleSet())));

    public static final Slot<Postings<ChildLink>> CHILDREN = Slot.derived(56, "child-edges", CHILD_INDEX.schema());
    public static final List<Slot<?>> SLOTS = List.of(CHILDREN);

    private NestingSlots() {
    }
}

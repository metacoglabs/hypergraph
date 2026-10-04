package io.hstore.engine.txn;

import io.hstore.engine.catalog.SlotChange;
import io.hstore.engine.topology.MemberChange;

public interface Derivation {

    default void onMember(MemberChange change, Workspace workspace) {
    }

    default void onSlot(SlotChange<?> change, Workspace workspace) {
    }
}

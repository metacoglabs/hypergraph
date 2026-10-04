package io.hstore.engine.feed;

import io.hstore.engine.catalog.SlotChange;
import io.hstore.engine.topology.MemberChange;

import java.util.List;

public record CommitEvent(long generation, long txnId, long wallTime, int branch,
                          List<MemberChange> members, List<SlotChange<?>> slots) {

    public CommitEvent {
        members = List.copyOf(members);
        slots = List.copyOf(slots);
    }

    public boolean isEmpty() {
        return members.isEmpty() && slots.isEmpty();
    }
}

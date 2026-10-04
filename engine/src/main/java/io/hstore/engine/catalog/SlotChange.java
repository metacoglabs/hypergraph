package io.hstore.engine.catalog;

import java.util.Optional;

public record SlotChange<V>(Slot<V> slot, long key, Optional<V> before, Optional<V> after) {

    public boolean created() {
        return before.isEmpty() && after.isPresent();
    }

    public boolean deleted() {
        return before.isPresent() && after.isEmpty();
    }
}

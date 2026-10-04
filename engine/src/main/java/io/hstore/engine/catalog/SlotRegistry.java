package io.hstore.engine.catalog;

import io.hstore.engine.HStoreException;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SlotRegistry {

    private final Map<Integer, Slot<?>> slots = new TreeMap<>();

    public SlotRegistry(Collection<Slot<?>> extensions) {
        EngineSlots.ALL.forEach(this::register);
        extensions.forEach(this::register);
    }

    private void register(Slot<?> slot) {
        Slot<?> previous = slots.putIfAbsent(slot.id(), slot);
        if (previous != null && previous != slot) {
            throw HStoreException.invalid("slot id " + slot.id() + " claimed by both " + previous + " and " + slot);
        }
    }

    public Slot<?> slot(int id) {
        Slot<?> slot = slots.get(id);
        if (slot == null) {
            throw HStoreException.invalid("no slot registered with id " + id);
        }
        return slot;
    }

    public List<Slot<?>> all() {
        return List.copyOf(slots.values());
    }
}

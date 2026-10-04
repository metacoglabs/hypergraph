package io.hstore.engine.catalog;

import io.hstore.engine.tree.Ref;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;

public record RootVector(Map<Integer, Ref> roots) {

    public static final RootVector EMPTY = new RootVector(Map.of());

    public RootVector {
        roots = Map.copyOf(roots);
    }

    public Ref get(int slot) {
        return roots.getOrDefault(slot, Ref.EMPTY);
    }

    public Ref get(Slot<?> slot) {
        return get(slot.id());
    }

    public RootVector with(int slot, Ref ref) {
        Map<Integer, Ref> next = new TreeMap<>(roots);
        if (ref instanceof Ref.Empty) {
            next.remove(slot);
        } else {
            next.put(slot, ref);
        }
        return new RootVector(next);
    }

    public RootVector map(BiFunction<Integer, Ref, Ref> mapper) {
        Map<Integer, Ref> next = new TreeMap<>();
        roots.forEach((slot, ref) -> {
            Ref mapped = mapper.apply(slot, ref);
            if (!(mapped instanceof Ref.Empty)) {
                next.put(slot, mapped);
            }
        });
        return new RootVector(next);
    }

    public boolean sameAs(RootVector other) {
        if (this == other) {
            return true;
        }
        if (!roots.keySet().equals(other.roots.keySet())) {
            return false;
        }
        return roots.entrySet().stream().allMatch(entry -> Ref.same(entry.getValue(), other.get(entry.getKey())));
    }
}

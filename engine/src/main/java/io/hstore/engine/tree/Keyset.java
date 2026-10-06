// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

import java.util.List;
import java.util.Optional;

public record Keyset(boolean descending, boolean started, long lastKey) {

    public static Keyset ascending() {
        return new Keyset(false, false, 0);
    }

    public static Keyset after(long key) {
        return new Keyset(false, true, key);
    }

    Keyset advancedTo(long key) {
        return new Keyset(descending, true, key);
    }

    public record Slice<V>(List<Entry<V>> entries, Optional<Keyset> next) {
    }
}

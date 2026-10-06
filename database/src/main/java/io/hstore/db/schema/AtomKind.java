// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.schema;

import io.hstore.engine.topology.EdgeKind;

import java.util.Optional;

public enum AtomKind {
    NODE,
    SET_EDGE,
    ORDERED_EDGE;

    public Optional<EdgeKind> edgeKind() {
        return switch (this) {
            case NODE -> Optional.empty();
            case SET_EDGE -> Optional.of(EdgeKind.SET);
            case ORDERED_EDGE -> Optional.of(EdgeKind.ORDERED);
        };
    }

    public boolean isEdge() {
        return this != NODE;
    }
}

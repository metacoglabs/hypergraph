// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import io.hstore.db.hora.NestingSlots.ChildLink;
import io.hstore.engine.tree.Entry;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.txn.View;

import java.util.stream.Stream;

public final class Nesting {

    private Nesting() {
    }

    public static Stream<Entry<ChildLink>> children(View view, long parent) {
        return tree(view, parent).stream();
    }

    public static Tree<ChildLink> tree(View view, long parent) {
        view.get(NestingSlots.CHILDREN, parent);
        try (View.Unrecorded _ = view.unrecorded()) {
            return NestingSlots.CHILD_INDEX.tree(view.scan(NestingSlots.CHILDREN), parent);
        }
    }
}

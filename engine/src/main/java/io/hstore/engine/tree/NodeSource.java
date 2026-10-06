// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.tree;

public interface NodeSource {

    Node load(long pageId, TreeSchema<?> schema);

    Layout layout();

    static NodeSource ephemeral(int pageSize) {
        Layout layout = Layout.of(pageSize);
        return new NodeSource() {
            @Override
            public Node load(long pageId, TreeSchema<?> schema) {
                throw new IllegalStateException("ephemeral trees have no stored pages");
            }

            @Override
            public Layout layout() {
                return layout;
            }
        };
    }
}

// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.semantic;

import java.util.List;

public interface Reranker {

    Reranker NONE = new Reranker() {
        @Override
        public String name() {
            return "none";
        }

        @Override
        public double[] score(String query, List<String> passages) {
            return new double[passages.size()];
        }
    };

    String name();

    double[] score(String query, List<String> passages);
}

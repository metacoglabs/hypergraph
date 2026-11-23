// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.engine.HStoreException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record HyperQuery(List<Vertex> vertices, List<Edge> edges, Map<Integer, Long> anchors) {

    public static final int MAX_EDGES = 64;

    public record Vertex(String name, int label) {
    }

    public record Edge(String name, int edgeType, int[] vertices) {
        public Edge {
            vertices = vertices.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Edge(String otherName, int otherType, int[] otherVertices)
                    && name.equals(otherName) && edgeType == otherType && Arrays.equals(vertices, otherVertices);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * name.hashCode() + edgeType) + Arrays.hashCode(vertices);
        }

        @Override
        public String toString() {
            return name + ":" + edgeType + Arrays.toString(vertices);
        }
    }

    public HyperQuery {
        vertices = List.copyOf(vertices);
        edges = List.copyOf(edges);
        anchors = Map.copyOf(anchors);
        if (edges.isEmpty()) {
            throw HStoreException.invalid("a structural match needs at least one hyperedge");
        }
        if (edges.size() + anchors.size() > MAX_EDGES) {
            throw HStoreException.invalid("a structural match has at most " + MAX_EDGES + " hyperedges and anchors, got " + (edges.size() + anchors.size()));
        }
        BitSet covered = new BitSet(vertices.size());
        for (Edge edge : edges) {
            if (Arrays.stream(edge.vertices()).distinct().count() != edge.vertices().length) {
                throw HStoreException.invalid("hyperedge " + edge.name() + " names a vertex twice");
            }
            Arrays.stream(edge.vertices()).forEach(covered::set);
        }
        if (covered.cardinality() != vertices.size()) {
            throw HStoreException.invalid("every vertex of a structural match must belong to a hyperedge");
        }
        if (!connected(vertices.size(), edges)) {
            throw HStoreException.invalid("a structural match must be connected");
        }
    }

    private static boolean connected(int vertexCount, List<Edge> edges) {
        BitSet reached = new BitSet(vertexCount);
        BitSet used = new BitSet(edges.size());
        Arrays.stream(edges.getFirst().vertices()).forEach(reached::set);
        used.set(0);
        boolean grew = true;
        while (grew) {
            grew = false;
            for (int e = 0; e < edges.size(); e++) {
                if (!used.get(e) && Arrays.stream(edges.get(e).vertices()).anyMatch(reached::get)) {
                    used.set(e);
                    Arrays.stream(edges.get(e).vertices()).forEach(reached::set);
                    grew = true;
                }
            }
        }
        return used.cardinality() == edges.size();
    }

    @Override
    public String toString() {
        return vertices.stream().map(v -> v.name() + ":" + v.label()).collect(Collectors.joining(",", "V[", "]"))
                + edges + (anchors.isEmpty() ? "" : " anchors " + anchors);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private final Map<String, Integer> index = new LinkedHashMap<>();
        private final List<Vertex> vertices = new ArrayList<>();
        private final List<Edge> edges = new ArrayList<>();
        private final Map<Integer, Long> anchors = new LinkedHashMap<>();

        public Builder vertex(String name, int label) {
            if (index.putIfAbsent(name, vertices.size()) != null) {
                throw HStoreException.invalid("vertex " + name + " is declared twice");
            }
            vertices.add(new Vertex(name, label));
            return this;
        }

        public boolean has(String name) {
            return index.containsKey(name);
        }

        public Builder edge(String name, int edgeType, String... members) {
            int[] ids = new int[members.length];
            for (int i = 0; i < members.length; i++) {
                Integer found = index.get(members[i]);
                if (found == null) {
                    throw HStoreException.invalid("unknown vertex " + members[i] + " in hyperedge " + name);
                }
                ids[i] = found;
            }
            if (edges.stream().anyMatch(edge -> edge.name().equals(name)) || index.containsKey(name)) {
                throw HStoreException.invalid("name " + name + " is used twice");
            }
            edges.add(new Edge(name, edgeType, ids));
            return this;
        }

        public Builder anchor(String vertex, long atom) {
            Integer found = index.get(vertex);
            if (found == null) {
                throw HStoreException.invalid("unknown vertex " + vertex);
            }
            if (anchors.putIfAbsent(found, atom) != null) {
                throw HStoreException.invalid("vertex " + vertex + " is anchored twice");
            }
            return this;
        }

        public HyperQuery build() {
            return new HyperQuery(vertices, edges, anchors);
        }
    }
}

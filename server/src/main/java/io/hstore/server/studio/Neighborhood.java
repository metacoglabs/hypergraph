// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server.studio;

import io.hstore.db.Atom;
import io.hstore.db.Member;
import io.hstore.db.Reader;
import io.hstore.db.query.QueryResult;
import io.hstore.db.schema.TypeDef;
import io.hstore.db.value.Json;
import io.hstore.db.value.Value;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;

final class Neighborhood {

    record Limits(int atoms, int membersPerEdge, int incidentPerAtom) {
    }

    private static final List<String> LABEL_PROPERTIES = List.of("name", "title", "label");
    private static final int CONNECT_CARDINALITY = 4_096;

    private final Reader reader;
    private final Limits limits;
    private final Map<Long, Atom> atoms = new LinkedHashMap<>();
    private final Map<Long, List<Member>> members = new HashMap<>();
    private boolean complete = true;

    private Neighborhood(Reader reader, Limits limits) {
        this.reader = reader;
        this.limits = limits;
    }

    static Json.Obj around(Reader reader, LongStream seeds, int depth, boolean connect, Limits limits) {
        Neighborhood graph = new Neighborhood(reader, limits);
        List<Long> frontier = seeds.filter(graph::include).boxed().toList();
        Set<Long> seedSet = Set.copyOf(frontier);
        for (int level = 0; level < depth; level++) {
            List<Long> next = new ArrayList<>();
            for (long atom : frontier) {
                reader.incident(atom).limit(limits.incidentPerAtom()).forEach(incident -> {
                    if (graph.include(incident.edge())) {
                        next.add(incident.edge());
                    }
                });
            }
            frontier = next;
        }
        if (connect) {
            graph.connect(seedSet);
        }
        graph.expandMembers();
        return graph.render();
    }

    static Json.Obj sample(Reader reader, Optional<String> type, Limits limits) {
        if (type.isPresent()) {
            return around(reader, reader.atoms(type.get()).limit(limits.atoms() / 2), 0, true, limits);
        }
        List<TypeDef> types = reader.schema().types(reader.view());
        List<TypeDef> edges = types.stream().filter(candidate -> candidate.kind().isEdge()).toList();
        List<TypeDef> chosen = edges.isEmpty() ? types : edges;
        int perType = Math.max(1, limits.atoms() / 4 / Math.max(1, chosen.size()));
        LongStream seeds = chosen.stream().flatMapToLong(candidate -> reader.atomsOfType(candidate.id()).limit(perType));
        return around(reader, seeds, 0, true, limits);
    }

    private boolean include(long id) {
        if (atoms.containsKey(id)) {
            return false;
        }
        if (atoms.size() >= limits.atoms()) {
            complete = false;
            return false;
        }
        Optional<Atom> atom = reader.atom(id);
        atom.ifPresent(found -> atoms.put(id, found));
        return atom.isPresent();
    }

    private void connect(Set<Long> seeds) {
        for (long seed : seeds) {
            reader.incident(seed).limit(limits.incidentPerAtom()).forEach(incident -> {
                long edge = incident.edge();
                if (atoms.containsKey(edge) || reader.cardinality(edge) > CONNECT_CARDINALITY) {
                    return;
                }
                long shared = reader.memberIds(edge).filter(seeds::contains).limit(2).count();
                if (shared >= 2) {
                    include(edge);
                }
            });
        }
    }

    private void expandMembers() {
        List<Atom> edges = atoms.values().stream().filter(Atom::isEdge).toList();
        for (Atom edge : edges) {
            List<Member> listed = reader.members(edge.id()).limit(limits.membersPerEdge()).toList();
            members.put(edge.id(), listed);
            for (Member member : listed) {
                if (!atoms.containsKey(member.atom())) {
                    reader.atom(member.atom()).ifPresent(found -> atoms.put(member.atom(), found));
                }
            }
        }
    }

    private Json.Obj render() {
        return JsonFields.object()
                .put("generation", reader.generation())
                .put("complete", complete)
                .put("atoms", JsonFields.array(atoms.values().stream().map(this::render)))
                .build();
    }

    private Json render(Atom atom) {
        Map<String, Value> properties = reader.properties(atom.id());
        JsonFields fields = JsonFields.object()
                .put("id", atom.id())
                .put("type", atom.type().name())
                .put("typeId", atom.type().id())
                .put("kind", atom.type().kind().name())
                .put("label", label(atom, properties))
                .put("degree", reader.degree(atom.id()));
        atom.key().ifPresent(key -> fields.put("key", key));
        JsonFields values = JsonFields.object();
        properties.forEach((name, value) -> values.put(name, QueryResult.json(value)));
        fields.put("properties", values.build());
        if (atom.isEdge()) {
            List<Member> listed = members.getOrDefault(atom.id(), List.of());
            fields.put("cardinality", atom.cardinality())
                    .put("truncated", listed.size() < atom.cardinality())
                    .put("members", JsonFields.array(listed.stream().map(member -> JsonFields.object()
                            .put("atom", member.atom())
                            .put("roles", JsonFields.array(member.roles().stream().map(JsonFields::text)))
                            .put("weight", member.weight())
                            .put("position", member.position())
                            .build())));
        }
        return fields.build();
    }

    private String label(Atom atom, Map<String, Value> properties) {
        for (String name : LABEL_PROPERTIES) {
            Value value = properties.get(name);
            if (value != null && !(value instanceof Value.Null)) {
                return reader.text(value);
            }
        }
        return atom.key().orElse(atom.type().name() + " #" + atom.id());
    }
}

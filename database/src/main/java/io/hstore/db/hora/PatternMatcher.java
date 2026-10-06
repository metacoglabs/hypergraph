// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import io.hstore.db.Atom;
import io.hstore.db.Reader;
import io.hstore.db.hora.Pattern.Constraint;
import io.hstore.db.hora.Pattern.Variable;
import io.hstore.db.schema.TypeDef;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeAlgebra;
import io.hstore.engine.txn.IncidentEdge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public final class PatternMatcher {

    private static final long UNKNOWN_DOMAIN = Long.MAX_VALUE / 4;

    private final Reader reader;

    public PatternMatcher(Reader reader) {
        this.reader = reader;
    }

    public List<String> plan(Pattern pattern) {
        Set<String> bound = new LinkedHashSet<>();
        while (bound.size() < pattern.variables().size()) {
            String next = pattern.variables().stream()
                    .map(Variable::name)
                    .filter(name -> !bound.contains(name))
                    .min(Comparator.comparingLong(name -> estimate(pattern, name, bound)))
                    .orElseThrow();
            bound.add(next);
        }
        return List.copyOf(bound);
    }

    public Stream<Map<String, Long>> match(Pattern pattern, Budget budget) {
        Budget.Meter meter = budget.start();
        List<Map<String, Long>> bindings = List.of(Map.of());
        for (String variable : plan(pattern)) {
            List<Map<String, Long>> next = new ArrayList<>();
            for (Map<String, Long> binding : bindings) {
                candidates(pattern, variable, binding).forEach(candidate -> {
                    Map<String, Long> extended = new HashMap<>(binding);
                    extended.put(variable, candidate);
                    if (admissible(pattern, extended, variable)) {
                        meter.consume(1);
                        next.add(extended);
                    }
                });
            }
            if (next.isEmpty()) {
                return Stream.empty();
            }
            bindings = next;
        }
        return bindings.stream().map(Map::copyOf);
    }

    private long estimate(Pattern pattern, String name, Set<String> bound) {
        Variable variable = pattern.variable(name);
        long estimate = typeDomain(variable);
        for (Constraint constraint : pattern.constraints()) {
            long narrowed = switch (constraint) {
                case Constraint.Bound(String target, long _) when target.equals(name) -> 1;
                case Constraint.Contains(String edge, String member) when edge.equals(name) && bound.contains(member) -> 16;
                case Constraint.HasRole(String edge, String member, String _) when edge.equals(name) && bound.contains(member) -> 8;
                case Constraint.Contains(String edge, String member) when member.equals(name) && bound.contains(edge) -> 32;
                case Constraint.HasRole(String edge, String member, String _) when member.equals(name) && bound.contains(edge) -> 16;
                case Constraint.Shares(String left, String right, long _, long _)
                        when (left.equals(name) && bound.contains(right)) || (right.equals(name) && bound.contains(left)) -> 256;
                case Constraint.SubsetOf(String inner, String outer)
                        when (inner.equals(name) && bound.contains(outer)) || (outer.equals(name) && bound.contains(inner)) -> 256;
                default -> UNKNOWN_DOMAIN;
            };
            estimate = Math.min(estimate, narrowed);
        }
        return estimate;
    }

    private long typeDomain(Variable variable) {
        return variable.type().flatMap(reader::type).map(type -> reader.countOfType(type.id())).orElse(UNKNOWN_DOMAIN);
    }

    private LongStream candidates(Pattern pattern, String name, Map<String, Long> binding) {
        Variable variable = pattern.variable(name);
        for (Constraint constraint : pattern.constraints()) {
            if (constraint instanceof Constraint.Bound(String target, long atom) && target.equals(name)) {
                return reader.visible(atom) ? LongStream.of(atom) : LongStream.empty();
            }
        }
        LongStream candidates = variable.edge() ? edgeCandidates(pattern, name, binding) : nodeCandidates(pattern, name, binding);
        Optional<Integer> type = variable.type().flatMap(reader::type).map(TypeDef::id);
        return candidates.filter(atom -> reader.atom(atom).map(found -> type.isEmpty() || found.type().id() == type.get()).orElse(false));
    }

    private LongStream edgeCandidates(Pattern pattern, String name, Map<String, Long> binding) {
        List<Tree<?>> incidentSets = new ArrayList<>();
        Set<Long> related = new HashSet<>();
        boolean relatedConstraint = false;
        for (Constraint constraint : pattern.constraints()) {
            switch (constraint) {
                case Constraint.Contains(String edge, String member) when edge.equals(name) && binding.containsKey(member) ->
                        incidentSets.add(reader.view().incidentTree(binding.get(member)));
                case Constraint.HasRole(String edge, String member, String _) when edge.equals(name) && binding.containsKey(member) ->
                        incidentSets.add(reader.view().incidentTree(binding.get(member)));
                case Constraint.Shares(String left, String right, long _, long _) when left.equals(name) && binding.containsKey(right) -> {
                    relatedConstraint = true;
                    related.addAll(neighbourEdges(binding.get(right)));
                }
                case Constraint.Shares(String left, String right, long _, long _) when right.equals(name) && binding.containsKey(left) -> {
                    relatedConstraint = true;
                    related.addAll(neighbourEdges(binding.get(left)));
                }
                case Constraint.SubsetOf(String inner, String outer) when inner.equals(name) && binding.containsKey(outer) -> {
                    relatedConstraint = true;
                    related.addAll(neighbourEdges(binding.get(outer)));
                }
                case Constraint.SubsetOf(String inner, String outer) when outer.equals(name) && binding.containsKey(inner) -> {
                    relatedConstraint = true;
                    related.addAll(neighbourEdges(binding.get(inner)));
                }
                default -> {
                }
            }
        }
        if (!incidentSets.isEmpty()) {
            LongStream common = TreeAlgebra.intersectKeys(incidentSets);
            return relatedConstraint ? common.filter(related::contains) : common;
        }
        if (relatedConstraint) {
            return related.stream().mapToLong(Long::longValue).sorted();
        }
        return typeScan(pattern.variable(name));
    }

    private Set<Long> neighbourEdges(long edge) {
        Set<Long> edges = new HashSet<>();
        reader.edge(edge).stream().forEach(incidence ->
                reader.view().incident(incidence.member()).map(IncidentEdge::edge).forEach(edges::add));
        return edges;
    }

    private LongStream nodeCandidates(Pattern pattern, String name, Map<String, Long> binding) {
        List<Tree<?>> memberships = pattern.constraints().stream()
                .flatMap(constraint -> switch (constraint) {
                    case Constraint.Contains(String edge, String member) when member.equals(name) && binding.containsKey(edge) ->
                            Stream.of(reader.edge(binding.get(edge)).membership());
                    case Constraint.HasRole(String edge, String member, String _) when member.equals(name) && binding.containsKey(edge) ->
                            Stream.of(reader.edge(binding.get(edge)).membership());
                    default -> Stream.<Tree<?>>empty();
                })
                .toList();
        return memberships.isEmpty() ? typeScan(pattern.variable(name)) : TreeAlgebra.intersectKeys(memberships);
    }

    private LongStream typeScan(Variable variable) {
        return variable.type().flatMap(reader::type)
                .map(type -> reader.atomsOfType(type.id()))
                .orElseGet(() -> reader.allAtoms(variable.edge()).mapToLong(Atom::id));
    }

    private boolean admissible(Pattern pattern, Map<String, Long> binding, String introduced) {
        return pattern.constraints().stream()
                .filter(constraint -> constraint.variables().contains(introduced))
                .filter(constraint -> binding.keySet().containsAll(constraint.variables()))
                .allMatch(constraint -> holds(constraint, binding, pattern));
    }

    private boolean holds(Constraint constraint, Map<String, Long> binding, Pattern pattern) {
        return switch (constraint) {
            case Constraint.Contains(String edge, String member) -> incidence(binding.get(edge), binding.get(member), pattern, edge).isPresent();
            case Constraint.HasRole(String edge, String member, String role) -> incidence(binding.get(edge), binding.get(member), pattern, edge)
                    .map(found -> reader.roles(found.roleSet()).contains(role)).orElse(false);
            case Constraint.Cardinality(String edge, long min, long max) -> {
                long cardinality = reader.cardinality(binding.get(edge));
                yield cardinality >= min && cardinality <= max;
            }
            case Constraint.Shares(String left, String right, long min, long max) -> {
                long shared = TreeAlgebra.countIntersect(membership(binding.get(left)), membership(binding.get(right)));
                yield shared >= min && shared <= max;
            }
            case Constraint.SubsetOf(String inner, String outer) ->
                    TreeAlgebra.subset(membership(binding.get(inner)), membership(binding.get(outer)));
            case Constraint.Distinct(String left, String right) -> !binding.get(left).equals(binding.get(right));
            case Constraint.Bound(String variable, long atom) -> binding.get(variable) == atom;
            case Constraint.ValidAt(String edge, long instant) -> reader.edge(binding.get(edge)).validAt(instant).findAny().isPresent();
        };
    }

    private Optional<Incidence> incidence(long edge, long member, Pattern pattern, String edgeVariable) {
        Optional<Incidence> found = reader.view().edge(edge).flatMap(hyperedge -> hyperedge.get(member));
        OptionalLong instant = validAt(pattern, edgeVariable);
        return instant.isPresent() ? found.filter(incidence -> incidence.validAt(instant.getAsLong())) : found;
    }

    private static OptionalLong validAt(Pattern pattern, String edgeVariable) {
        return pattern.constraints().stream()
                .filter(constraint -> constraint instanceof Constraint.ValidAt(String edge, long _) && edge.equals(edgeVariable))
                .mapToLong(constraint -> ((Constraint.ValidAt) constraint).instant())
                .findFirst();
    }

    private Tree<?> membership(long edge) {
        return reader.edge(edge).membership();
    }
}

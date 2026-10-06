// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.query;

import io.hstore.db.Reader;
import io.hstore.db.property.PropertyIndex;
import io.hstore.db.property.PropertySlots;
import io.hstore.db.query.Ast.Comparison;
import io.hstore.db.query.Ast.Expr;
import io.hstore.db.schema.TypeDef;
import io.hstore.db.stats.Statistics;
import io.hstore.db.value.TypeTag;
import io.hstore.db.value.Value;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.EngineSlots;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

final class Planner {

    record Candidate(AccessPath path, List<Expr> sources, double rows, double cost) {
    }

    record Plan(Ast.Match match, Optional<TypeDef> type, Candidate chosen, List<Candidate> alternatives, List<Expr> residual) {
        String describe() {
            StringBuilder out = new StringBuilder();
            out.append(chosen.path().describe())
                    .append("  rows≈").append(Math.round(chosen.rows()))
                    .append("  cost≈").append(Math.round(chosen.cost()));
            residual.forEach(filter -> out.append("\n  -> Verify(").append(Evaluator.describe(filter)).append(")"));
            if (!match.projections().isEmpty()) {
                out.append("\n  -> Project(").append(match.projections().stream().map(Ast.Projection::alias).collect(Collectors.joining(", "))).append(")");
            }
            match.orderBy().ifPresent(order -> out.append("\n  -> Sort(").append(Evaluator.describe(order)).append(match.descending() ? " DESC)" : ")"));
            match.limit().ifPresent(limit -> out.append("\n  -> Limit(").append(limit).append(")"));
            alternatives.stream().filter(alternative -> alternative != chosen).forEach(alternative ->
                    out.append("\n  rejected ").append(alternative.path().describe())
                            .append(" rows≈").append(Math.round(alternative.rows()))
                            .append(" cost≈").append(Math.round(alternative.cost())));
            return out.toString();
        }
    }

    private static final double IO_WEIGHT = 4.0;
    private static final double CPU_WEIGHT = 0.01;
    private static final double VERIFY_WEIGHT = 0.05;
    private static final double ENTRIES_PER_PAGE = 128;
    private static final double TREE_DEPTH = 3;
    private static final int MAX_INTERSECTED = 4;

    private final Reader reader;
    private final Statistics statistics;
    private final ToLongFunction<Ast.Ref> resolver;

    Planner(Reader reader, Statistics statistics, ToLongFunction<Ast.Ref> resolver) {
        this.reader = reader;
        this.statistics = statistics;
        this.resolver = resolver;
    }

    Plan plan(Ast.Match match) {
        Optional<TypeDef> type = match.type().map(reader::requireType);
        type.ifPresent(found -> {
            if (found.kind().isEdge() != match.edges()) {
                throw HStoreException.invalid(found.name() + " is not a " + (match.edges() ? "hyperedge" : "node") + " type");
            }
        });
        List<Expr> conjuncts = match.where().map(Planner::conjuncts).orElse(List.of());
        List<Candidate> candidates = new ArrayList<>();
        double catalogSize = reader.view().scan(EngineSlots.CATALOG).size();
        double population = type.map(found -> (double) reader.countOfType(found.id())).orElse(catalogSize);
        Candidate catalogScan = candidate(new AccessPath.AllAtoms(match.edges()), List.of(), catalogSize, conjuncts);
        candidates.add(new Candidate(catalogScan.path(), List.of(), catalogScan.rows(), catalogScan.cost() + VERIFY_WEIGHT * catalogScan.rows()));
        type.ifPresent(found -> candidates.add(candidate(new AccessPath.TypeScan(found), List.of(), population, conjuncts)));
        List<Candidate> exactParts = new ArrayList<>();
        for (Expr conjunct : conjuncts) {
            accessPath(match, type, conjunct).or(() -> union(match, type, conjunct)).ifPresent(path -> {
                List<Expr> consumed = exact(path, conjunct) ? List.of(conjunct) : List.of();
                Candidate single = candidate(path, consumed, estimate(path, catalogSize), conjuncts);
                candidates.add(single);
                if (!consumed.isEmpty() && !(path instanceof AccessPath.Semantic)) {
                    exactParts.add(single);
                }
            });
        }
        exactParts.sort(Comparator.comparingDouble(Candidate::rows));
        for (int size = 2; size <= Math.min(MAX_INTERSECTED, exactParts.size()); size++) {
            candidates.add(intersection(exactParts.subList(0, size), population, conjuncts));
        }
        Candidate chosen = candidates.stream().min(Comparator.comparingDouble(Candidate::cost)).orElseThrow();
        List<Expr> residual = conjuncts.stream()
                .filter(conjunct -> chosen.sources().stream().noneMatch(source -> source == conjunct))
                .sorted(Comparator.comparingInt(Planner::costClass))
                .toList();
        return new Plan(match, type, chosen, List.copyOf(candidates), residual);
    }

    private Optional<AccessPath> union(Ast.Match match, Optional<TypeDef> type, Expr conjunct) {
        if (!(conjunct instanceof Expr.Or(List<Expr> terms))) {
            return Optional.empty();
        }
        List<AccessPath> parts = new ArrayList<>();
        for (Expr term : terms) {
            Optional<AccessPath> part = accessPath(match, type, term);
            if (part.isEmpty() || part.get() instanceof AccessPath.Semantic) {
                return Optional.empty();
            }
            parts.add(part.get());
        }
        return Optional.of(new AccessPath.Union(parts));
    }

    private Candidate intersection(List<Candidate> parts, double population, List<Expr> conjuncts) {
        double rows = parts.getFirst().rows();
        for (Candidate part : parts.subList(1, parts.size())) {
            rows *= Math.min(1.0, part.rows() / Math.max(1.0, population));
        }
        List<Expr> consumed = parts.stream().flatMap(part -> part.sources().stream()).toList();
        AccessPath path = new AccessPath.Intersection(parts.stream().map(Candidate::path).toList());
        double corrected = rows * statistics.correction(path.kind());
        double access = parts.stream().mapToDouble(part -> scanCost(part.path(), part.rows())).sum();
        return new Candidate(path, consumed, corrected, access + VERIFY_WEIGHT * corrected * verificationWeight(conjuncts, consumed));
    }

    private static double verificationWeight(List<Expr> conjuncts, List<Expr> consumed) {
        return conjuncts.stream()
                .filter(conjunct -> consumed.stream().noneMatch(source -> source == conjunct))
                .mapToInt(Planner::costClass)
                .sum();
    }

    private Candidate candidate(AccessPath path, List<Expr> sources, double rows, List<Expr> conjuncts) {
        double corrected = rows * statistics.correction(path.kind());
        return new Candidate(path, sources, corrected,
                scanCost(path, corrected) + VERIFY_WEIGHT * corrected * verificationWeight(conjuncts, sources));
    }

    private double scanCost(AccessPath path, double rows) {
        return switch (path) {
            case AccessPath.Fixed _ -> IO_WEIGHT * TREE_DEPTH + CPU_WEIGHT * rows;
            case AccessPath.Semantic(String _, int k, var _) -> IO_WEIGHT * (2.0 * k + TREE_DEPTH) + CPU_WEIGHT * rows;
            case AccessPath.Union(List<AccessPath> parts) -> parts.stream()
                    .mapToDouble(part -> scanCost(part, estimate(part, reader.view().scan(EngineSlots.CATALOG).size()))).sum();
            case AccessPath.Intersection(List<AccessPath> parts) -> parts.stream()
                    .mapToDouble(part -> scanCost(part, estimate(part, reader.view().scan(EngineSlots.CATALOG).size()))).sum();
            default -> IO_WEIGHT * (rows / ENTRIES_PER_PAGE + TREE_DEPTH) + CPU_WEIGHT * rows;
        };
    }

    private double estimate(AccessPath path, double population) {
        return switch (path) {
            case AccessPath.Incident(List<Long> atoms) -> {
                double[] degrees = atoms.stream().mapToDouble(reader::degree).sorted().toArray();
                double rows = degrees.length == 0 ? 0 : degrees[0];
                for (int i = 1; i < degrees.length; i++) {
                    rows *= Math.min(1.0, degrees[i] / Math.max(1.0, population));
                }
                yield rows;
            }
            case AccessPath.Members(long edge) -> reader.cardinality(edge);
            case AccessPath.Fixed _ -> 1;
            case AccessPath.Semantic(String _, int k, var _) -> k;
            case AccessPath.IndexRange range -> indexEstimate(range);
            case AccessPath.TypeScan(TypeDef type) -> reader.countOfType(type.id());
            case AccessPath.AllAtoms _ -> reader.view().scan(EngineSlots.CATALOG).size();
            case AccessPath.Union(List<AccessPath> parts) -> Math.min(population,
                    parts.stream().mapToDouble(part -> estimate(part, population)).sum());
            case AccessPath.Intersection(List<AccessPath> parts) -> parts.stream().mapToDouble(part -> estimate(part, population)).min().orElse(0);
        };
    }

    private double indexEstimate(AccessPath.IndexRange range) {
        TypeTag tag = Reader.indexedTag(range.type(), range.property()).orElseThrow();
        OptionalInt key = reader.schema().existingPropertyKey(range.property());
        if (key.isEmpty()) {
            return 0;
        }
        return PropertyIndex.estimate(reader.view().scan(PropertySlots.PROPERTY_INDEX), reader.tenant(), key.getAsInt(), tag,
                PropertyIndex.lowerKey(tag, range.low()), PropertyIndex.upperKey(tag, range.high()));
    }

    private Optional<AccessPath> accessPath(Ast.Match match, Optional<TypeDef> type, Expr conjunct) {
        String variable = match.variable();
        return switch (conjunct) {
            case Expr.Contains(String target, List<Ast.Ref> members) when target.equals(variable) && match.edges() ->
                    Optional.of(new AccessPath.Incident(members.stream().map(resolver::applyAsLong).toList()));
            case Expr.Has(String target, Ast.Ref member, Optional<String> _) when target.equals(variable) && match.edges() ->
                    Optional.of(new AccessPath.Incident(List.of(resolver.applyAsLong(member))));
            case Expr.In(String target, Ast.Ref edge) when target.equals(variable) ->
                    Optional.of(new AccessPath.Members(resolver.applyAsLong(edge)));
            case Expr.Similar(String target, String text, int k, var consistency) when target.equals(variable) ->
                    Optional.of(new AccessPath.Semantic(text, k, consistency));
            case Expr.Compare(Comparison op, Expr.Var(String target), Expr.Atom(Ast.Ref ref)) when op == Comparison.EQ && target.equals(variable) ->
                    Optional.of(new AccessPath.Fixed(resolver.applyAsLong(ref)));
            case Expr.Compare(Comparison op, Expr left, Expr.Literal(Value value)) when indexed(left, variable, type).isPresent() ->
                    indexed(left, variable, type).map(property -> range(type.get(), property, op, value));
            case Expr.Compare(Comparison op, Expr.Literal(Value value), Expr right) when indexed(right, variable, type).isPresent() ->
                    indexed(right, variable, type).map(property -> range(type.get(), property, flip(op), value));
            case Expr.Between(Expr operand, Expr.Literal(Value low), Expr.Literal(Value high)) when indexed(operand, variable, type).isPresent() ->
                    indexed(operand, variable, type).map(property ->
                            new AccessPath.IndexRange(type.get(), property, Optional.of(low), Optional.of(high), true, true));
            default -> Optional.empty();
        };
    }

    private static Optional<String> indexed(Expr operand, String variable, Optional<TypeDef> type) {
        return switch (operand) {
            case Expr.Prop(String target, String property) when target.equals(variable) ->
                    type.flatMap(found -> found.property(property)).filter(TypeDef.PropertyDef::indexed).map(TypeDef.PropertyDef::name);
            case Expr.Call(String function, List<Expr> arguments) when function.equalsIgnoreCase("json") && arguments.size() == 2
                    && arguments.get(0) instanceof Expr.Var(String target) && target.equals(variable)
                    && arguments.get(1) instanceof Expr.Literal(Value.Text(String path)) ->
                    type.flatMap(found -> found.jsonIndexes().stream().filter(index -> index.path().equals(path)).findFirst())
                            .map(TypeDef.JsonIndex::path);
            default -> Optional.empty();
        };
    }

    private static AccessPath range(TypeDef type, String property, Comparison op, Value value) {
        return switch (op) {
            case EQ -> new AccessPath.IndexRange(type, property, Optional.of(value), Optional.of(value), true, true);
            case LT -> new AccessPath.IndexRange(type, property, Optional.empty(), Optional.of(value), true, false);
            case LE -> new AccessPath.IndexRange(type, property, Optional.empty(), Optional.of(value), true, true);
            case GT -> new AccessPath.IndexRange(type, property, Optional.of(value), Optional.empty(), false, true);
            case GE -> new AccessPath.IndexRange(type, property, Optional.of(value), Optional.empty(), true, true);
            case NE -> new AccessPath.TypeScan(type);
        };
    }

    private static Comparison flip(Comparison op) {
        return switch (op) {
            case LT -> Comparison.GT;
            case LE -> Comparison.GE;
            case GT -> Comparison.LT;
            case GE -> Comparison.LE;
            case EQ, NE -> op;
        };
    }

    private static boolean exact(AccessPath path, Expr conjunct) {
        return switch (path) {
            case AccessPath.Semantic _, AccessPath.TypeScan _, AccessPath.AllAtoms _ -> false;
            case AccessPath.Incident _ -> !(conjunct instanceof Expr.Has(String _, Ast.Ref _, Optional<String> role) && role.isPresent());
            case AccessPath.Members _, AccessPath.IndexRange _, AccessPath.Fixed _ -> true;
            case AccessPath.Union(List<AccessPath> parts) -> conjunct instanceof Expr.Or(List<Expr> terms) && terms.size() == parts.size()
                    && IntStream.range(0, parts.size()).allMatch(i -> exact(parts.get(i), terms.get(i)));
            case AccessPath.Intersection _ -> false;
        };
    }

    static List<Expr> conjuncts(Expr expr) {
        return expr instanceof Expr.And(List<Expr> terms) ? terms.stream().flatMap(term -> conjuncts(term).stream()).toList() : List.of(expr);
    }

    private static int costClass(Expr expr) {
        return switch (expr) {
            case Expr.Compare _, Expr.Between _ -> 1;
            case Expr.In _, Expr.Has _ -> 2;
            case Expr.Contains _, Expr.ValidAt _ -> 3;
            case Expr.Not(Expr inner) -> costClass(inner);
            case Expr.Similar _ -> 5;
            default -> 4;
        };
    }
}

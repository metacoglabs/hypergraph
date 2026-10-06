// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.query;

import io.hstore.db.Atom;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Member;
import io.hstore.db.MemberSpec;
import io.hstore.db.Reader;
import io.hstore.db.Writer;
import io.hstore.db.evidence.Evidence;
import io.hstore.db.evidence.EvidencePolicy;
import io.hstore.db.evidence.Provenance;
import io.hstore.db.evidence.Qualifier;
import io.hstore.db.hora.Budget;
import io.hstore.db.hora.Field;
import io.hstore.db.hora.Hora;
import io.hstore.db.hora.Pattern;
import io.hstore.db.hora.PatternMatcher;
import io.hstore.db.hora.SwapSampler;
import io.hstore.db.query.Ast.Expr;
import io.hstore.db.query.Ast.MemberClause;
import io.hstore.db.query.Ast.Statement;
import io.hstore.db.query.QueryResult.Cell;
import io.hstore.db.schema.TypeDef;
import io.hstore.db.security.Principal;
import io.hstore.db.security.Quota;
import io.hstore.db.security.Security;
import io.hstore.db.semantic.SemanticPlane;
import io.hstore.db.signal.Signals;
import io.hstore.db.stats.Statistics;
import io.hstore.db.temporal.BranchMerge;
import io.hstore.db.temporal.StateBindings;
import io.hstore.db.temporal.Temporal;
import io.hstore.db.value.Json;
import io.hstore.db.value.Value;
import io.hstore.db.view.MaterializedViews;
import io.hstore.db.view.ViewCell;
import io.hstore.engine.EngineStats;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.page.IoTrace;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.Hyperedge;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeAlgebra;
import io.hstore.engine.tree.WriteScope;
import io.hstore.engine.txn.Snapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

final class Executor {

    private final Session session;

    Executor(Session session) {
        this.session = session;
    }

    private HypergraphDatabase database() {
        return session.database();
    }

    QueryResult run(Statement statement) {
        if (administrative(statement)) {
            session.principal().requireAdmin();
        }
        return switch (statement) {
            case Statement.CreateType type -> write(writer -> {
                TypeDef created = type.kind().isEdge()
                        ? writer.defineEdge(type.name(), type.kind(), type.properties(), type.roles())
                        : writer.defineNode(type.name(), type.properties());
                return QueryResult.message("created " + created.kind() + " type " + created.name() + " #" + created.id());
            });
            case Statement.CreateIndex(String type, String property) -> write(writer -> {
                writer.createIndex(type, property);
                return QueryResult.message("indexed " + type + "." + property);
            });
            case Statement.CreateJsonIndex(String type, String path, var tag) -> write(writer -> {
                writer.createJsonIndex(type, path, tag);
                return QueryResult.message("indexed " + type + " documents at " + path + " as " + tag);
            });
            case Statement.CreateView(String name, MaterializedViews.Kind kind, long parameter, boolean continuous) -> {
                MaterializedViews.Descriptor descriptor = database().views().create(session.principal(), name, kind, continuous
                        ? MaterializedViews.Refresh.CONTINUOUS : MaterializedViews.Refresh.ON_DEMAND, parameter);
                yield QueryResult.message("created view " + descriptor.name() + " at generation " + descriptor.lastGeneration());
            }
            case Statement.RefreshView(String name) -> QueryResult.message("view " + name + " refreshed to generation "
                    + database().views().refresh(session.principal(), name).lastGeneration());
            case Statement.ShowView view -> showView(view);
            case Statement.CreateBranch(String name, Optional<String> from) -> {
                Branch created = database().engine().createBranch(name, from.map(session::branchId).orElse(Branch.MAIN));
                yield QueryResult.message("created branch " + created.name() + " #" + created.id() + " from generation " + created.baseGeneration());
            }
            case Statement.MergeBranch(String source, Optional<String> target, BranchMerge.Policy policy) -> {
                int sourceId = session.branchId(source);
                int targetId = target.map(session::branchId)
                        .orElseGet(() -> database().engine().transactions().current().branch(sourceId).parent());
                BranchMerge.Outcome outcome = BranchMerge.merge(database(), sourceId, targetId, policy);
                yield QueryResult.message("merged " + source + ": " + outcome.atomsCreated() + " atoms created, " + outcome.atomsDeleted()
                        + " deleted, " + outcome.memberChanges() + " membership and " + outcome.propertyChanges() + " property changes"
                        + (outcome.conflicts().isEmpty() ? "" : ", " + outcome.conflicts().size() + " conflicts resolved for " + policy));
            }
            case Statement.CreateTenant(String name, Map<String, Long> quota) -> write(writer -> {
                Security.Tenant tenant = Security.createTenant(writer.transaction(), name, Quota.UNLIMITED.with(quota));
                return QueryResult.message("created tenant " + tenant.name() + " #" + tenant.id());
            });
            case Statement.AlterTenant(String name, Map<String, Long> quota) -> write(writer -> {
                Security.Tenant tenant = Security.requireTenant(writer.view(), name);
                Security.setQuota(writer.transaction(), tenant, tenant.quota().with(quota));
                return QueryResult.message("updated quota of tenant " + name);
            });
            case Statement.CreateUser(String name, String password, Optional<String> tenant, var role) -> write(writer -> {
                int tenantId = tenant.map(found -> Security.requireTenant(writer.view(), found).id()).orElse(Principal.DEFAULT_TENANT);
                Security.createUser(writer.transaction(), name, password, tenantId, role);
                return QueryResult.message("created user " + name + " with role " + role);
            });
            case Statement.AlterUser(String name, String password) -> write(writer -> {
                Security.setPassword(writer.transaction(), name, password);
                return QueryResult.message("changed password of " + name);
            });
            case Statement.DropUser(String name) -> write(writer -> {
                Security.dropUser(writer.transaction(), name);
                return QueryResult.message("dropped user " + name);
            });
            case Statement.ShowTenants _ -> read(reader -> table(List.of("id", "name", "atoms", "edges", "payload bytes", "max atoms", "max edges"),
                    Security.tenants(reader.view()).stream().map(tenant -> {
                        Quota.Usage usage = Security.usage(reader.view(), tenant.id());
                        return List.of(QueryResult.number(tenant.id()), QueryResult.text(tenant.name()), QueryResult.number(usage.atoms()),
                                QueryResult.number(usage.edges()), QueryResult.number(usage.payloadBytes()),
                                limit(tenant.quota().maxAtoms()), limit(tenant.quota().maxEdges()));
                    })));
            case Statement.ShowUsers _ -> read(reader -> table(List.of("user", "tenant", "role"), Security.users(reader.view()).stream()
                    .map(user -> List.of(QueryResult.text(user.name()), QueryResult.number(user.tenant()), QueryResult.text(user.role().name())))));
            case Statement.DropBranch(String name) -> {
                database().engine().dropBranch(session.branchId(name));
                yield QueryResult.message("dropped branch " + name);
            }
            case Statement.InsertNode insert -> write(writer -> {
                long id = writer.node(insert.type(), insert.key().orElse(null), insert.properties());
                insert.bind().ifPresent(name -> session.bind(name, id));
                return inserted(writer, id);
            });
            case Statement.InsertEdge insert -> write(writer -> {
                long id = writer.edge(insert.type(), insert.properties());
                for (MemberClause member : insert.members()) {
                    writer.add(id, session.resolve(writer, member.member()), spec(member));
                }
                insert.bind().ifPresent(name -> session.bind(name, id));
                return inserted(writer, id);
            });
            case Statement.Add(MemberClause member, Ast.Ref edge, OptionalLong at) -> write(writer -> {
                long edgeId = session.resolve(writer, edge);
                long memberId = session.resolve(writer, member.member());
                if (at.isPresent()) {
                    writer.insertAt(edgeId, at.getAsLong(), memberId, spec(member));
                } else if (writer.edge(edgeId).kind() == EdgeKind.ORDERED && !writer.view().contains(edgeId, memberId)) {
                    writer.append(edgeId, memberId, spec(member));
                } else {
                    writer.add(edgeId, memberId, spec(member));
                }
                return QueryResult.message("@" + memberId + " is a member of @" + edgeId);
            });
            case Statement.Remove(Ast.Ref member, Ast.Ref edge) -> write(writer -> {
                writer.remove(session.resolve(writer, edge), session.resolve(writer, member));
                return QueryResult.message("removed");
            });
            case Statement.RemoveAt(long position, Ast.Ref edge) -> write(writer -> {
                writer.removeAt(session.resolve(writer, edge), position);
                return QueryResult.message("removed position " + position);
            });
            case Statement.SetProperty set -> write(writer -> {
                writer.set(session.resolve(writer, set.atom()), set.property(), set.value(), set.validity());
                return QueryResult.message("set " + set.property());
            });
            case Statement.Unset(Ast.Ref atom, String property) -> write(writer -> {
                writer.unset(session.resolve(writer, atom), property);
                return QueryResult.message("unset " + property);
            });
            case Statement.Document(Ast.Ref atom, String json) -> write(writer -> {
                writer.document(session.resolve(writer, atom), Json.parse(json));
                return QueryResult.message("document stored");
            });
            case Statement.EmbedText(Ast.Ref atom, String text) -> write(writer -> {
                writer.embed(session.resolve(writer, atom), text);
                return QueryResult.message("embedded with " + database().semantic().encoder().model());
            });
            case Statement.EmbedVector(Ast.Ref atom, String model, float[] vector) -> write(writer -> {
                writer.embed(session.resolve(writer, atom), model, 1, vector);
                return QueryResult.message("embedded " + vector.length + "-dimensional vector with " + model);
            });
            case Statement.BindState state -> write(writer -> {
                StateBindings.bind(writer, session.resolve(writer, state.atom()), state.schema(), state.value(), state.validity());
                return QueryResult.message("state bound");
            });
            case Statement.Delete(Ast.Ref atom) -> write(writer -> {
                long id = session.resolve(writer, atom);
                writer.delete(id);
                return QueryResult.message("deleted @" + id);
            });
            case Statement.Qualify qualify -> write(writer -> {
                long edge = session.resolve(writer, qualify.edge());
                long member = session.resolve(writer, qualify.member());
                List<Long> evidence = qualify.evidence().stream().map(ref -> session.resolve(writer, ref)).toList();
                long id = Provenance.qualify(writer, edge, member,
                        new Qualifier(0, qualify.type(), qualify.confidence(), 0, System.currentTimeMillis(), evidence, 0));
                qualify.bind().ifPresent(name -> session.bind(name, id));
                return QueryResult.message("assertion @" + id + " qualifies @" + member + " in @" + edge);
            });
            case Statement.RecordEvidence evidence -> write(writer -> {
                long source = evidence.source().map(ref -> session.resolve(writer, ref)).orElse(0L);
                long id = Provenance.record(writer, new Evidence(source, 0, System.currentTimeMillis(), evidence.creator(), 0, evidence.attributes(), 0));
                evidence.bind().ifPresent(name -> session.bind(name, id));
                return QueryResult.message("evidence @" + id + " recorded");
            });
            case Statement.Trace(Ast.Ref assertion, int depth) -> read(reader -> traceProvenance(reader, session.resolve(reader, assertion), depth));
            case Statement.RegisterSignal signal -> write(writer -> {
                long atom = session.resolve(writer, signal.atom());
                long id = Signals.register(writer, new Signals.Signal(atom, 0, signal.clock(), signal.from(), signal.to(), signal.resolution(), signal.schema()));
                return QueryResult.message("signal @" + id + " registered on @" + atom);
            });
            case Statement.ResolveSignals(List<Ast.Ref> edges, long from, long to) -> read(reader -> {
                Map<Long, List<Signals.Resolved>> resolved = Signals.resolve(reader,
                        edges.stream().map(ref -> session.resolve(reader, ref)).toList(), from, to);
                List<List<Cell>> rows = new ArrayList<>();
                resolved.forEach((atom, signals) -> signals.forEach(signal -> rows.add(List.of(atomCell(reader, atom),
                        new Cell.AtomCell(signal.signal(), ""), QueryResult.text(signal.descriptor().clock()),
                        QueryResult.scalar(new Value.Int(signal.descriptor().from())), QueryResult.scalar(new Value.Int(signal.descriptor().to()))))));
                return QueryResult.table(List.of("atom", "signal", "clock", "from", "to"), rows);
            });
            case Statement.Query(Ast.Match match) -> read(reader -> query(reader, match));
            case Statement.Explain(Ast.Match match) -> read(reader -> {
                Planner.Plan plan = planner(reader).plan(match);
                return QueryResult.message(plan.describe());
            });
            case Statement.Members members -> read(reader -> members(reader, members));
            case Statement.IncidentTo(Ast.Ref atom, Optional<Expr> validAt) -> read(reader -> incident(reader, session.resolve(reader, atom), validAt));
            case Statement.Describe(Ast.Ref atom) -> read(reader -> describe(reader, session.resolve(reader, atom)));
            case Statement.SetAlgebra algebra -> algebra.into().isPresent() ? write(writer -> setAlgebra(writer, algebra)) : read(reader -> setAlgebra(reader, algebra));
            case Statement.Expand(Ast.Ref atom, int depth, OptionalLong limit) -> read(reader -> table(List.of("path", "atom", "status"),
                    Hora.on(reader).expand(session.resolve(reader, atom), depth, budget(limit).withDepth(depth))
                            .map(expansion -> List.<Cell>of(new Cell.Items(expansion.path().stream().map(id -> (Cell) new Cell.AtomCell(id, "")).toList()),
                                    atomCell(reader, expansion.atom()), QueryResult.text(expansion.status().name())))));
            case Statement.Gather(Field field, Ast.Ref edge) -> read(reader -> table(List.of("member", "value", "weight", "roles"),
                    Hora.on(reader).gather(session.resolve(reader, edge), field).map(gathered -> List.of(atomCell(reader, gathered.member()),
                            QueryResult.scalar(gathered.value()), QueryResult.number(gathered.weight()), QueryResult.text(String.join("|", gathered.roles()))))));
            case Statement.Reduce reduce -> read(reader -> {
                long edge = session.resolve(reader, reduce.edge());
                return table(List.of(reduce.reducer().name().toLowerCase()), Stream.of(List.of(
                        Hora.on(reader).reduce(edge, reduce.field(), reduce.reducer(), reduce.weighted())
                                .stream().mapToObj(QueryResult::number).findFirst().orElse(QueryResult.scalar(Value.NULL)))));
            });
            case Statement.Scatter scatter -> read(reader -> numericTable(reader, "node", Hora.on(reader).nodeValues(
                    reader.atoms(scatter.edgeType()), edge -> numeric(reader, edge, scatter.field()), scatter.weighted())));
            case Statement.Propagate propagate -> read(reader -> numericTable(reader, "node", Hora.on(reader).propagate(
                    reader.atoms(propagate.edgeType()).boxed().toList(), node -> numeric(reader, node, propagate.field()),
                    _ -> 1.0)));
            case Statement.Neighbors(Ast.Ref atom, int threshold, OptionalLong limit) -> read(reader -> {
                long id = session.resolve(reader, atom);
                Hora hora = Hora.on(reader);
                LongStream neighbors = reader.require(id).isEdge()
                        ? hora.edgeNeighbors(id, threshold, Budget.DEFAULT)
                        : hora.neighbors(id, threshold, Budget.DEFAULT);
                return table(List.of("neighbor"), limited(neighbors.mapToObj(neighbor -> List.of(atomCell(reader, neighbor))), limit));
            });
            case Statement.OverlapJoin(String type, long threshold, OptionalLong limit) -> read(reader -> table(List.of("left", "right", "overlap"),
                    limited(Hora.on(reader).overlapJoin(reader.atoms(type).boxed().toList(), threshold, Budget.DEFAULT)
                            .map(pair -> List.of(atomCell(reader, pair.left()), atomCell(reader, pair.right()), QueryResult.number(pair.overlap()))), limit)));
            case Statement.Closure(Ast.Ref edge, int dimension, OptionalLong limit) -> read(reader -> table(List.of("simplex"),
                    limited(Hora.on(reader).closure(session.resolve(reader, edge), dimension, budget(limit))
                            .map(subset -> List.<Cell>of(new Cell.Items(LongStream.of(subset).mapToObj(id -> (Cell) new Cell.AtomCell(id, "")).toList()))), limit)));
            case Statement.PatternQuery pattern -> read(reader -> pattern(reader, pattern));
            case Statement.Sample sample -> {
                SwapSampler.Sample result = SwapSampler.run(database(), sample.edgeType(), sample.swaps(), sample.seed(), sample.branch());
                yield QueryResult.message("branch " + result.branch().name() + " holds " + result.accepted() + " accepted and "
                        + result.rejected() + " rejected swaps (seed " + result.seed() + ")");
            }
            case Statement.DiffGenerations(long before, long after) -> diff(
                    database().engine().snapshotAt(before, session.branch()), database().engine().snapshotAt(after, session.branch()));
            case Statement.DiffBranches(String before, String after) -> diff(
                    database().engine().snapshot(session.branchId(before)), database().engine().snapshot(session.branchId(after)));
            case Statement.History(OptionalLong limit) -> history(limit);
            case Statement.Stats _ -> stats();
            case Statement.ShowTypes _ -> read(reader -> table(List.of("id", "name", "kind", "properties", "roles", "version"),
                    reader.schema().types(reader.view()).stream().map(type -> List.of(QueryResult.number(type.id()), QueryResult.text(type.name()),
                            QueryResult.text(type.kind().name()), QueryResult.text(type.properties().stream()
                                    .map(property -> property.name() + " " + property.type() + (property.indexed() ? " INDEXED" : ""))
                                    .collect(Collectors.joining(", "))),
                            QueryResult.text(String.join(", ", type.roles())), QueryResult.number(type.version())))));
            case Statement.ShowBranches _ -> table(List.of("id", "name", "parent", "base", "created"),
                    database().engine().branches().stream().map(branch -> List.of(QueryResult.number(branch.id()), QueryResult.text(branch.name()),
                            QueryResult.number(branch.parent()), QueryResult.number(branch.baseGeneration()),
                            QueryResult.text(branch.createdAt() == 0 ? "-" : Instant.ofEpochMilli(branch.createdAt()).toString()))));
            case Statement.ShowViews _ -> read(reader -> table(List.of("id", "name", "kind", "refresh", "parameter", "generation"),
                    database().views().list(reader).stream().map(view -> List.of(QueryResult.number(view.id()), QueryResult.text(view.name()),
                            QueryResult.text(view.kind().name()), QueryResult.text(view.refresh().name()), QueryResult.number(view.parameter()),
                            QueryResult.number(view.lastGeneration())))));
            case Statement.Checkpoint _ -> {
                var result = database().engine().checkpoint();
                yield QueryResult.message("checkpoint at generation " + result.generation() + ", lsn " + result.lsn());
            }
            case Statement.Compact _ -> {
                var report = database().engine().compact();
                yield QueryResult.message("compacted segments " + report.compacted() + " at generation " + report.generation());
            }
            case Statement.Begin _, Statement.Commit _, Statement.Rollback _, Statement.UseBranch _, Statement.At _, Statement.AsOf _,
                 Statement.UseTenant _, Statement.Authenticate _, Statement.WhoAmI _, Statement.SetFormat _ ->
                    session.execute(statement);
        };
    }

    private static boolean administrative(Statement statement) {
        return switch (statement) {
            case Statement.CreateBranch _, Statement.DropBranch _, Statement.MergeBranch _, Statement.Sample _,
                 Statement.DiffGenerations _, Statement.DiffBranches _, Statement.History _, Statement.Stats _,
                 Statement.Checkpoint _, Statement.Compact _, Statement.CreateTenant _, Statement.AlterTenant _,
                 Statement.CreateUser _, Statement.AlterUser _, Statement.DropUser _, Statement.ShowTenants _, Statement.ShowUsers _,
                 Statement.CreateView _, Statement.RefreshView _, Statement.ShowView _, Statement.CreateType _,
                 Statement.CreateIndex _, Statement.CreateJsonIndex _ -> true;
            default -> false;
        };
    }

    private static Cell limit(long value) {
        return value == Long.MAX_VALUE ? QueryResult.text("unlimited") : QueryResult.number(value);
    }

    private QueryResult read(Function<Reader, QueryResult> work) {
        return session.read(work);
    }

    private QueryResult write(Function<Writer, QueryResult> work) {
        return session.write(work);
    }

    private static MemberSpec spec(MemberClause clause) {
        MemberSpec spec = MemberSpec.PLAIN.withRoles(clause.roles()).withValidity(clause.validity());
        return clause.weight().isPresent() ? spec.withWeight(clause.weight().getAsDouble()) : spec;
    }

    private QueryResult inserted(Reader reader, long id) {
        return QueryResult.table(List.of("id"), List.of(List.of(atomCell(reader, id))));
    }

    private Planner planner(Reader reader) {
        return new Planner(reader, database().statistics(), ref -> session.resolve(reader, ref));
    }

    private Evaluator evaluator(Reader reader) {
        Map<String, Set<Long>> similarity = new HashMap<>();
        return new Evaluator(new Evaluator.Context() {
            @Override
            public Reader reader() {
                return reader;
            }

            @Override
            public long resolve(Ast.Ref ref) {
                return session.resolve(reader, ref);
            }

            @Override
            public Set<Long> similar(String text, int k, SemanticPlane.Consistency consistency) {
                return similarity.computeIfAbsent(k + ":" + consistency + ":" + text, _ -> reader.similar(text, k, consistency).stream()
                        .map(SemanticPlane.Hit::atom).collect(Collectors.toSet()));
            }
        });
    }

    private QueryResult query(Reader reader, Ast.Match match) {
        long started = System.nanoTime();
        IoTrace trace = IoTrace.withBudget(database().queryPageBudget(session.principal()));
        Planner.Plan plan = planner(reader).plan(match);
        Evaluator evaluator = evaluator(reader);
        AtomicLong produced = new AtomicLong();
        List<Map<String, Long>> bindings = trace.call(() -> {
            Stream<Map<String, Long>> rows = plan.chosen().path().scan(reader)
                    .peek(_ -> produced.incrementAndGet())
                    .filter(atom -> admits(reader, plan, atom))
                    .mapToObj(atom -> Map.of(match.variable(), atom))
                    .filter(binding -> plan.residual().stream().allMatch(filter -> evaluator.test(filter, binding)));
            if (match.orderBy().isPresent()) {
                Comparator<Map<String, Long>> order = Comparator.comparing(binding -> evaluator.value(match.orderBy().get(), binding));
                rows = rows.sorted(match.descending() ? order.reversed() : order);
            }
            boolean aggregate = match.projections().stream().anyMatch(projection -> Evaluator.isAggregate(projection.expr()));
            if (match.limit().isPresent() && !aggregate) {
                rows = rows.limit(match.limit().getAsLong());
            }
            return rows.toList();
        });
        database().statistics().observe(plan.chosen().path().kind(), plan.chosen().rows(), produced.get());
        boolean aggregate = match.projections().stream().anyMatch(projection -> Evaluator.isAggregate(projection.expr()));
        List<List<Cell>> rows = aggregate
                ? List.of(match.projections().stream().map(projection -> Evaluator.isAggregate(projection.expr())
                ? evaluator.aggregate(projection.expr(), bindings)
                : bindings.isEmpty() ? QueryResult.scalar(Value.NULL) : evaluator.cell(projection.expr(), bindings.getFirst())).toList())
                : bindings.stream().map(binding -> match.projections().stream()
                .map(projection -> evaluator.cell(projection.expr(), binding)).toList()).toList();
        QueryResult result = QueryResult.table(match.projections().stream().map(Ast.Projection::alias).toList(), rows);
        return result.withTrace(new QueryResult.Trace(0, reader.generation(), plan.chosen().path().describe(), plan.chosen().rows(),
                rows.size(), trace.pagesRead(), trace.cacheHits(), System.nanoTime() - started));
    }

    private static boolean admits(Reader reader, Planner.Plan plan, long atom) {
        if (plan.chosen().path() instanceof AccessPath.TypeScan) {
            return true;
        }
        return reader.atom(atom).map(found -> found.isEdge() == plan.match().edges()
                && plan.type().map(type -> type.id() == found.type().id()).orElse(true)).orElse(false);
    }

    private QueryResult members(Reader reader, Statement.Members statement) {
        long edge = session.resolve(reader, statement.edge());
        Evaluator evaluator = evaluator(reader);
        OptionalLong instant = statement.validAt()
                .map(expr -> OptionalLong.of((long) evaluator.value(expr, Map.of()).number().orElseThrow()))
                .orElse(OptionalLong.empty());
        Stream<Member> members = statement.role().map(role -> reader.membersWithRole(edge, role))
                .or(() -> statement.weight().map(range -> reader.membersWeighted(edge, range.low(), range.high())))
                .or(() -> instant.isPresent() ? Optional.of(reader.members(edge, instant.getAsLong())) : Optional.empty())
                .orElseGet(() -> reader.members(edge))
                .filter(member -> statement.role().map(member.roles()::contains).orElse(true))
                .filter(member -> statement.weight().map(range -> member.weight() >= range.low() && member.weight() <= range.high()).orElse(true))
                .filter(member -> instant.isEmpty() || member.validity().contains(instant.getAsLong()));
        EvidencePolicy policy = statement.policy().orElse(EvidencePolicy.ANY);
        Stream<Member> admitted = members.filter(member -> policy.admits(Provenance.qualifier(reader, member.qualifier())));
        return table(List.of("position", "member", "roles", "weight", "valid", "qualifier"),
                limited(admitted.map(member -> List.of(QueryResult.number(member.position()), atomCell(reader, member.atom()),
                        QueryResult.text(String.join("|", member.roles())), QueryResult.number(member.weight()),
                        QueryResult.text(interval(member.validity().from(), member.validity().to())),
                        member.qualifier() == 0 ? QueryResult.scalar(Value.NULL) : new Cell.AtomCell(member.qualifier(), ""))), statement.limit()));
    }

    private QueryResult incident(Reader reader, long atom, Optional<Expr> validAt) {
        Evaluator evaluator = evaluator(reader);
        OptionalLong instant = validAt.map(expr -> OptionalLong.of((long) evaluator.value(expr, Map.of()).number().orElseThrow()))
                .orElse(OptionalLong.empty());
        Set<Long> active = instant.isPresent() ? Temporal.activeEdges(reader, atom, instant.getAsLong()).boxed().collect(Collectors.toSet()) : Set.of();
        return table(List.of("edge", "roles", "position"), reader.incident(atom)
                .filter(incident -> instant.isEmpty() || active.contains(incident.edge()))
                .map(incident -> List.of(atomCell(reader, incident.edge()), QueryResult.text(String.join("|", incident.roles())),
                        QueryResult.number(reader.position(incident)))));
    }

    private QueryResult describe(Reader reader, long id) {
        Atom atom = reader.require(id);
        List<List<Cell>> rows = new ArrayList<>();
        rows.add(row("id", atomCell(reader, id)));
        rows.add(row("type", QueryResult.text(atom.type().name())));
        rows.add(row("kind", QueryResult.text(atom.type().kind().name())));
        atom.key().ifPresent(key -> rows.add(row("key", QueryResult.text(key))));
        if (atom.isEdge()) {
            Hyperedge edge = reader.edge(id);
            rows.add(row("cardinality", QueryResult.number(edge.size())));
            rows.add(row("weight sum", QueryResult.number(edge.summary().weightSum() / 1e9)));
            rows.add(row("fingerprint", QueryResult.text(Long.toHexString(edge.summary().fingerprint()))));
            rows.add(row("height", QueryResult.number(edge.members().height())));
        }
        rows.add(row("degree", QueryResult.number(reader.degree(id))));
        reader.properties(id).forEach((name, value) -> rows.add(row(name, QueryResult.scalar(value))));
        reader.document(id).ifPresent(json -> rows.add(row("document", QueryResult.text(json.print()))));
        StateBindings.state(reader, id).ifPresent(state -> rows.add(row("state", QueryResult.scalar(state.value()))));
        return QueryResult.table(List.of("attribute", "value"), rows);
    }

    private static List<Cell> row(String name, Cell value) {
        return List.of(QueryResult.text(name), value);
    }

    private QueryResult setAlgebra(Reader reader, Statement.SetAlgebra algebra) {
        Hyperedge left = reader.edge(session.resolve(reader, algebra.left()));
        Hyperedge right = reader.edge(session.resolve(reader, algebra.right()));
        Tree<?> a = left.membership();
        Tree<?> b = right.membership();
        return switch (algebra.op()) {
            case SUBSET -> scalarResult("subset", new Value.Bool(TreeAlgebra.subset(a, b)));
            case EQUAL -> scalarResult("equal", new Value.Bool(TreeAlgebra.sameKeys(a, b)));
            case JACCARD -> scalarResult("jaccard", new Value.Real(TreeAlgebra.jaccard(a, b)));
            case CONTAINMENT -> scalarResult("containment", new Value.Real(TreeAlgebra.containment(a, b)));
            case OVERLAP -> scalarResult("overlap", new Value.Int(TreeAlgebra.countIntersect(a, b)));
            case INTERSECT -> memberList(reader, TreeAlgebra.intersectKeys(a, b));
            case UNION -> memberList(reader, LongStream.concat(a.keys(), b.keys()).distinct().sorted());
            case DIFFERENCE -> memberList(reader, a.keys().filter(member -> !b.contains(member)));
        };
    }

    private QueryResult setAlgebra(Writer writer, Statement.SetAlgebra algebra) {
        long leftId = session.resolve(writer, algebra.left());
        Hyperedge left = writer.edge(leftId);
        Hyperedge right = writer.edge(session.resolve(writer, algebra.right()));
        if (left.kind() != EdgeKind.SET || right.kind() != EdgeKind.SET) {
            throw HStoreException.invalid("materialized set algebra requires SET hyperedges");
        }
        WriteScope scope = new WriteScope();
        Tree<Incidence> result = switch (algebra.op()) {
            case UNION -> TreeAlgebra.union(left.members(), right.members(), scope, (mine, _) -> mine);
            case DIFFERENCE -> TreeAlgebra.difference(left.members(), right.members(), scope);
            case INTERSECT -> TreeAlgebra.intersection(left.members(), right.members(), scope);
            default -> throw HStoreException.invalid(algebra.op() + " does not produce a hyperedge");
        };
        long created = writer.edge(writer.require(leftId).type().name());
        writer.transaction().load(created, result.values().toList());
        algebra.into().ifPresent(name -> session.bind(name, created));
        return QueryResult.table(List.of("edge", "cardinality"), List.of(List.of(atomCell(writer, created), QueryResult.number(result.size()))));
    }

    private QueryResult memberList(Reader reader, LongStream members) {
        return table(List.of("member"), members.mapToObj(member -> List.of(atomCell(reader, member))));
    }

    private QueryResult traceProvenance(Reader reader, long assertion, int depth) {
        return table(List.of("depth", "step", "id", "detail"), Provenance.trace(reader, assertion, depth).stream().map(step -> switch (step) {
            case Provenance.Step.Assertion(int level, long id, Qualifier qualifier) -> List.of(QueryResult.number(level), QueryResult.text("assertion"),
                    (Cell) new Cell.AtomCell(id, ""), QueryResult.text(qualifier.type() + " confidence " + qualifier.confidence()));
            case Provenance.Step.Support(int level, long id, Evidence evidence) -> List.of(QueryResult.number(level), QueryResult.text("evidence"),
                    (Cell) new Cell.AtomCell(id, ""), QueryResult.text("by " + evidence.creator() + " " + evidence.attributes()));
            case Provenance.Step.Source(int level, long atom) -> List.of(QueryResult.number(level), QueryResult.text("source"),
                    atomCell(reader, atom), QueryResult.text(""));
            case Provenance.Step.Cycle(int level, long id) -> List.of(QueryResult.number(level), QueryResult.text("cycle"),
                    (Cell) new Cell.AtomCell(id, ""), QueryResult.text("already visited"));
        }));
    }

    private QueryResult pattern(Reader reader, Statement.PatternQuery query) {
        List<Pattern.Constraint> constraints = new ArrayList<>(query.constraints());
        query.bindings().forEach((variable, ref) -> constraints.add(new Pattern.Constraint.Bound(variable, session.resolve(reader, ref))));
        Pattern pattern = new Pattern(query.variables(), constraints);
        List<String> columns = query.variables().stream().map(Pattern.Variable::name).toList();
        return table(columns, limited(new PatternMatcher(reader).match(pattern, Budget.DEFAULT)
                .map(binding -> columns.stream().map(column -> atomCell(reader, binding.get(column))).toList()), query.limit()));
    }

    private QueryResult showView(Statement.ShowView view) {
        return read(reader -> {
            if (view.key().isPresent()) {
                long key = session.resolve(reader, view.key().get());
                return table(List.of("key", "value", "staleness"), database().views().read(reader, view.name(), key).stream()
                        .map(reading -> List.of(QueryResult.number(key), QueryResult.text(render(reading.cell())), QueryResult.number(reading.staleness()))));
            }
            return table(List.of("key", "value"), limited(database().views().scan(reader, view.name())
                    .map(entry -> List.of(QueryResult.number(entry.key()), QueryResult.text(render(entry.value())))), view.limit()));
        });
    }

    private static String render(ViewCell cell) {
        return switch (cell) {
            case ViewCell.Count(long value) -> Long.toString(value);
            case ViewCell.Activity(long added, long removed) -> "+" + added + " -" + removed;
            case ViewCell.Ranked(var neighbors) -> neighbors.stream()
                    .map(neighbor -> "@" + neighbor.edge() + "×" + neighbor.overlap()).collect(Collectors.joining(", "));
        };
    }

    private QueryResult diff(Snapshot before, Snapshot after) {
        try (before; after) {
            Reader left = database().reader(before);
            Reader right = database().reader(after);
            return table(List.of("atom", "change", "detail"), Temporal.diff(left, right).flatMap(delta -> switch (delta) {
                case Temporal.Delta.AtomCreated(long atom, var _) -> Stream.of(List.of(atomCell(right, atom), QueryResult.text("created"), QueryResult.text("")));
                case Temporal.Delta.AtomDeleted(long atom, var _) -> Stream.of(List.of((Cell) new Cell.AtomCell(atom, ""), QueryResult.text("deleted"), QueryResult.text("")));
                case Temporal.Delta.MembersChanged(long atom, List<MemberChange> changes) -> changes.stream().map(change -> List.of(
                        atomCell(right, atom), QueryResult.text(change.getClass().getSimpleName().toLowerCase()), QueryResult.text("@" + change.member())));
            }));
        }
    }

    private QueryResult history(OptionalLong limit) {
        long newest = database().engine().feed().lastGeneration();
        long from = Math.max(0, newest - limit.orElse(20));
        return table(List.of("generation", "txn", "time", "branch", "members", "slots"), database().engine().feed().replay(from)
                .map(event -> List.of(QueryResult.number(event.generation()), QueryResult.number(event.txnId()),
                        QueryResult.text(Instant.ofEpochMilli(event.wallTime()).toString()), QueryResult.number(event.branch()),
                        QueryResult.number(event.members().size()), QueryResult.number(event.slots().size()))));
    }

    private QueryResult stats() {
        EngineStats engine = database().engine().stats();
        Statistics.Catalog catalog = database().statistics().refresh();
        Map<String, Cell> metrics = new LinkedHashMap<>();
        metrics.put("generation", QueryResult.number(engine.generation()));
        metrics.put("atoms", QueryResult.number(catalog.atoms()));
        metrics.put("hyperedges", QueryResult.number(catalog.edges()));
        metrics.put("cardinality", QueryResult.text(catalog.cardinality().toString()));
        metrics.put("degree", QueryResult.text(catalog.degree().toString()));
        metrics.put("average degree", QueryResult.number(Math.round(catalog.averageDegree() * 100) / 100.0));
        for (long k = 2; k <= 5; k++) {
            metrics.put("edges with cardinality " + k, QueryResult.number(catalog.countWithCardinality(k)));
        }
        metrics.put("commits", QueryResult.number(engine.commits()));
        metrics.put("rebased commits", QueryResult.number(engine.rebases()));
        metrics.put("conflicts", QueryResult.number(engine.conflicts()));
        metrics.put("pages read", QueryResult.number(engine.pagesRead()));
        metrics.put("pages written", QueryResult.number(engine.pagesWritten()));
        metrics.put("data bytes written", QueryResult.number(engine.dataBytesWritten()));
        metrics.put("wal bytes", QueryResult.number(engine.walBytes()));
        metrics.put("wal segments", QueryResult.number(engine.walSegments()));
        metrics.put("cache hit rate", QueryResult.number(Math.round(engine.cacheHitRate() * 1000) / 1000.0));
        metrics.put("data segments", QueryResult.number(engine.segments().size()));
        metrics.put("feed bytes", QueryResult.number(engine.feedBytes()));
        metrics.put("semantic index generation", QueryResult.number(database().semantic().indexedGeneration()));
        database().statistics().corrections().forEach((path, factor) ->
                metrics.put("planner feedback " + path, QueryResult.number(Math.round(factor * 1000) / 1000.0)));
        return QueryResult.table(List.of("metric", "value"), metrics.entrySet().stream()
                .map(entry -> List.of(QueryResult.text(entry.getKey()), entry.getValue())).toList());
    }

    private double numeric(Reader reader, long atom, Field field) {
        return switch (field) {
            case Field.Property(String name) -> reader.property(atom, name).flatMap(value -> value.number().stream().boxed().findFirst()).orElse(0.0);
            case Field.State _ -> StateBindings.state(reader, atom).flatMap(binding -> binding.value().number().stream().boxed().findFirst()).orElse(0.0);
            case Field.Degree _ -> reader.degree(atom);
            case Field.Weight _ -> reader.view().edge(atom).map(edge -> edge.summary().weightSum() / 1e9).orElse(1.0);
        };
    }

    private QueryResult numericTable(Reader reader, String column, Map<Long, Double> values) {
        return table(List.of(column, "value"), values.entrySet().stream()
                .map(entry -> List.of(atomCell(reader, entry.getKey()), QueryResult.number(entry.getValue()))));
    }

    private static QueryResult scalarResult(String column, Value value) {
        return QueryResult.table(List.of(column), List.of(List.of(QueryResult.scalar(value))));
    }

    private static Budget budget(OptionalLong limit) {
        return limit.isPresent() ? Budget.DEFAULT.withRows(limit.getAsLong()) : Budget.DEFAULT;
    }

    private static <T> Stream<T> limited(Stream<T> rows, OptionalLong limit) {
        return limit.isPresent() ? rows.limit(limit.getAsLong()) : rows;
    }

    private static QueryResult table(List<String> columns, Stream<List<Cell>> rows) {
        return QueryResult.table(columns, rows.toList());
    }

    private Cell atomCell(Reader reader, long atom) {
        return evaluator(reader).atomCell(atom);
    }

    private static String interval(long from, long to) {
        return "[" + (from == Long.MIN_VALUE ? "*" : Long.toString(from)) + ", " + (to == Long.MAX_VALUE ? "*" : Long.toString(to)) + ")";
    }
}

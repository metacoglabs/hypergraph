// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
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
import io.hstore.db.hora.Diffusion;
import io.hstore.db.hora.Expansion;
import io.hstore.db.hora.Refinement;
import io.hstore.db.hora.Field;
import io.hstore.db.hora.Hora;
import io.hstore.db.hora.Pattern;
import io.hstore.db.hora.PatternMatcher;
import io.hstore.db.hora.SwapSampler;
import io.hstore.db.query.Ast.Expr;
import io.hstore.db.query.Ast.MemberClause;
import io.hstore.db.query.Ast.Statement;
import io.hstore.db.query.QueryResult.Cell;
import io.hstore.db.retrieve.HyperMatch;
import io.hstore.db.retrieve.HyperQuery;
import io.hstore.db.retrieve.RankQuery;
import io.hstore.db.retrieve.Ranking;
import io.hstore.db.retrieve.RoleTimeScorer;
import io.hstore.db.retrieve.MotifMatcher;
import io.hstore.db.retrieve.MotifClasses;
import io.hstore.db.retrieve.MotifCensus;
import io.hstore.db.retrieve.Selection;
import io.hstore.db.schema.TypeDef;
import io.hstore.db.security.Access;
import io.hstore.db.security.Principal;
import io.hstore.db.security.Quota;
import io.hstore.db.security.Role;
import io.hstore.db.security.Security;
import io.hstore.db.semantic.SemanticPlane;
import io.hstore.db.signal.Signals;
import io.hstore.db.stats.Statistics;
import io.hstore.db.temporal.BranchMerge;
import io.hstore.db.temporal.BranchRetrieval;
import io.hstore.db.temporal.StateBindings;
import io.hstore.db.temporal.Temporal;
import io.hstore.db.temporal.Transitions;
import io.hstore.db.temporal.FeedFold;
import io.hstore.db.value.Json;
import io.hstore.db.value.Value;
import io.hstore.db.view.MaterializedViews;
import io.hstore.db.view.ViewCell;
import io.hstore.engine.CheckpointThreshold;
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
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.stream.Stream;

final class Executor {

    private static final int MAX_DEPTH = 64;

    private final Session session;

    Executor(Session session) {
        this.session = session;
    }

    private HypergraphDatabase database() {
        return session.database();
    }

    QueryResult run(Statement statement) {
        if (engineWide(statement)) {
            session.principal().requireGlobal();
        } else if (administrative(statement)) {
            session.principal().requireAdmin();
        }
        capability(statement).ifPresent(this::requireCapability);
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
            case Statement.CreateMotifView(String name, Optional<String> type, boolean continuous) -> {
                long parameter = type.map(found -> session.read(reader -> {
                    TypeDef def = reader.requireReadableType(found);
                    if (!def.kind().isEdge()) {
                        throw HStoreException.invalid(found + " is not a hyperedge type");
                    }
                    return (long) def.id() + 1;
                })).orElse(0L);
                MaterializedViews.Descriptor descriptor = database().views().create(session.principal(), name, MaterializedViews.Kind.MOTIF_CENSUS,
                        continuous ? MaterializedViews.Refresh.CONTINUOUS : MaterializedViews.Refresh.ON_DEMAND, parameter);
                yield QueryResult.message("created view " + descriptor.name() + " at generation " + descriptor.lastGeneration());
            }
            case Statement.RefreshView(String name) -> QueryResult.message("view " + name + " refreshed to generation "
                    + database().views().refresh(session.principal(), name).lastGeneration());
            case Statement.DropView(String name) -> {
                database().views().drop(session.principal(), name);
                yield QueryResult.message("dropped view " + name);
            }
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
                Map<String, Long> limits = new LinkedHashMap<>(quota);
                Long connections = removeConnections(limits);
                Security.Tenant tenant = Security.createTenant(writer.transaction(), name, Quota.UNLIMITED.with(limits));
                if (connections != null) {
                    Access.setTenantConnectionLimit(writer.transaction(), tenant.id(), connections);
                }
                return QueryResult.message("created tenant " + tenant.name() + " #" + tenant.id());
            });
            case Statement.AlterTenant(String name, Map<String, Long> quota) -> write(writer -> {
                Security.Tenant tenant = Security.requireTenant(writer.engineView(), name);
                Map<String, Long> limits = new LinkedHashMap<>(quota);
                Long connections = removeConnections(limits);
                Security.setQuota(writer.transaction(), tenant, tenant.quota().with(limits));
                if (connections != null) {
                    Access.setTenantConnectionLimit(writer.transaction(), tenant.id(), connections);
                }
                return QueryResult.message("updated quota of tenant " + name);
            });
            case Statement.CreateUser(String name, String password, Optional<String> tenant, var role, var validUntil, var connectionLimit) ->
                    write(writer -> {
                        Security.createUser(writer.transaction(), name, password, governedTenant(writer, tenant), role);
                        Access.forget(writer.transaction(), name);
                        if (validUntil.isPresent() || connectionLimit.isPresent()) {
                            Access.setOptions(writer.transaction(), new Access.Options(name, validUntil.orElse(Long.MAX_VALUE),
                                    (int) connectionLimit.orElse(-1)));
                        }
                        Security.keepAGlobalAdmin(writer.transaction(), hadGlobalAdmin());
                        return QueryResult.message("created user " + name + " with role " + role);
                    });
            case Statement.AlterUser alter -> write(writer -> alterUser(writer, alter));
            case Statement.DropUser(String name) -> write(writer -> {
                Security.User user = governedUser(writer, name);
                if (!session.principal().global() && Security.users(writer.engineView()).size() <= 1) {
                    throw HStoreException.invalid("cannot drop the last user: the server would stop asking for credentials");
                }
                Security.dropUser(writer.transaction(), name);
                Security.keepAGlobalAdmin(writer.transaction(), hadGlobalAdmin());
                return QueryResult.message("dropped user " + name);
            });
            case Statement.Grant(Access.Privilege privilege, Optional<String> type, String name) -> write(writer -> {
                Security.User user = grantee(writer, privilege, name);
                boolean changed = Access.grant(writer.transaction(), name, type.map(found -> writer.requireType(found).id()).orElse(0), privilege);
                return QueryResult.message((changed ? "granted " : name + " already holds ") + privilege + type.map(found -> " on type " + found).orElse("")
                        + (changed ? " to " + user.name() : ""));
            });
            case Statement.Revoke(Access.Privilege privilege, Optional<String> type, String name) -> write(writer -> {
                grantee(writer, privilege, name);
                boolean changed = Access.revoke(writer.transaction(), name, type.map(found -> writer.requireType(found).id()).orElse(0), privilege);
                return QueryResult.message((changed ? "revoked " : name + " does not hold ") + privilege + type.map(found -> " on type " + found).orElse("")
                        + (changed ? " from " + name : ""));
            });
            case Statement.ShowGrants(Optional<String> name) -> read(reader -> showGrants(reader, name));
            case Statement.ShowTenants _ -> read(reader -> table(List.of("id", "name", "atoms", "edges", "payload bytes", "max atoms", "max edges",
                            "connections", "max connections"),
                    Security.tenants(reader.engineView()).stream().filter(tenant -> session.principal().governs(tenant.id())).map(tenant -> {
                        Quota.Usage usage = Security.usage(reader.engineView(), tenant.id());
                        int connectionLimit = Access.tenantConnectionLimit(reader.engineView(), tenant.id());
                        return List.of(QueryResult.number(tenant.id()), QueryResult.text(tenant.name()), QueryResult.number(usage.atoms()),
                                QueryResult.number(usage.edges()), QueryResult.number(usage.payloadBytes()),
                                limit(tenant.quota().maxAtoms()), limit(tenant.quota().maxEdges()),
                                QueryResult.number(database().tenantConnections(tenant.id())),
                                limit(connectionLimit < 0 ? Long.MAX_VALUE : connectionLimit));
                    })));
            case Statement.ShowUsers _ -> read(reader -> table(List.of("user", "tenant", "role", "valid until", "connection limit"),
                    Security.users(reader.engineView()).stream()
                    .filter(user -> session.principal().governs(user.tenant()))
                    .map(user -> {
                        Access.Options options = Access.options(reader.engineView(), user.name());
                        return List.of(QueryResult.text(user.name()), QueryResult.number(user.tenant()), QueryResult.text(user.role().name()),
                                options.validUntil() == Long.MAX_VALUE ? QueryResult.text("infinity") : QueryResult.text(Instant.ofEpochMilli(options.validUntil()).toString()),
                                options.connectionLimit() < 0 ? QueryResult.text("unlimited") : QueryResult.number(options.connectionLimit()));
                    })));
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
                } else if (writer.edge(edgeId).kind() == EdgeKind.ORDERED && !writer.contains(edgeId, memberId)) {
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
            case Statement.Trace(Ast.Ref assertion, int depth) -> read(reader -> traceProvenance(reader, session.resolve(reader, assertion), depth(depth)));
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
            case Statement.Rank rank -> read(reader -> rank(reader, rank));
            case Statement.Transitions transitions -> read(reader -> transitions(reader, transitions));
            case Statement.Diffuse diffuse -> read(reader -> diffuse(reader, diffuse));
            case Statement.SelectEvidence selection -> read(reader -> selectEvidence(reader, selection));
            case Statement.ExpandFrom expansion -> read(reader -> expandFrom(reader, expansion));
            case Statement.Refine refine -> read(reader -> refine(reader, refine));
            case Statement.BranchDelta delta -> read(reader -> branchDelta(reader, delta));
            case Statement.BranchSurvivors survivors -> read(reader -> QueryResult.table(List.of("key"), limited(
                    branches().survivors(session.branchId(survivors.branch()), scope(reader, survivors.scope())).stream()
                            .map(key -> List.<Cell>of(QueryResult.text(key(reader, key)))), survivors.limit()).toList()));
            case Statement.CompareBranches compare -> read(reader -> QueryResult.table(List.of("key", "side", "left", "right"), limited(
                    branches().compare(session.branchId(compare.left()), session.branchId(compare.right()), scope(reader, compare.scope())).stream()
                            .map(found -> List.of(QueryResult.text(key(reader, found.key())), QueryResult.text(found.side().name()),
                                    QueryResult.text(found.left().map(net -> value(net.after())).orElse("unchanged")),
                                    QueryResult.text(found.right().map(net -> value(net.after())).orElse("unchanged")))), compare.limit()).toList()));
            case Statement.NearestBranch nearest -> read(reader -> {
                List<Integer> among = nearest.among().isEmpty()
                        ? database().engine().branches().stream().filter(Branch::isActive).map(Branch::id).toList()
                        : nearest.among().stream().map(session::branchId).distinct().toList();
                return QueryResult.table(List.of("branch", "distance", "lower_bound"), branches()
                        .nearest(session.branchId(nearest.target()), among, scope(reader, nearest.scope()), nearest.top()).stream()
                        .map(found -> List.of(QueryResult.text(branchName(found.branch())), QueryResult.number(found.distance()),
                                QueryResult.number(found.lowerBound()))).toList());
            });
            case Statement.BranchAfter after -> read(reader -> QueryResult.table(List.of("generation", "branch", "key", "before", "after"), limited(
                    branches().after(session.branchId(after.branch()), after.generation(), after.window()).stream()
                            .flatMap(succession -> succession.changes().stream().map(net -> List.of(QueryResult.number(succession.generation()),
                                    QueryResult.text(branchName(succession.branch())), QueryResult.text(key(reader, net.key())),
                                    QueryResult.text(value(net.before())), QueryResult.text(value(net.after()))))), after.limit()).toList()));
            case Statement.StructuralMatch match -> read(reader -> structural(reader, match));
            case Statement.Motifs motifs -> read(reader -> motifs(reader, motifs));
            case Statement.MotifProfile profile -> read(reader -> motifProfile(reader, profile));
            case Statement.MotifMatch match -> read(reader -> motifMatch(reader, match));
            case Statement.ExplainStructural(Statement.StructuralMatch match) -> read(reader ->
                    QueryResult.message(hyperMatch(reader, match).explain(hyperQuery(reader, match))));
            case Statement.Expand(Ast.Ref atom, int depth, OptionalLong limit) -> read(reader -> table(List.of("path", "atom", "status"),
                    Hora.on(reader).expand(session.resolve(reader, atom), depth(depth), budget(limit).withDepth(depth(depth)))
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
                SwapSampler.Sample result = SwapSampler.run(database(), session.principal(), sample.edgeType(), sample.swaps(), sample.seed(), sample.branch());
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
                    reader.schema().types(reader.engineView()).stream().map(type -> List.of(QueryResult.number(type.id()), QueryResult.text(type.name()),
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
            case Statement.CheckpointAt(OptionalLong bytes) -> QueryResult.message(describe(database().engine().checkpointAt(bytes)));
            case Statement.Compact _ -> {
                var report = database().engine().compact();
                yield QueryResult.message("compacted segments " + report.compacted() + " at generation " + report.generation());
            }
            case Statement.Begin _, Statement.Commit _, Statement.Rollback _, Statement.UseBranch _, Statement.At _, Statement.AsOf _,
                 Statement.UseTenant _, Statement.Authenticate _, Statement.WhoAmI _, Statement.SetFormat _ ->
                    session.execute(statement);
        };
    }

    private static boolean engineWide(Statement statement) {
        return switch (statement) {
            case Statement.CreateBranch _, Statement.DropBranch _, Statement.MergeBranch _, Statement.ShowBranches _, Statement.Sample _,
                 Statement.BranchDelta _, Statement.BranchSurvivors _, Statement.CompareBranches _, Statement.NearestBranch _, Statement.BranchAfter _,
                 Statement.DiffGenerations _, Statement.DiffBranches _, Statement.History _, Statement.CreateTenant _, Statement.AlterTenant _,
                 Statement.CreateType _, Statement.CreateIndex _, Statement.CreateJsonIndex _ -> true;
            default -> false;
        };
    }

    private static boolean administrative(Statement statement) {
        return switch (statement) {
            case Statement.CreateUser _, Statement.AlterUser _, Statement.DropUser _, Statement.ShowTenants _, Statement.ShowUsers _,
                 Statement.Grant _, Statement.Revoke _,
                 Statement.CreateView _, Statement.CreateMotifView _, Statement.RefreshView _, Statement.DropView _, Statement.ShowView _ -> true;
            default -> false;
        };
    }

    private int governedTenant(Reader reader, Optional<String> name) {
        Principal principal = session.principal();
        if (name.isEmpty()) {
            return principal.tenant();
        }
        Optional<Security.Tenant> found = Security.tenant(reader.engineView(), name.get());
        if (principal.global()) {
            return found.orElseThrow(() -> HStoreException.invalid("unknown tenant '" + name.get() + "'")).id();
        }
        return found.filter(tenant -> tenant.id() == principal.home())
                .orElseThrow(() -> HStoreException.invalid("user " + principal.user() + " may only manage users of its own tenant")).id();
    }

    private Security.User governedUser(Reader reader, String name) {
        return Security.user(reader.engineView(), name).filter(user -> session.principal().governs(user.tenant()))
                .orElseThrow(() -> HStoreException.invalid("unknown user '" + name + "'"));
    }

    private boolean hadGlobalAdmin() {
        return database().read(reader -> Security.hasGlobalAdmin(reader.engineView()));
    }

    private static Long removeConnections(Map<String, Long> limits) {
        Long connections = null;
        for (String key : List.copyOf(limits.keySet())) {
            if (key.equalsIgnoreCase("connections")) {
                connections = limits.remove(key);
            }
        }
        return connections;
    }

    private QueryResult alterUser(Writer writer, Statement.AlterUser alter) {
        Security.User user = governedUser(writer, alter.name());
        boolean attributes = alter.role().isPresent() || alter.validUntil().isPresent() || alter.connectionLimit().isPresent()
                || alter.password().isPresent() && !user.name().equals(session.principal().user());
        if (attributes && user.role() == Role.ADMIN && !session.principal().global()) {
            throw HStoreException.invalid("only an admin of the default tenant can change the role, password expiry or connection limit "
                    + "of an admin, or the password of another admin");
        }
        Security.keepUnchanged(writer.transaction(), user);
        List<String> changes = new ArrayList<>();
        alter.role().filter(role -> role != user.role()).ifPresent(role -> {
            Security.setRole(writer.transaction(), user.name(), role);
            changes.add("role " + role);
        });
        alter.password().ifPresent(password -> {
            Security.setPassword(writer.transaction(), user.name(), password);
            changes.add("password");
        });
        if (alter.validUntil().isPresent() || alter.connectionLimit().isPresent()) {
            Access.Options current = Access.options(writer.engineView(), user.name());
            Access.setOptions(writer.transaction(), new Access.Options(user.name(), alter.validUntil().orElse(current.validUntil()),
                    (int) alter.connectionLimit().orElse(current.connectionLimit())));
            alter.validUntil().ifPresent(_ -> changes.add("password expiry"));
            alter.connectionLimit().ifPresent(_ -> changes.add("connection limit"));
        }
        Security.keepAGlobalAdmin(writer.transaction(), hadGlobalAdmin());
        return QueryResult.message(changes.isEmpty() ? "nothing changed for " + user.name() : "changed " + String.join(", ", changes) + " of " + user.name());
    }

    private Security.User grantee(Writer writer, Access.Privilege privilege, String name) {
        Security.User user = governedUser(writer, name);
        if (privilege.onType() && user.role().readsEveryType()) {
            throw HStoreException.invalid("user " + name + " has the " + user.role() + " role, which already covers every type; "
                    + "grants on types apply to users with ROLE none");
        }
        if (!privilege.onType()) {
            session.principal().requireGlobal();
            if (user.tenant() != Principal.DEFAULT_TENANT) {
                throw HStoreException.invalid(privilege + " can only be granted to users of the default tenant");
            }
        }
        Security.keepUnchanged(writer.transaction(), user);
        return user;
    }

    private QueryResult showGrants(Reader reader, Optional<String> name) {
        Principal principal = session.principal();
        String wanted = name.orElse(principal.user());
        if (!wanted.equals(principal.user())) {
            principal.requireAdmin();
            governedUser(reader, wanted);
        }
        return table(List.of("user", "privilege", "type"), Access.grants(reader.engineView()).stream()
                .filter(grant -> name.isPresent() || !principal.role().canAdminister() ? grant.user().equals(wanted)
                        : Security.user(reader.engineView(), grant.user()).filter(user -> principal.governs(user.tenant())).isPresent())
                .map(grant -> List.of(QueryResult.text(grant.user()), QueryResult.text(grant.privilege().name()),
                        Access.isCapability(grant.type()) ? QueryResult.scalar(Value.NULL)
                                : QueryResult.text(reader.schema().type(reader.engineView(), grant.type()).map(TypeDef::name).orElse("#" + grant.type())))));
    }

    private static Optional<Access.Privilege> capability(Statement statement) {
        return switch (statement) {
            case Statement.Stats _ -> Optional.of(Access.Privilege.MONITOR);
            case Statement.Checkpoint _, Statement.CheckpointAt _, Statement.Compact _ -> Optional.of(Access.Privilege.MAINTAIN);
            default -> Optional.empty();
        };
    }

    private void requireCapability(Access.Privilege capability) {
        Principal principal = session.principal();
        if (!database().holds(principal, capability)) {
            throw HStoreException.invalid("user " + principal.user() + " needs the " + capability + " privilege or the ADMIN role in the default "
                    + "tenant for this statement");
        }
    }

    private static String describe(CheckpointThreshold threshold) {
        return "checkpoint after " + threshold.bytes() + " bytes of log, " + switch (threshold.source()) {
            case AUTOMATIC -> "sized from the store";
            case DATABASE -> "set by CHECKPOINT AT";
            case CONFIGURATION -> "set by configuration";
        };
    }

    private static Cell limit(long value) {
        return value == Long.MAX_VALUE ? QueryResult.text("unlimited") : QueryResult.number(value);
    }

    private QueryResult read(Function<Reader, QueryResult> work) {
        return IoTrace.withBudget(database().queryPageBudget(session.principal())).call(() -> session.read(work));
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
                    .filter(atom -> admits(reader, plan, atom))
                    .peek(_ -> produced.incrementAndGet())
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
        if (reader.principal().role().readsEveryType()) {
            database().statistics().observe(reader.tenant(), plan.chosen().path().kind(), plan.chosen().rows(), produced.get());
        }
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

    private QueryResult expandFrom(Reader reader, Statement.ExpandFrom statement) {
        Evaluator evaluator = evaluator(reader);
        if (!(statement.minimum() >= 0 && statement.minimum() <= 1)) {
            throw HStoreException.invalid("MIN must lie in [0, 1]");
        }
        Map<Long, Double> seeds = new LinkedHashMap<>();
        statement.seeds().forEach(seed -> {
            if (seeds.put(reader.require(session.resolve(reader, seed.atom())).id(), seed.weight()) != null) {
                throw HStoreException.invalid("EXPAND FROM names a seed twice");
            }
        });
        Map<Integer, Double> factors = new HashMap<>();
        statement.through().forEach(found -> {
            TypeDef type = reader.requireReadableType(found.type());
            if (!type.kind().isEdge()) {
                throw HStoreException.invalid(found.type() + " is not a hyperedge type");
            }
            if (factors.put(type.id(), found.weight()) != null) {
                throw HStoreException.invalid("THROUGH names " + found.type() + " twice");
            }
        });
        Map<String, Double> exit = factorMap(statement.exit(), "EXIT");
        Map<String, Double> entry = factorMap(statement.entry(), "ENTER");
        OptionalLong validAt = statement.validAt().map(expr -> OptionalLong.of((long) evaluator.value(expr, Map.of()).number()
                .orElseThrow(() -> HStoreException.invalid("VALID AT needs a number or a timestamp")))).orElse(OptionalLong.empty());
        Expansion.Model model = new Expansion.Model(factors, exit, entry, validAt, statement.hubDamping(), statement.edgeDamping(), statement.gate());
        Budget.Meter meter = Budget.DEFAULT.start();
        Expansion.Relevance relevance = statement.towards().<Expansion.Relevance>map(rank -> {
            RoleTimeScorer scorer = new RoleTimeScorer(reader, rankQuery(reader, rank), meter);
            return new Expansion.Relevance() {
                public double bound(long edge) {
                    return scorer.bound(edge);
                }

                public OptionalDouble score(long edge) {
                    return scorer.score(edge).map(found -> OptionalDouble.of(found.score())).orElse(OptionalDouble.empty());
                }
            };
        }).orElse(Expansion.Relevance.NONE);
        Expansion.Result result = Expansion.on(reader).run(seeds, model, relevance, (int) statement.top(), statement.minimum(), 100_000, meter);
        QueryResult table = QueryResult.table(List.of("edge", "value", "relevance", "via", "parent"), result.edges().stream()
                .map(found -> List.of(atomCell(reader, found.edge()), QueryResult.number(found.value()), QueryResult.number(found.relevance()),
                        atomCell(reader, found.via()), found.parent() < 0 ? QueryResult.scalar(Value.NULL) : atomCell(reader, found.parent())))
                .toList());
        String truncated = result.droppedBound() > 0
                ? "; the frontier was truncated, so rows with value below " + String.format(java.util.Locale.ROOT, "%.6g", result.droppedBound()) + " may be missing"
                : "";
        return new QueryResult(table.columns(), table.rows(), table.message() + "; stopped on " + result.stop().name().toLowerCase(java.util.Locale.ROOT)
                + ", every other hyperedge has value at most " + String.format(java.util.Locale.ROOT, "%.6g", result.bound()) + truncated, table.trace());
    }

    private static Map<String, Double> factorMap(List<Ast.RoleFactor> factors, String clause) {
        Map<String, Double> map = new HashMap<>();
        for (Ast.RoleFactor factor : factors) {
            if (map.put(factor.role(), factor.factor()) != null) {
                throw HStoreException.invalid(clause + " names role " + factor.role() + " twice");
            }
        }
        return map;
    }

    private static final int EVIDENCE_MEMBER_CAP = 10_000;

    private QueryResult selectEvidence(Reader reader, Statement.SelectEvidence statement) {
        Budget generation = new Budget(500_000, Budget.DEFAULT.maxDepth(), Budget.DEFAULT.maxTime().dividedBy(2));
        RankQuery query = rankQuery(reader, statement.query());
        Budget.Meter ranking = generation.start();
        Map<Long, RoleTimeScorer.EdgeScore> scores = new LinkedHashMap<>();
        Ranking.of(reader, query).top(ranking).forEach(score -> scores.put(score.edge(), score));
        Map<Long, Long> parents = new HashMap<>();
        Budget.Meter expanding = generation.start();
        RoleTimeScorer scorer = new RoleTimeScorer(reader, query, expanding);
        if (!statement.seeds().isEmpty()) {
            Map<Long, Double> seeds = new LinkedHashMap<>();
            statement.seeds().forEach(seed -> seeds.put(reader.require(session.resolve(reader, seed)).id(), 1.0));
            Expansion.Relevance relevance = new Expansion.Relevance() {
                public double bound(long edge) {
                    return scorer.bound(edge);
                }

                public OptionalDouble score(long edge) {
                    return scorer.score(edge).map(found -> OptionalDouble.of(found.score())).orElse(OptionalDouble.empty());
                }
            };
            Expansion.Result expansion = Expansion.on(reader).run(seeds, new Expansion.Model(Map.of(), Map.of(), Map.of(), OptionalLong.empty(),
                    0.5, 0.5, 0.1), relevance, (int) statement.candidates(), 0, 100_000, expanding);
            expansion.edges().forEach(found -> parents.put(found.edge(), found.parent()));
        }
        Set<Long> order = new LinkedHashSet<>(scores.keySet());
        order.addAll(parents.keySet());
        Map<Long, Selection.Candidate> candidates = new LinkedHashMap<>();
        boolean cut = false;
        for (long edge : order) {
            long[] members = reader.edge(edge).stream().limit(EVIDENCE_MEMBER_CAP + 1L).mapToLong(incidence -> incidence.member())
                    .filter(reader::owns).toArray();
            if (!expanding.tryConsume(members.length + 1L)) {
                cut = true;
                break;
            }
            if (members.length > EVIDENCE_MEMBER_CAP) {
                continue;
            }
            Optional<RoleTimeScorer.EdgeScore> score = Optional.ofNullable(scores.get(edge));
            boolean ofQueryType = query.type().map(type -> reader.typeOf(edge).orElse(-1) == type.id()).orElse(true);
            if (score.isEmpty() && ofQueryType) {
                try {
                    score = scorer.score(edge);
                } catch (HStoreException.ResourceLimit exhausted) {
                    cut = true;
                    break;
                }
            }
            double[] cover = score.map(RoleTimeScorer.EdgeScore::slotCover).orElse(new double[query.slots().size()]);
            candidates.put(edge, new Selection.Candidate(edge, score.map(found -> Math.max(0, Math.min(1, found.score()))).orElse(0.0), cover,
                    Math.max(1, members.length), members, parents.getOrDefault(edge, Selection.UNREACHABLE)));
        }
        double[] weights = query.slots().stream().mapToDouble(RankQuery.Slot::weight).toArray();
        Selection.Options options = new Selection.Options((int) Math.min(Integer.MAX_VALUE, statement.top().orElse(Integer.MAX_VALUE)),
                statement.budget().orElse(Long.MAX_VALUE), statement.kappa(), statement.lambda(), weights, statement.connected());
        Selection.Outcome outcome = Selection.select(List.copyOf(candidates.values()), options, generation.start());
        QueryResult table = QueryResult.table(List.of("edge", "gain", "relevance"), outcome.selected().stream()
                .map(found -> List.of(atomCell(reader, found.edge()), QueryResult.number(found.gain()), QueryResult.number(found.relevance())))
                .toList());
        StringBuilder message = new StringBuilder(table.message()).append("; objective ").append(format(outcome.objective()));
        message.append(Double.isFinite(outcome.bound()) ? " of at most " + format(outcome.bound()) : "")
                .append(" from ").append(candidates.size()).append(" candidates");
        if (outcome.singleton()) {
            message.append("; the best single unit beat the greedy set");
        }
        if (outcome.exhausted()) {
            message.append("; selection ran out of budget, so this is the greedy set so far and has no bound");
        }
        if (cut) {
            message.append("; candidate generation ran out of budget, so later candidates were not considered");
        }
        return new QueryResult(table.columns(), table.rows(), message.toString(), table.trace());
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.6g", value);
    }

    private OptionalInt motifScope(Reader reader, Optional<String> type) {
        return type.map(name -> {
            TypeDef def = reader.requireReadableType(name);
            if (!def.kind().isEdge()) {
                throw HStoreException.invalid(name + " is not a hyperedge type");
            }
            return OptionalInt.of(def.id());
        }).orElse(OptionalInt.empty());
    }

    private QueryResult motifs(Reader reader, Statement.Motifs statement) {
        MotifCensus census = MotifCensus.of(reader, motifScope(reader, statement.type()));
        MotifCensus.Result result = switch (statement.mode()) {
            case "EXACT" -> census.exact(Budget.DEFAULT.start());
            case "SAMPLE" -> census.sample(statement.samples(), statement.seed(), Budget.DEFAULT.start());
            default -> census.auto(statement.seed(), Budget.DEFAULT.maxRows());
        };
        QueryResult table = QueryResult.table(List.of("class", "shape", "count", "standard_error"), result.counts().stream()
                .map(count -> List.<Cell>of(QueryResult.number(count.code()), QueryResult.text(MotifClasses.shape(count.code())),
                        result.exact() ? QueryResult.number(Math.round(count.estimate())) : QueryResult.number(count.estimate()),
                        result.exact() ? QueryResult.number(0) : Double.isFinite(count.standardError())
                                ? QueryResult.number(count.standardError()) : QueryResult.text("unknown")))
                .toList());
        String how = result.exact()
                ? "exact count over " + result.population() + " hyperedges"
                : "estimated from " + result.samples() + " sampled hyperedges of " + result.population()
                        + (result.samples() < result.requested() && result.requested() != Long.MAX_VALUE
                        ? " (the budget allowed " + result.samples() + " of " + result.requested() + " draws)" : "");
        return new QueryResult(table.columns(), table.rows(), table.message() + "; " + how, table.trace());
    }

    private QueryResult motifProfile(Reader reader, Statement.MotifProfile statement) {
        long edge = reader.require(session.resolve(reader, statement.edge())).id();
        List<MotifCensus.Count> counts = MotifCensus.of(reader, motifScope(reader, statement.type())).profile(edge, Budget.DEFAULT.start());
        return QueryResult.table(List.of("class", "shape", "count"), counts.stream()
                .map(count -> List.<Cell>of(QueryResult.number(count.code()), QueryResult.text(MotifClasses.shape(count.code())),
                        QueryResult.number(Math.round(count.estimate()))))
                .toList());
    }

    private QueryResult motifMatch(Reader reader, Statement.MotifMatch statement) {
        List<OptionalInt> types = statement.types().stream().map(type -> motifScope(reader, type)).toList();
        OptionalLong around = statement.around().map(ref -> OptionalLong.of(reader.require(session.resolve(reader, ref)).id())).orElse(OptionalLong.empty());
        List<MotifMatcher.Region> regions = statement.regions().stream().map(region -> new MotifMatcher.Region(region.region(),
                region.memberType().map(name -> OptionalInt.of(reader.requireReadableType(name).id())).orElse(OptionalInt.empty()),
                region.member().map(ref -> OptionalLong.of(reader.require(session.resolve(reader, ref)).id())).orElse(OptionalLong.empty()),
                region.roles().stream().map(Set::copyOf).toList())).toList();
        long limit = statement.limit().orElse(1_000);
        if (limit < 1 || limit > 100_000) {
            throw HStoreException.invalid("LIMIT must be between 1 and 100000");
        }
        MotifMatcher.Query query = new MotifMatcher.Query(statement.code(), types, around, regions, statement.nested(),
                statement.rankBy().map(MotifMatcher.Weight::valueOf), (int) limit);
        MotifMatcher.Result result = MotifMatcher.find(reader, query, Budget.DEFAULT.start());
        List<String> columns = new ArrayList<>(List.of("e1", "e2", "e3"));
        statement.rankBy().ifPresent(_ -> columns.add("weight"));
        QueryResult table = QueryResult.table(columns, result.occurrences().stream().map(found -> {
            List<Cell> row = new ArrayList<>(List.of(atomCell(reader, found.first()), atomCell(reader, found.second()), atomCell(reader, found.third())));
            statement.rankBy().ifPresent(_ -> row.add(QueryResult.number(found.weight())));
            return row;
        }).toList());
        return new QueryResult(table.columns(), table.rows(), table.message() + (result.complete() ? ""
                : "; the budget ran out, so more occurrences may exist" + (statement.rankBy().isPresent() ? " and these are the best found so far" : "")),
                table.trace());
    }

    private QueryResult diffuse(Reader reader, Statement.Diffuse statement) {
        Evaluator evaluator = evaluator(reader);
        Map<Long, Double> seeds = new LinkedHashMap<>();
        statement.seeds().forEach(seed -> seeds.merge(reader.require(session.resolve(reader, seed.atom())).id(), seed.weight(), Double::sum));
        Set<Integer> types = new HashSet<>();
        Map<Integer, Double> typeWeights = new HashMap<>();
        statement.through().forEach(found -> {
            TypeDef type = reader.requireReadableType(found.type());
            if (!type.kind().isEdge()) {
                throw HStoreException.invalid(found.type() + " is not a hyperedge type");
            }
            if (!types.add(type.id())) {
                throw HStoreException.invalid("THROUGH names " + found.type() + " twice");
            }
            typeWeights.put(type.id(), found.weight());
        });
        Map<String, Double> exit = factorMap(statement.exit(), "EXIT");
        Map<String, Double> entry = factorMap(statement.entry(), "ENTER");
        OptionalLong validAt = statement.validAt().map(expr -> OptionalLong.of((long) evaluator.value(expr, Map.of()).number()
                .orElseThrow(() -> HStoreException.invalid("VALID AT needs a number or a timestamp")))).orElse(OptionalLong.empty());
        Diffusion.Model model = new Diffusion.Model(types, typeWeights, new Diffusion.RoleFactors(exit, entry), validAt,
                statement.policy().orElse(EvidencePolicy.ANY));
        Diffusion.Result result = Hora.on(reader).diffuse(seeds, model,
                new Diffusion.Params(statement.alpha(), statement.epsilon(), (int) statement.top(), (int) statement.edges()), Budget.DEFAULT.start());
        List<List<Cell>> rows = new ArrayList<>();
        result.atoms().forEach(scored -> rows.add(List.of(QueryResult.text("atom"), atomCell(reader, scored.id()),
                QueryResult.number(scored.estimate()), QueryResult.number(scored.upper()))));
        result.edges().forEach(scored -> rows.add(List.of(QueryResult.text("edge"), atomCell(reader, scored.id()),
                QueryResult.number(scored.estimate()), QueryResult.number(scored.upper()))));
        QueryResult table = QueryResult.table(List.of("kind", "id", "score", "upper"), rows);
        return new QueryResult(table.columns(), table.rows(), table.message() + "; residual " + result.residual() + ", " + result.reads()
                + " incidences read" + (result.exhausted() ? ", budget reached" : ""), table.trace());
    }

    private QueryResult refine(Reader reader, Statement.Refine statement) {
        Evaluator evaluator = evaluator(reader);
        List<Long> roots = statement.rootType().isPresent()
                ? reader.atomsOfType(reader.requireReadableType(statement.rootType().get()).id()).boxed().toList()
                : statement.roots().stream().map(ref -> session.resolve(reader, ref)).toList();
        OptionalInt rootType = statement.rootType().map(name -> OptionalInt.of(reader.requireReadableType(name).id())).orElse(OptionalInt.empty());
        List<Refinement.Level> levels = statement.levels().stream()
                .map(level -> new Refinement.Level(reader.requireReadableType(level.type()).id(), level.roles())).toList();
        OptionalInt leafType = statement.leafType().map(name -> OptionalInt.of(reader.requireReadableType(name).id())).orElse(OptionalInt.empty());
        OptionalLong anchor = statement.leafAnchor().map(ref -> OptionalLong.of(session.resolve(reader, ref))).orElse(OptionalLong.empty());
        OptionalLong validAt = statement.validAt().map(expr -> OptionalLong.of((long) evaluator.value(expr, Map.of()).number()
                .orElseThrow(() -> HStoreException.invalid("VALID AT needs a number or a timestamp")))).orElse(OptionalLong.empty());
        Refinement.Spec spec = new Refinement.Spec(roots, rootType, levels, new Refinement.LeafSpec(leafType, statement.leafRoles(), anchor), validAt,
                statement.weightLow(), statement.weightHigh(), statement.distinct() ? Refinement.Mode.DISTINCT : Refinement.Mode.ALL,
                (int) statement.top());
        Refinement.Outcome outcome = Refinement.on(reader).run(spec, Budget.DEFAULT.start());
        QueryResult result = QueryResult.table(List.of("path", "leaf", "score"), outcome.results().stream()
                .map(found -> List.of(QueryResult.text(found.path().stream().map(edge -> "@" + edge).collect(Collectors.joining(" > "))),
                        atomCell(reader, found.leaf()), QueryResult.number(found.score()))).toList());
        return outcome.prefix() ? new QueryResult(result.columns(), result.rows(), result.message() + " (budget reached; a prefix of the answer)", result.trace()) : result;
    }

    private BranchRetrieval branches() {
        return BranchRetrieval.on(database(), Budget.DEFAULT.start());
    }

    private String branchName(int id) {
        return database().engine().branches().stream().filter(branch -> branch.id() == id).map(Branch::name).findFirst().orElse("#" + id);
    }

    private BranchRetrieval.Scope scope(Reader reader, Ast.BranchScope scope) {
        return switch (scope) {
            case Ast.BranchScope.Everything _ -> new BranchRetrieval.Scope.Everything();
            case Ast.BranchScope.Incidences _ -> new BranchRetrieval.Scope.Incidences();
            case Ast.BranchScope.OfType(String type) -> new BranchRetrieval.Scope.OfType(reader.tenant(), reader.requireReadableType(type).id());
            case Ast.BranchScope.Around(Ast.Ref atom) -> new BranchRetrieval.Scope.Around(session.resolve(reader, atom));
        };
    }

    private QueryResult branchDelta(Reader reader, Statement.BranchDelta statement) {
        List<BranchRetrieval.Net> nets = branches().delta(session.branchId(statement.branch()), statement.since(), BranchRetrieval.Route.AUTO,
                scope(reader, statement.scope()));
        return QueryResult.table(List.of("key", "before", "after"), limited(nets.stream()
                .map(net -> List.<Cell>of(QueryResult.text(key(reader, net.key())), QueryResult.text(value(net.before())),
                        QueryResult.text(value(net.after())))), statement.limit()).toList());
    }

    private String key(Reader reader, BranchRetrieval.Key key) {
        return switch (key) {
            case BranchRetrieval.Key.Atom(long atom) -> "atom @" + atom;
            case BranchRetrieval.Key.Member(long edge, long member) -> "member @" + edge + " / @" + member;
            case BranchRetrieval.Key.Property(long atom, int property) -> "property @" + atom + "." + reader.schema().propertyName(property);
        };
    }

    private static String value(Optional<Object> value) {
        return value.map(found -> switch (found) {
            case BranchRetrieval.AtomValue atom -> (atom.edge() ? "edge" : "node") + " type " + atom.type()
                    + (atom.canonicalKey().isEmpty() ? "" : " '" + atom.canonicalKey() + "'");
            case io.hstore.engine.topology.Incidence incidence -> "weight " + incidence.weightValue() + " valid " + interval(incidence.validFrom(), incidence.validTo());
            case io.hstore.db.property.PropertyBag.Property property -> property.value().render();
            default -> found.toString();
        }).orElse("absent");
    }

    private HyperMatch hyperMatch(Reader reader, Statement.StructuralMatch statement) {
        return HyperMatch.on(reader).withThreads(Math.max(1, Runtime.getRuntime().availableProcessors()))
                .withMode(statement.mode().map(HyperMatch.Mode::valueOf).orElse(HyperMatch.Mode.AUTO));
    }

    private HyperQuery hyperQuery(Reader reader, Statement.StructuralMatch statement) {
        Map<String, String> types = new LinkedHashMap<>();
        for (Ast.StructuralEdge edge : statement.edges()) {
            for (Ast.StructuralVertex vertex : edge.vertices()) {
                types.putIfAbsent(vertex.name(), null);
                vertex.type().ifPresent(type -> {
                    String known = types.get(vertex.name());
                    if (known != null && !known.equals(type)) {
                        throw HStoreException.invalid("vertex " + vertex.name() + " is declared as " + known + " and " + type);
                    }
                    types.put(vertex.name(), type);
                });
            }
        }
        HyperQuery.Builder builder = HyperQuery.builder();
        types.forEach((name, type) -> {
            if (type == null) {
                throw HStoreException.invalid("vertex " + name + " needs a type the first time it is named, as " + name + ":Type");
            }
            builder.vertex(name, reader.requireReadableType(type).id());
        });
        for (Ast.StructuralEdge edge : statement.edges()) {
            TypeDef type = reader.requireReadableType(edge.type());
            if (!type.kind().isEdge()) {
                throw HStoreException.invalid(edge.type() + " is not a hyperedge type");
            }
            builder.edge(edge.name(), type.id(), edge.vertices().stream().map(Ast.StructuralVertex::name).toArray(String[]::new));
        }
        for (Ast.StructuralAnchor anchor : statement.anchors()) {
            builder.anchor(anchor.vertex(), reader.require(session.resolve(reader, anchor.atom())).id());
        }
        return builder.build();
    }

    private QueryResult structural(Reader reader, Statement.StructuralMatch statement) {
        long started = System.nanoTime();
        HyperQuery query = hyperQuery(reader, statement);
        HyperMatch match = hyperMatch(reader, statement);
        Budget.Meter meter = Budget.DEFAULT.start();
        HyperMatch.Plan plan = match.prepare(query, meter);
        if (statement.count()) {
            return QueryResult.table(List.of("count"), List.of(List.of(QueryResult.number(match.count(plan, meter)))));
        }
        long limit = statement.limit().orElse(Long.MAX_VALUE);
        List<long[]> tuples = match.tuples(plan, meter, limit);
        List<String> columns = new ArrayList<>(query.edges().stream().map(HyperQuery.Edge::name).toList());
        if (statement.bindings()) {
            columns.addAll(query.vertices().stream().map(HyperQuery.Vertex::name).toList());
        }
        List<List<Cell>> rows = new ArrayList<>();
        for (int t = 0; t < tuples.size() && rows.size() < limit; t++) {
            long[] tuple = tuples.get(t);
            List<Cell> edges = Arrays.stream(tuple).mapToObj(edge -> atomCell(reader, edge)).toList();
            if (!statement.bindings()) {
                rows.add(edges);
                continue;
            }
            Iterator<long[]> bindings = match.bindings(query, tuple);
            while (bindings.hasNext() && rows.size() < limit) {
                meter.consume(1);
                List<Cell> row = new ArrayList<>(edges);
                Arrays.stream(bindings.next()).forEach(vertex -> row.add(atomCell(reader, vertex)));
                rows.add(row);
            }
        }
        Optional<IoTrace> trace = IoTrace.current();
        return QueryResult.table(columns, rows).withTrace(new QueryResult.Trace(0, reader.generation(), match.describe(plan), Double.NaN, rows.size(),
                trace.map(IoTrace::pagesRead).orElse(0L), trace.map(IoTrace::cacheHits).orElse(0L), System.nanoTime() - started));
    }

    private QueryResult transitions(Reader reader, Statement.Transitions statement) {
        OptionalInt type = statement.type().map(name -> {
            TypeDef found = reader.requireReadableType(name);
            if (!found.kind().isEdge()) {
                throw HStoreException.invalid(name + " is not a hyperedge type");
            }
            return OptionalInt.of(found.id());
        }).orElse(OptionalInt.empty());
        long from = generation(reader, statement.from());
        long to = generation(reader, statement.to());
        if (from == to && statement.from() instanceof Ast.Instant.Time && statement.to() instanceof Ast.Instant.Time) {
            return QueryResult.table(statement.members() ? List.of("edge", "member") : List.of("edge"), List.of());
        }
        List<FeedFold.Change> changes = Transitions.of(reader, type, from, to,
                statement.appeared() ? Transitions.Direction.APPEARED : Transitions.Direction.DISAPPEARED, statement.members(),
                Budget.DEFAULT.start());
        return table(statement.members() ? List.of("edge", "member") : List.of("edge"), limited(changes.stream().map(change -> switch (change) {
            case FeedFold.Change.Membership(long edge, long member) -> List.of(atomCell(reader, edge), atomCell(reader, member));
            case FeedFold.Change.Edge(long edge) -> List.of(atomCell(reader, edge));
        }), statement.limit()));
    }

    private static long generation(Reader reader, Ast.Instant instant) {
        return switch (instant) {
            case Ast.Instant.Generation(long id) -> id;
            case Ast.Instant.Time(long millis) -> Transitions.generationAt(reader, millis)
                    .orElseThrow(() -> HStoreException.invalid("no commit at or before " + java.time.Instant.ofEpochMilli(millis)));
        };
    }

    private QueryResult rank(Reader reader, Statement.Rank statement) {
        long started = System.nanoTime();
        IoTrace trace = IoTrace.withBudget(database().queryPageBudget(session.principal()));
        Ranking ranking = Ranking.of(reader, rankQuery(reader, statement));
        List<RoleTimeScorer.EdgeScore> scores = trace.call(() -> ranking.top(Budget.DEFAULT.start()));
        List<List<Cell>> rows = scores.stream().map(scored -> List.of(atomCell(reader, scored.edge()), QueryResult.number(scored.score()),
                component(scored.components().members()), component(scored.components().roles()), component(scored.components().attributes()),
                component(scored.components().time()), component(scored.components().topology()))).toList();
        return QueryResult.table(List.of("edge", "score", "members", "roles", "attributes", "time", "topology"), rows)
                .withTrace(new QueryResult.Trace(0, reader.generation(), ranking.access(), Double.NaN, rows.size(),
                        trace.pagesRead(), trace.cacheHits(), System.nanoTime() - started));
    }

    private static Cell component(double value) {
        return Double.isNaN(value) ? QueryResult.scalar(Value.NULL) : QueryResult.number(value);
    }

    private RankQuery rankQuery(Reader reader, Statement.Rank statement) {
        Optional<TypeDef> type = statement.type().map(reader::requireReadableType);
        List<RankQuery.Slot> slots = statement.slots().stream().map(slot -> new RankQuery.Slot(switch (slot.target()) {
            case Ast.RankTarget.Atom(Ast.Ref ref) -> new RankQuery.Target.Atom(reader.require(session.resolve(reader, ref)).id());
            case Ast.RankTarget.OfType(String name) -> new RankQuery.Target.OfType(reader.requireReadableType(name));
        }, Set.copyOf(slot.roles()), slot.weight().orElse(1), slot.required())).toList();
        Evaluator evaluator = evaluator(reader);
        Optional<RankQuery.TimeSpec> time = statement.time().map(spec -> switch (spec) {
            case Ast.RankTime.At(Expr instant, var tau) -> new RankQuery.TimeSpec.At(
                    (long) evaluator.value(instant, Map.of()).number().orElseThrow(() -> HStoreException.invalid("VALID AT needs a number or a timestamp")),
                    tau.orElse(0));
            case Ast.RankTime.During(long from, long to) -> new RankQuery.TimeSpec.During(from, to);
        });
        List<RankQuery.Preference> preferences = statement.preferences().stream().map(preference -> switch (preference) {
            case Ast.RankPreference.Equals(String variable, String property, Value target, var weight) -> {
                requireRankVariable(statement, variable);
                yield (RankQuery.Preference) new RankQuery.Preference.Equals(property, target, weight.orElse(1));
            }
            case Ast.RankPreference.Near(String variable, String property, double target, double scale, var weight) -> {
                requireRankVariable(statement, variable);
                yield new RankQuery.Preference.Near(property, target, scale, weight.orElse(1));
            }
        }).toList();
        RankQuery.Weights defaults = RankQuery.Weights.DEFAULT;
        Map<String, Double> given = statement.weights();
        Set<String> unknown = new TreeSet<>(given.keySet());
        unknown.removeAll(Set.of("members", "roles", "attributes", "time", "topology"));
        if (!unknown.isEmpty()) {
            throw HStoreException.invalid("unknown component " + unknown + "; expected members, roles, attributes, time or topology");
        }
        RankQuery.Weights weights = new RankQuery.Weights(given.getOrDefault("members", defaults.members()),
                given.getOrDefault("roles", defaults.roles()), given.getOrDefault("attributes", defaults.attributes()),
                given.getOrDefault("time", defaults.time()), given.getOrDefault("topology", defaults.topology()));
        if (statement.top() > RankQuery.MAX_TOP) {
            throw HStoreException.invalid("TOP must be between 1 and " + RankQuery.MAX_TOP);
        }
        return new RankQuery(type, slots, time, preferences, statement.size(), weights, (int) Math.max(0, statement.top()));
    }

    private static void requireRankVariable(Statement.Rank statement, String variable) {
        if (!variable.equals(statement.variable())) {
            throw HStoreException.invalid("unknown variable " + variable + "; RANK binds only " + statement.variable());
        }
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
        writer.loadIncidences(created, result.values().toList());
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
            case ViewCell.Census(long estimate, long error, boolean exact) -> exact ? Long.toString(estimate)
                    : estimate + " ± " + (error < 0 ? "?" : String.format(java.util.Locale.ROOT, "%.1f", error / 1_000_000.0));
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
        CheckpointThreshold threshold = database().engine().checkpointThreshold();
        metrics.put("checkpoint after wal bytes", QueryResult.number(threshold.bytes()));
        metrics.put("checkpoint threshold", QueryResult.text(threshold.source().name().toLowerCase()));
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
            case Field.Weight _ -> reader.hyperedge(atom).map(edge -> edge.summary().weightSum() / 1e9).orElse(1.0);
        };
    }

    private QueryResult numericTable(Reader reader, String column, Map<Long, Double> values) {
        return table(List.of(column, "value"), values.entrySet().stream()
                .map(entry -> List.of(atomCell(reader, entry.getKey()), QueryResult.number(entry.getValue()))));
    }

    private static QueryResult scalarResult(String column, Value value) {
        return QueryResult.table(List.of(column), List.of(List.of(QueryResult.scalar(value))));
    }

    private static int depth(int depth) {
        if (depth < 1 || depth > MAX_DEPTH) {
            throw HStoreException.invalid("DEPTH must be between 1 and " + MAX_DEPTH);
        }
        return depth;
    }

    private static Budget budget(OptionalLong limit) {
        return limit.isPresent() ? Budget.DEFAULT.withRows(Math.min(limit.getAsLong(), Budget.DEFAULT.maxRows())) : Budget.DEFAULT;
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

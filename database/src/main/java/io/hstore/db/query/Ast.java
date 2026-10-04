package io.hstore.db.query;

import io.hstore.db.evidence.AssertionType;
import io.hstore.db.evidence.EvidencePolicy;
import io.hstore.db.hora.Field;
import io.hstore.db.hora.Pattern;
import io.hstore.db.hora.Reducer;
import io.hstore.db.schema.AtomKind;
import io.hstore.db.schema.TypeDef.PropertyDef;
import io.hstore.db.security.Role;
import io.hstore.db.semantic.SemanticPlane;
import io.hstore.db.temporal.BranchMerge;
import io.hstore.db.value.TypeTag;
import io.hstore.db.value.Value;
import io.hstore.db.view.MaterializedViews;
import io.hstore.engine.topology.Validity;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

public final class Ast {

    private Ast() {
    }

    public sealed interface Ref {
        record Id(long id) implements Ref {
        }

        record Keyed(String type, String key) implements Ref {
        }

        record Variable(String name) implements Ref {
        }
    }

    public enum Comparison { EQ, NE, LT, LE, GT, GE }

    public enum SetOp { INTERSECT, UNION, DIFFERENCE, SUBSET, EQUAL, JACCARD, CONTAINMENT, OVERLAP }

    public sealed interface Expr {
        record Literal(Value value) implements Expr {
        }

        record Atom(Ref ref) implements Expr {
        }

        record Var(String name) implements Expr {
        }

        record Prop(String variable, String property) implements Expr {
        }

        record Call(String function, List<Expr> arguments) implements Expr {
        }

        record Star() implements Expr {
        }

        record Compare(Comparison op, Expr left, Expr right) implements Expr {
        }

        record Arithmetic(char op, Expr left, Expr right) implements Expr {
        }

        record And(List<Expr> terms) implements Expr {
        }

        record Or(List<Expr> terms) implements Expr {
        }

        record Not(Expr term) implements Expr {
        }

        record Between(Expr value, Expr low, Expr high) implements Expr {
        }

        record Contains(String variable, List<Ref> members) implements Expr {
        }

        record Has(String variable, Ref member, Optional<String> role) implements Expr {
        }

        record In(String variable, Ref edge) implements Expr {
        }

        record Similar(String variable, String text, int k, SemanticPlane.Consistency consistency) implements Expr {
        }

        record ValidAt(String variable, Expr instant) implements Expr {
        }
    }

    public record WeightRange(double low, double high) {
    }

    public record MemberClause(Ref member, List<String> roles, OptionalDouble weight, Validity validity) {
    }

    public record Projection(Expr expr, String alias) {
    }

    public record Match(boolean edges, String variable, Optional<String> type, Optional<Expr> where,
                        List<Projection> projections, Optional<Expr> orderBy, boolean descending, OptionalLong limit) {
    }

    public sealed interface Statement {
        record Begin(boolean serializable) implements Statement {
        }

        record Commit() implements Statement {
        }

        record Rollback() implements Statement {
        }

        record UseBranch(Optional<String> name) implements Statement {
        }

        record At(long generation, Statement inner) implements Statement {
        }

        record AsOf(long wallTime, Statement inner) implements Statement {
        }

        record CreateType(String name, AtomKind kind, List<PropertyDef> properties, List<String> roles) implements Statement {
        }

        record CreateIndex(String type, String property) implements Statement {
        }

        record CreateJsonIndex(String type, String path, TypeTag tag) implements Statement {
        }

        record CreateView(String name, MaterializedViews.Kind kind, long parameter, boolean continuous) implements Statement {
        }

        record RefreshView(String name) implements Statement {
        }

        record ShowView(String name, Optional<Ref> key, OptionalLong limit) implements Statement {
        }

        record CreateBranch(String name, Optional<String> from) implements Statement {
        }

        record DropBranch(String name) implements Statement {
        }

        record MergeBranch(String source, Optional<String> target, BranchMerge.Policy policy) implements Statement {
        }

        record CreateTenant(String name, Map<String, Long> quota) implements Statement {
        }

        record AlterTenant(String name, Map<String, Long> quota) implements Statement {
        }

        record CreateUser(String name, String password, Optional<String> tenant, Role role) implements Statement {
        }

        record AlterUser(String name, String password) implements Statement {
        }

        record DropUser(String name) implements Statement {
        }

        record UseTenant(String name) implements Statement {
        }

        record Authenticate(String user, String password) implements Statement {
        }

        record WhoAmI() implements Statement {
        }

        record ShowTenants() implements Statement {
        }

        record ShowUsers() implements Statement {
        }

        record SetFormat(String format) implements Statement {
        }

        record InsertNode(String type, Optional<String> key, Map<String, Value> properties, Optional<String> bind) implements Statement {
        }

        record InsertEdge(String type, Map<String, Value> properties, List<MemberClause> members, Optional<String> bind) implements Statement {
        }

        record Add(MemberClause member, Ref edge, OptionalLong at) implements Statement {
        }

        record Remove(Ref member, Ref edge) implements Statement {
        }

        record RemoveAt(long index, Ref edge) implements Statement {
        }

        record SetProperty(Ref atom, String property, Value value, Validity validity) implements Statement {
        }

        record Unset(Ref atom, String property) implements Statement {
        }

        record Document(Ref atom, String json) implements Statement {
        }

        record EmbedText(Ref atom, String text) implements Statement {
        }

        record EmbedVector(Ref atom, String model, float[] vector) implements Statement {
        }

        record BindState(Ref atom, Value value, String schema, Validity validity) implements Statement {
        }

        record Delete(Ref atom) implements Statement {
        }

        record Qualify(Ref member, Ref edge, AssertionType type, double confidence, List<Ref> evidence, Optional<String> bind) implements Statement {
        }

        record RecordEvidence(String creator, Optional<Ref> source, Map<String, String> attributes, Optional<String> bind) implements Statement {
        }

        record Trace(Ref assertion, int depth) implements Statement {
        }

        record RegisterSignal(Ref atom, String clock, long from, long to, long resolution, String schema) implements Statement {
        }

        record ResolveSignals(List<Ref> edges, long from, long to) implements Statement {
        }

        record Query(Match match) implements Statement {
        }

        record Explain(Match match) implements Statement {
        }

        record Members(Ref edge, Optional<String> role, Optional<WeightRange> weight, Optional<Expr> validAt,
                       Optional<EvidencePolicy> policy, OptionalLong limit) implements Statement {
        }

        record IncidentTo(Ref atom, Optional<Expr> validAt) implements Statement {
        }

        record Describe(Ref atom) implements Statement {
        }

        record SetAlgebra(SetOp op, Ref left, Ref right, Optional<String> into) implements Statement {
        }

        record Expand(Ref atom, int depth, OptionalLong limit) implements Statement {
        }

        record Gather(Field field, Ref edge) implements Statement {
        }

        record Reduce(Reducer reducer, Field field, Ref edge, boolean weighted) implements Statement {
        }

        record Scatter(Field field, String edgeType, boolean weighted) implements Statement {
        }

        record Propagate(Field field, String edgeType, boolean weighted) implements Statement {
        }

        record Neighbors(Ref atom, int threshold, OptionalLong limit) implements Statement {
        }

        record OverlapJoin(String edgeType, long threshold, OptionalLong limit) implements Statement {
        }

        record Closure(Ref edge, int dimension, OptionalLong limit) implements Statement {
        }

        record PatternQuery(List<Pattern.Variable> variables, List<Pattern.Constraint> constraints, Map<String, Ref> bindings,
                            OptionalLong limit) implements Statement {
        }

        record Sample(int swaps, String edgeType, long seed, String branch) implements Statement {
        }

        record DiffGenerations(long before, long after) implements Statement {
        }

        record DiffBranches(String before, String after) implements Statement {
        }

        record History(OptionalLong limit) implements Statement {
        }

        record Stats() implements Statement {
        }

        record ShowTypes() implements Statement {
        }

        record ShowBranches() implements Statement {
        }

        record ShowViews() implements Statement {
        }

        record Checkpoint() implements Statement {
        }

        record Compact() implements Statement {
        }
    }
}

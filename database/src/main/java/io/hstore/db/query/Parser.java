// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.query;

import io.hstore.db.evidence.AssertionType;
import io.hstore.db.evidence.EvidencePolicy;
import io.hstore.db.hora.Field;
import io.hstore.db.hora.Pattern;
import io.hstore.db.hora.Reducer;
import io.hstore.db.query.Ast.Comparison;
import io.hstore.db.query.Ast.Expr;
import io.hstore.db.query.Ast.MemberClause;
import io.hstore.db.query.Ast.Projection;
import io.hstore.db.query.Ast.Ref;
import io.hstore.db.query.Ast.SetOp;
import io.hstore.db.query.Ast.Statement;
import io.hstore.db.schema.AtomKind;
import io.hstore.db.schema.TypeDef.PropertyDef;
import io.hstore.db.security.Role;
import io.hstore.db.semantic.SemanticPlane;
import io.hstore.db.temporal.BranchMerge;
import io.hstore.db.value.TypeTag;
import io.hstore.db.value.Value;
import io.hstore.db.view.MaterializedViews;
import io.hstore.engine.HStoreException;
import io.hstore.engine.topology.Validity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;

public final class Parser {

    private final List<Token> tokens;
    private int index;

    public record Sourced(Statement statement, String text) {
    }

    private Parser(String source) {
        this.tokens = Lexer.tokenize(source);
    }

    public static List<Statement> script(String source) {
        return sourced(source).stream().map(Sourced::statement).toList();
    }

    public static List<Sourced> sourced(String source) {
        Parser parser = new Parser(source);
        List<Sourced> statements = new ArrayList<>();
        while (!parser.atEnd()) {
            if (parser.acceptSymbol(";")) {
                continue;
            }
            int start = parser.peek().position();
            Statement statement = parser.statement();
            int end = parser.atEnd() ? source.length() : parser.peek().position();
            statements.add(new Sourced(statement, source.substring(start, Math.max(start, end)).strip()));
            if (!parser.atEnd()) {
                parser.expectSymbol(";");
            }
        }
        return statements;
    }

    public static Statement parse(String source) {
        List<Statement> statements = script(source);
        if (statements.size() != 1) {
            throw HStoreException.invalid("expected exactly one statement, found " + statements.size());
        }
        return statements.getFirst();
    }

    private Statement statement() {
        if (accept("BEGIN")) {
            return new Statement.Begin(accept("SERIALIZABLE"));
        }
        if (accept("COMMIT")) {
            return new Statement.Commit();
        }
        if (accept("ROLLBACK") || accept("ABORT")) {
            return new Statement.Rollback();
        }
        if (accept("USE")) {
            if (accept("TENANT")) {
                return new Statement.UseTenant(identifier());
            }
            if (accept("MAIN")) {
                return new Statement.UseBranch(Optional.empty());
            }
            expect("BRANCH");
            return new Statement.UseBranch(Optional.of(identifier()));
        }
        if (accept("AT")) {
            expect("GENERATION");
            return new Statement.At(longValue(), statement());
        }
        if (peek().is("AS") && peek(1).is("OF")) {
            index += 2;
            return new Statement.AsOf(instant(), statement());
        }
        if (accept("CREATE")) {
            return create();
        }
        if (accept("MERGE")) {
            expect("BRANCH");
            String source = identifier();
            Optional<String> target = accept("INTO") ? Optional.of(identifier()) : Optional.empty();
            BranchMerge.Policy policy = BranchMerge.Policy.FAIL;
            if (accept("ON")) {
                expect("CONFLICT");
                policy = BranchMerge.Policy.valueOf(identifier().toUpperCase());
            }
            return new Statement.MergeBranch(source, target, policy);
        }
        if (accept("AUTHENTICATE")) {
            String user = identifierOrString();
            expect("PASSWORD");
            return new Statement.Authenticate(user, string());
        }
        if (accept("WHOAMI")) {
            return new Statement.WhoAmI();
        }
        if (accept("ALTER")) {
            if (accept("TENANT")) {
                String name = identifier();
                expect("QUOTA");
                return new Statement.AlterTenant(name, quota());
            }
            expect("USER");
            String name = identifierOrString();
            expect("PASSWORD");
            return new Statement.AlterUser(name, string());
        }
        if (peek().is("FORMAT")) {
            index++;
            return new Statement.SetFormat(identifier().toLowerCase());
        }
        if (accept("DROP")) {
            if (accept("USER")) {
                return new Statement.DropUser(identifierOrString());
            }
            expect("BRANCH");
            return new Statement.DropBranch(identifier());
        }
        if (accept("REFRESH")) {
            expect("VIEW");
            return new Statement.RefreshView(identifier());
        }
        if (accept("VIEW")) {
            String name = identifier();
            Optional<Ref> key = accept("KEY") ? Optional.of(refOrNumber()) : Optional.empty();
            return new Statement.ShowView(name, key, limit());
        }
        if (accept("INSERT")) {
            return insert();
        }
        if (accept("ADD")) {
            MemberClause member = memberClause();
            expect("TO");
            Ref edge = ref();
            if (member.roles().isEmpty() && member.weight().isEmpty() && member.validity().equals(Validity.ALWAYS)) {
                member = modifiers(member.member());
            }
            OptionalLong at = accept("AT") ? OptionalLong.of(longValue()) : OptionalLong.empty();
            return new Statement.Add(member, edge, at);
        }
        if (accept("REMOVE")) {
            if (accept("AT")) {
                long position = longValue();
                expect("FROM");
                return new Statement.RemoveAt(position, ref());
            }
            Ref member = ref();
            expect("FROM");
            return new Statement.Remove(member, ref());
        }
        if (accept("SET")) {
            Ref atom = ref();
            expectSymbol(".");
            String property = identifier();
            expectSymbol("=");
            Value value = literal();
            return new Statement.SetProperty(atom, property, value, validity());
        }
        if (accept("UNSET")) {
            Ref atom = ref();
            expectSymbol(".");
            return new Statement.Unset(atom, identifier());
        }
        if (accept("DOCUMENT")) {
            return new Statement.Document(ref(), string());
        }
        if (accept("EMBED")) {
            Ref atom = ref();
            if (accept("TEXT")) {
                return new Statement.EmbedText(atom, string());
            }
            expect("VECTOR");
            float[] vector = vector();
            expect("MODEL");
            return new Statement.EmbedVector(atom, identifierOrString(), vector);
        }
        if (accept("STATE")) {
            Ref atom = ref();
            expectSymbol("=");
            Value value = literal();
            String schema = accept("SCHEMA") ? identifierOrString() : "default";
            return new Statement.BindState(atom, value, schema, validity());
        }
        if (accept("DELETE")) {
            return new Statement.Delete(ref());
        }
        if (accept("QUALIFY")) {
            return qualify();
        }
        if (accept("EVIDENCE")) {
            String creator = string();
            Optional<Ref> source = accept("SOURCE") ? Optional.of(ref()) : Optional.empty();
            Map<String, String> attributes = new LinkedHashMap<>();
            if (accept("ATTRIBUTES")) {
                properties().forEach((key, value) -> attributes.put(key, value instanceof Value.Text(String text) ? text : value.render()));
            }
            return new Statement.RecordEvidence(creator, source, attributes, binding());
        }
        if (accept("TRACE")) {
            Ref assertion = ref();
            int depth = accept("DEPTH") ? (int) longValue() : 8;
            return new Statement.Trace(assertion, depth);
        }
        if (accept("SIGNAL")) {
            expect("ON");
            Ref atom = ref();
            expect("CLOCK");
            String clock = identifierOrString();
            expect("FROM");
            long from = instant();
            expect("TO");
            long to = instant();
            long resolution = accept("RESOLUTION") ? longValue() : 1;
            String schema = accept("SCHEMA") ? identifierOrString() : "series";
            return new Statement.RegisterSignal(atom, clock, from, to, resolution, schema);
        }
        if (accept("SIGNALS")) {
            expect("FOR");
            List<Ref> edges = new ArrayList<>(List.of(ref()));
            while (acceptSymbol(",")) {
                edges.add(ref());
            }
            expect("DURING");
            Validity interval = interval();
            return new Statement.ResolveSignals(edges, interval.from(), interval.to());
        }
        if (accept("MATCH")) {
            return new Statement.Query(match());
        }
        if (accept("EXPLAIN")) {
            expect("MATCH");
            return new Statement.Explain(match());
        }
        if (accept("MEMBERS")) {
            expect("OF");
            Ref edge = ref();
            Optional<String> role = accept("ROLE") ? Optional.of(identifier()) : Optional.empty();
            Optional<Ast.WeightRange> weight = Optional.empty();
            if (accept("WEIGHT")) {
                expect("BETWEEN");
                double low = number().doubleValue();
                expect("AND");
                weight = Optional.of(new Ast.WeightRange(low, number().doubleValue()));
            }
            Optional<Expr> validAt = validAt();
            Optional<EvidencePolicy> policy = accept("POLICY") ? Optional.of(policy()) : Optional.empty();
            return new Statement.Members(edge, role, weight, validAt, policy, limit());
        }
        if (accept("INCIDENT")) {
            expect("TO");
            return new Statement.IncidentTo(ref(), validAt());
        }
        if (accept("DESCRIBE")) {
            return new Statement.Describe(ref());
        }
        if (peek().is("OVERLAP") && peek(1).is("JOIN")) {
            index += 2;
            String type = identifier();
            expect("THRESHOLD");
            return new Statement.OverlapJoin(type, longValue(), limit());
        }
        for (SetOp op : SetOp.values()) {
            if (accept(op.name())) {
                Ref left = ref();
                acceptSymbol(",");
                Ref right = ref();
                return new Statement.SetAlgebra(op, left, right, accept("INTO") ? Optional.of(variable()) : Optional.empty());
            }
        }
        if (accept("EXPAND")) {
            Ref atom = ref();
            int depth = accept("DEPTH") ? (int) longValue() : 4;
            return new Statement.Expand(atom, depth, limit());
        }
        if (accept("GATHER")) {
            Field field = field();
            expect("FROM");
            return new Statement.Gather(field, ref());
        }
        if (accept("REDUCE")) {
            Reducer reducer = Reducer.parse(identifier());
            expectSymbol("(");
            Field field = field();
            expectSymbol(")");
            expect("OVER");
            Ref edge = ref();
            return new Statement.Reduce(reducer, field, edge, accept("WEIGHTED"));
        }
        if (accept("SCATTER")) {
            Field field = field();
            expect("FROM");
            expect("EDGES");
            String type = identifier();
            return new Statement.Scatter(field, type, accept("WEIGHTED"));
        }
        if (accept("PROPAGATE")) {
            Field field = field();
            expect("OVER");
            expect("EDGES");
            String type = identifier();
            return new Statement.Propagate(field, type, accept("WEIGHTED"));
        }
        if (accept("NEIGHBORS")) {
            expect("OF");
            Ref atom = ref();
            int threshold = accept("THRESHOLD") ? (int) longValue() : 1;
            return new Statement.Neighbors(atom, threshold, limit());
        }
        if (accept("CLOSURE")) {
            Ref edge = ref();
            expect("DIM");
            return new Statement.Closure(edge, (int) longValue(), limit());
        }
        if (accept("PATTERN")) {
            return pattern();
        }
        if (accept("SAMPLE")) {
            int swaps = (int) longValue();
            expect("SWAPS");
            expect("ON");
            String type = identifier();
            long seed = accept("SEED") ? longValue() : System.nanoTime();
            expect("INTO");
            expect("BRANCH");
            return new Statement.Sample(swaps, type, seed, identifier());
        }
        if (accept("DIFF")) {
            if (accept("GENERATION")) {
                long before = longValue();
                expect("AND");
                return new Statement.DiffGenerations(before, longValue());
            }
            expect("BRANCH");
            String before = identifier();
            expect("AND");
            return new Statement.DiffBranches(before, identifier());
        }
        if (accept("HISTORY")) {
            return new Statement.History(limit());
        }
        if (accept("STATS")) {
            return new Statement.Stats();
        }
        if (accept("SHOW")) {
            if (accept("TYPES")) {
                return new Statement.ShowTypes();
            }
            if (accept("BRANCHES")) {
                return new Statement.ShowBranches();
            }
            if (accept("TENANTS")) {
                return new Statement.ShowTenants();
            }
            if (accept("USERS")) {
                return new Statement.ShowUsers();
            }
            expect("VIEWS");
            return new Statement.ShowViews();
        }
        if (accept("CHECKPOINT")) {
            return new Statement.Checkpoint();
        }
        if (accept("COMPACT")) {
            return new Statement.Compact();
        }
        throw error("unknown statement");
    }

    private Statement create() {
        if (accept("TENANT")) {
            String name = identifier();
            return new Statement.CreateTenant(name, accept("QUOTA") ? quota() : Map.of());
        }
        if (accept("USER")) {
            String name = identifierOrString();
            expect("PASSWORD");
            String password = string();
            Optional<String> tenant = accept("TENANT") ? Optional.of(identifier()) : Optional.empty();
            Role role = accept("ROLE") ? Role.valueOf(identifier().toUpperCase()) : Role.WRITER;
            return new Statement.CreateUser(name, password, tenant, role);
        }
        if (accept("BRANCH")) {
            String name = identifier();
            return new Statement.CreateBranch(name, accept("FROM") ? Optional.of(identifier()) : Optional.empty());
        }
        if (accept("INDEX")) {
            expect("ON");
            String type = identifier();
            expectSymbol("(");
            String property = identifier();
            expectSymbol(")");
            return new Statement.CreateIndex(type, property);
        }
        if (accept("JSON")) {
            expect("INDEX");
            expect("ON");
            String type = identifier();
            expectSymbol("(");
            String path = string();
            expectSymbol(")");
            expect("AS");
            return new Statement.CreateJsonIndex(type, path, TypeTag.parse(identifier()));
        }
        if (accept("VIEW")) {
            String name = identifier();
            expect("AS");
            MaterializedViews.Kind kind;
            long parameter = 0;
            if (accept("DEGREE")) {
                kind = MaterializedViews.Kind.DEGREE;
            } else if (accept("CARDINALITY")) {
                kind = MaterializedViews.Kind.CARDINALITY;
            } else if (accept("ACTIVITY")) {
                kind = MaterializedViews.Kind.ACTIVITY;
                parameter = accept("BUCKET") ? longValue() : 60_000;
            } else {
                expect("OVERLAP");
                expect("TOP");
                kind = MaterializedViews.Kind.OVERLAP_TOP_K;
                parameter = longValue();
            }
            return new Statement.CreateView(name, kind, parameter, accept("CONTINUOUS"));
        }
        AtomKind kind;
        if (accept("NODE")) {
            kind = AtomKind.NODE;
        } else if (accept("ORDERED")) {
            kind = AtomKind.ORDERED_EDGE;
            expect("EDGE");
        } else {
            expect("SET");
            kind = AtomKind.SET_EDGE;
            expect("EDGE");
        }
        expect("TYPE");
        String name = identifier();
        List<PropertyDef> properties = new ArrayList<>();
        if (acceptSymbol("(")) {
            do {
                String property = identifier();
                TypeTag tag = TypeTag.parse(identifier());
                boolean indexed = false;
                boolean required = false;
                while (peek().is("INDEXED") || peek().is("REQUIRED")) {
                    indexed |= accept("INDEXED");
                    required |= accept("REQUIRED");
                }
                properties.add(new PropertyDef(property, tag, indexed, required));
            } while (acceptSymbol(","));
            expectSymbol(")");
        }
        List<String> roles = new ArrayList<>();
        if (accept("ROLES")) {
            expectSymbol("(");
            do {
                roles.add(identifier());
            } while (acceptSymbol(","));
            expectSymbol(")");
        }
        return new Statement.CreateType(name, kind, properties, roles);
    }

    private Map<String, Long> quota() {
        expectSymbol("(");
        Map<String, Long> limits = new LinkedHashMap<>();
        do {
            String name = identifier();
            limits.put(name, number().longValue());
        } while (acceptSymbol(","));
        expectSymbol(")");
        return limits;
    }

    private Statement insert() {
        if (accept("NODE")) {
            String type = identifier();
            Optional<String> key = peek().kind() == Token.Kind.STRING ? Optional.of(string()) : Optional.empty();
            Map<String, Value> properties = peek().is("{") ? properties() : Map.of();
            return new Statement.InsertNode(type, key, properties, binding());
        }
        expect("EDGE");
        String type = identifier();
        Map<String, Value> properties = peek().is("{") ? properties() : Map.of();
        List<MemberClause> members = new ArrayList<>();
        if (accept("MEMBERS")) {
            expectSymbol("(");
            if (!peek().is(")")) {
                do {
                    members.add(memberClause());
                } while (acceptSymbol(","));
            }
            expectSymbol(")");
        }
        return new Statement.InsertEdge(type, properties, members, binding());
    }

    private Statement qualify() {
        Ref member = ref();
        expect("IN");
        Ref edge = ref();
        expect("AS");
        AssertionType type = AssertionType.valueOf(identifier().toUpperCase());
        double confidence = accept("CONFIDENCE") ? number().doubleValue() : 1.0;
        List<Ref> evidence = new ArrayList<>();
        if (accept("EVIDENCE")) {
            expectSymbol("(");
            do {
                evidence.add(ref());
            } while (acceptSymbol(","));
            expectSymbol(")");
        }
        return new Statement.Qualify(member, edge, type, confidence, evidence, binding());
    }

    private Statement pattern() {
        List<Pattern.Variable> variables = new ArrayList<>();
        expectSymbol("(");
        do {
            String name = identifier();
            expectSymbol(":");
            boolean edge = accept("EDGE");
            if (!edge) {
                expect("NODE");
            }
            Optional<String> type = peek().kind() == Token.Kind.WORD ? Optional.of(identifier()) : Optional.empty();
            variables.add(new Pattern.Variable(name, edge, type));
        } while (acceptSymbol(","));
        expectSymbol(")");
        List<Pattern.Constraint> constraints = new ArrayList<>();
        Map<String, Ref> bindings = new LinkedHashMap<>();
        if (accept("WHERE")) {
            do {
                patternConstraint(constraints, bindings);
            } while (accept("AND"));
        }
        return new Statement.PatternQuery(variables, constraints, bindings, limit());
    }

    private void patternConstraint(List<Pattern.Constraint> constraints, Map<String, Ref> bindings) {
        if (accept("CARD")) {
            expectSymbol("(");
            String edge = identifier();
            expectSymbol(")");
            Bounds bounds = bounds();
            constraints.add(new Pattern.Constraint.Cardinality(edge, bounds.min(), bounds.max()));
            return;
        }
        if (accept("SHARED")) {
            expectSymbol("(");
            String left = identifier();
            expectSymbol(",");
            String right = identifier();
            expectSymbol(")");
            Bounds bounds = bounds();
            constraints.add(new Pattern.Constraint.Shares(left, right, bounds.min(), bounds.max()));
            return;
        }
        String subject = identifier();
        if (accept("CONTAINS")) {
            constraints.add(new Pattern.Constraint.Contains(subject, identifier()));
        } else if (accept("HAS")) {
            String member = identifier();
            expect("AS");
            constraints.add(new Pattern.Constraint.HasRole(subject, member, identifier()));
        } else if (accept("SUBSET")) {
            constraints.add(new Pattern.Constraint.SubsetOf(subject, identifier()));
        } else if (accept("VALID")) {
            expect("AT");
            constraints.add(new Pattern.Constraint.ValidAt(subject, instant()));
        } else if (acceptSymbol("!=") || acceptSymbol("<>")) {
            constraints.add(new Pattern.Constraint.Distinct(subject, identifier()));
        } else {
            expectSymbol("=");
            bindings.put(subject, ref());
        }
    }

    private record Bounds(long min, long max) {
    }

    private Bounds bounds() {
        if (accept("BETWEEN")) {
            long low = longValue();
            expect("AND");
            return new Bounds(low, longValue());
        }
        if (acceptSymbol(">=")) {
            return new Bounds(longValue(), Long.MAX_VALUE);
        }
        if (acceptSymbol(">")) {
            return new Bounds(longValue() + 1, Long.MAX_VALUE);
        }
        if (acceptSymbol("<=")) {
            return new Bounds(0, longValue());
        }
        if (acceptSymbol("<")) {
            return new Bounds(0, longValue() - 1);
        }
        expectSymbol("=");
        long exact = longValue();
        return new Bounds(exact, exact);
    }

    private Ast.Match match() {
        boolean edges;
        if (accept("EDGE") || accept("EDGES")) {
            edges = true;
        } else if (accept("NODE") || accept("NODES")) {
            edges = false;
        } else {
            throw error("expected NODE or EDGE");
        }
        String variable = identifier();
        Optional<String> type = acceptSymbol(":") ? Optional.of(identifier()) : Optional.empty();
        Optional<Expr> where = accept("WHERE") ? Optional.of(expression()) : Optional.empty();
        List<Projection> projections = new ArrayList<>();
        if (accept("RETURN")) {
            do {
                Expr expr = expression();
                String alias = accept("AS") ? identifier() : render(expr);
                projections.add(new Projection(expr, alias));
            } while (acceptSymbol(","));
        } else {
            projections.add(new Projection(new Expr.Var(variable), variable));
        }
        Optional<Expr> order = Optional.empty();
        boolean descending = false;
        if (accept("ORDER")) {
            expect("BY");
            order = Optional.of(expression());
            descending = accept("DESC");
            if (!descending) {
                accept("ASC");
            }
        }
        return new Ast.Match(edges, variable, type, where, projections, order, descending, limit());
    }

    private Expr expression() {
        List<Expr> terms = new ArrayList<>(List.of(conjunction()));
        while (accept("OR")) {
            terms.add(conjunction());
        }
        return terms.size() == 1 ? terms.getFirst() : new Expr.Or(terms);
    }

    private Expr conjunction() {
        List<Expr> terms = new ArrayList<>(List.of(negation()));
        while (accept("AND")) {
            terms.add(negation());
        }
        return terms.size() == 1 ? terms.getFirst() : new Expr.And(terms);
    }

    private Expr negation() {
        return accept("NOT") ? new Expr.Not(negation()) : predicate();
    }

    private Expr predicate() {
        if (peek().kind() == Token.Kind.WORD && !isKeywordLiteral(peek())) {
            Token subject = peek();
            Token following = peek(1);
            if (following.is("CONTAINS")) {
                index += 2;
                expectSymbol("(");
                List<Ref> members = new ArrayList<>();
                do {
                    members.add(ref());
                } while (acceptSymbol(","));
                expectSymbol(")");
                return new Expr.Contains(subject.text(), members);
            }
            if (following.is("HAS")) {
                index += 2;
                Ref member = ref();
                return new Expr.Has(subject.text(), member, accept("AS") ? Optional.of(identifier()) : Optional.empty());
            }
            if (following.is("IN")) {
                index += 2;
                return new Expr.In(subject.text(), ref());
            }
            if (following.is("SIMILAR")) {
                index += 2;
                expect("TO");
                String text = string();
                int k = accept("TOP") ? (int) longValue() : 10;
                SemanticPlane.Consistency consistency = accept("SNAPSHOT") ? SemanticPlane.Consistency.SNAPSHOT
                        : optionalKeyword("FRESH", SemanticPlane.Consistency.FRESH);
                return new Expr.Similar(subject.text(), text, k, consistency);
            }
            if (following.is("VALID")) {
                index += 2;
                expect("AT");
                return new Expr.ValidAt(subject.text(), additive());
            }
        }
        Expr left = additive();
        if (accept("BETWEEN")) {
            Expr low = additive();
            expect("AND");
            return new Expr.Between(left, low, additive());
        }
        Optional<Comparison> comparison = comparison();
        return comparison.<Expr>map(op -> new Expr.Compare(op, left, additive())).orElse(left);
    }

    private Optional<Comparison> comparison() {
        Token token = peek();
        Comparison op = switch (token.text()) {
            case "=" -> Comparison.EQ;
            case "!=", "<>" -> Comparison.NE;
            case "<" -> Comparison.LT;
            case "<=" -> Comparison.LE;
            case ">" -> Comparison.GT;
            case ">=" -> Comparison.GE;
            default -> null;
        };
        if (op == null || token.kind() != Token.Kind.SYMBOL) {
            return Optional.empty();
        }
        index++;
        return Optional.of(op);
    }

    private Expr additive() {
        Expr left = multiplicative();
        while (peek().is("+") || peek().is("-")) {
            char op = next().text().charAt(0);
            left = new Expr.Arithmetic(op, left, multiplicative());
        }
        return left;
    }

    private Expr multiplicative() {
        Expr left = primary();
        while (peek().is("*") || peek().is("/")) {
            char op = next().text().charAt(0);
            left = new Expr.Arithmetic(op, left, primary());
        }
        return left;
    }

    private Expr primary() {
        Token token = peek();
        if (acceptSymbol("(")) {
            Expr inner = expression();
            expectSymbol(")");
            return inner;
        }
        if (token.is("@") || token.kind() == Token.Kind.VARIABLE) {
            return new Expr.Atom(ref());
        }
        if (token.kind() == Token.Kind.NUMBER || token.kind() == Token.Kind.STRING || isKeywordLiteral(token) || token.is("-")) {
            return new Expr.Literal(literal());
        }
        if (token.is("*")) {
            index++;
            return new Expr.Star();
        }
        String name = identifier();
        if (acceptSymbol("(")) {
            List<Expr> arguments = new ArrayList<>();
            if (!peek().is(")")) {
                do {
                    arguments.add(expression());
                } while (acceptSymbol(","));
            }
            expectSymbol(")");
            return new Expr.Call(name.toLowerCase(), arguments);
        }
        if (acceptSymbol(".")) {
            return new Expr.Prop(name, identifier());
        }
        return new Expr.Var(name);
    }

    private boolean isKeywordLiteral(Token token) {
        return token.is("TRUE") || token.is("FALSE") || token.is("NULL") || token.is("TIMESTAMP") || token.is("DECIMAL");
    }

    private MemberClause memberClause() {
        return modifiers(ref());
    }

    private MemberClause modifiers(Ref member) {
        List<String> roles = new ArrayList<>();
        if (accept("AS")) {
            do {
                roles.add(identifier());
            } while (acceptSymbol("|"));
        }
        OptionalDouble weight = accept("WEIGHT") ? OptionalDouble.of(number().doubleValue()) : OptionalDouble.empty();
        return new MemberClause(member, roles, weight, validity());
    }

    private Validity validity() {
        return accept("VALID") ? interval() : Validity.ALWAYS;
    }

    private Validity interval() {
        expectSymbol("[");
        long from = acceptSymbol("*") ? Long.MIN_VALUE : instant();
        expectSymbol(",");
        long to = acceptSymbol("*") ? Long.MAX_VALUE : instant();
        expectSymbol(")");
        return new Validity(from, to);
    }

    private Optional<Expr> validAt() {
        if (accept("VALID")) {
            expect("AT");
            return Optional.of(additive());
        }
        return Optional.empty();
    }

    private EvidencePolicy policy() {
        if (accept("OBSERVED")) {
            return EvidencePolicy.OBSERVED;
        }
        if (accept("ANY")) {
            return EvidencePolicy.ANY;
        }
        expect("SUPPORTED");
        return EvidencePolicy.supported(peek().kind() == Token.Kind.NUMBER ? number().doubleValue() : 0.5);
    }

    private Field field() {
        return Field.parse(identifier());
    }

    private Map<String, Value> properties() {
        expectSymbol("{");
        Map<String, Value> properties = new LinkedHashMap<>();
        if (!peek().is("}")) {
            do {
                String key = peek().kind() == Token.Kind.STRING ? string() : identifier();
                expectSymbol(":");
                properties.put(key, literal());
            } while (acceptSymbol(","));
        }
        expectSymbol("}");
        return properties;
    }

    private float[] vector() {
        expectSymbol("[");
        List<Float> components = new ArrayList<>();
        do {
            components.add(number().floatValue());
        } while (acceptSymbol(","));
        expectSymbol("]");
        float[] vector = new float[components.size()];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = components.get(i);
        }
        return vector;
    }

    private Value literal() {
        if (accept("TRUE")) {
            return new Value.Bool(true);
        }
        if (accept("FALSE")) {
            return new Value.Bool(false);
        }
        if (accept("NULL")) {
            return Value.NULL;
        }
        if (accept("TIMESTAMP")) {
            return new Value.Time(parseInstant(string()));
        }
        if (accept("DECIMAL")) {
            return new Value.Decimal(new BigDecimal(peek().kind() == Token.Kind.STRING ? string() : next().text()));
        }
        if (peek().kind() == Token.Kind.STRING) {
            return new Value.Text(string());
        }
        BigDecimal number = number();
        return number.scale() <= 0 && number.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0
                ? new Value.Int(number.longValueExact())
                : new Value.Real(number.doubleValue());
    }

    private BigDecimal number() {
        boolean negative = acceptSymbol("-");
        Token token = next();
        if (token.kind() != Token.Kind.NUMBER) {
            throw error("expected a number but found " + token);
        }
        try {
            BigDecimal value = new BigDecimal(token.text());
            return negative ? value.negate() : value;
        } catch (NumberFormatException e) {
            throw error("malformed number " + token.text());
        }
    }

    private long longValue() {
        try {
            return number().longValueExact();
        } catch (ArithmeticException e) {
            throw error("expected an integer");
        }
    }

    private long instant() {
        if (peek().kind() == Token.Kind.STRING) {
            return parseInstant(string());
        }
        return longValue();
    }

    private long parseInstant(String text) {
        try {
            return Instant.parse(text).toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return Instant.parse(text + "T00:00:00Z").toEpochMilli();
            } catch (DateTimeParseException _) {
                throw error("invalid timestamp '" + text + "'");
            }
        }
    }

    private Ref ref() {
        Token token = next();
        if (token.kind() == Token.Kind.VARIABLE) {
            return new Ref.Variable(token.text());
        }
        if (!token.text().equals("@")) {
            throw error("expected an atom reference (@id, @Type:'key' or $variable) but found " + token);
        }
        if (peek().kind() == Token.Kind.NUMBER) {
            return new Ref.Id(longValue());
        }
        String type = identifier();
        expectSymbol(":");
        return new Ref.Keyed(type, peek().kind() == Token.Kind.STRING ? string() : identifier());
    }

    private Ref refOrNumber() {
        return peek().kind() == Token.Kind.NUMBER ? new Ref.Id(longValue()) : ref();
    }

    private Optional<String> binding() {
        return accept("AS") ? Optional.of(variable()) : Optional.empty();
    }

    private String variable() {
        Token token = next();
        if (token.kind() != Token.Kind.VARIABLE) {
            throw error("expected a $variable but found " + token);
        }
        return token.text();
    }

    private OptionalLong limit() {
        return accept("LIMIT") ? OptionalLong.of(longValue()) : OptionalLong.empty();
    }

    private String identifier() {
        Token token = next();
        if (token.kind() != Token.Kind.WORD) {
            throw error("expected an identifier but found " + token);
        }
        return token.text();
    }

    private String identifierOrString() {
        return peek().kind() == Token.Kind.STRING ? string() : identifier();
    }

    private String string() {
        Token token = next();
        if (token.kind() != Token.Kind.STRING) {
            throw error("expected a string literal but found " + token);
        }
        return token.text();
    }

    private static String render(Expr expr) {
        return switch (expr) {
            case Expr.Var(String name) -> name;
            case Expr.Prop(String variable, String property) -> variable + "." + property;
            case Expr.Call(String function, List<Expr> arguments) ->
                    function + "(" + String.join(", ", arguments.stream().map(Parser::render).toList()) + ")";
            case Expr.Star _ -> "*";
            case Expr.Literal(Value value) -> value.render();
            default -> "expr";
        };
    }

    private <T> T optionalKeyword(String word, T value) {
        accept(word);
        return value;
    }

    private Token peek() {
        return tokens.get(index);
    }

    private Token peek(int ahead) {
        return tokens.get(Math.min(index + ahead, tokens.size() - 1));
    }

    private Token next() {
        Token token = tokens.get(index);
        if (token.kind() != Token.Kind.END) {
            index++;
        }
        return token;
    }

    private boolean atEnd() {
        return peek().kind() == Token.Kind.END;
    }

    private boolean accept(String word) {
        if (peek().kind() == Token.Kind.WORD && peek().text().equalsIgnoreCase(word)) {
            index++;
            return true;
        }
        return false;
    }

    private void expect(String word) {
        if (!accept(word)) {
            throw error("expected " + word + " but found " + peek());
        }
    }

    private boolean acceptSymbol(String symbol) {
        if (peek().kind() == Token.Kind.SYMBOL && peek().text().equals(symbol)) {
            index++;
            return true;
        }
        return false;
    }

    private void expectSymbol(String symbol) {
        if (!acceptSymbol(symbol)) {
            throw error("expected '" + symbol + "' but found " + peek());
        }
    }

    private HStoreException error(String message) {
        return HStoreException.invalid("syntax error at offset " + peek().position() + ": " + message);
    }
}

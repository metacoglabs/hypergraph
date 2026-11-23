// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.query;

import io.hstore.db.evidence.AssertionType;
import io.hstore.db.evidence.EvidencePolicy;
import io.hstore.db.hora.Field;
import io.hstore.db.hora.Pattern;
import io.hstore.db.hora.Reducer;
import io.hstore.db.query.Ast.BranchScope;
import io.hstore.db.query.Ast.Comparison;
import io.hstore.db.query.Ast.Expr;
import io.hstore.db.query.Ast.MemberClause;
import io.hstore.db.query.Ast.Projection;
import io.hstore.db.query.Ast.Ref;
import io.hstore.db.query.Ast.SetOp;
import io.hstore.db.query.Ast.Statement;
import io.hstore.db.retrieve.RankQuery;
import io.hstore.db.schema.AtomKind;
import io.hstore.db.schema.TypeDef.PropertyDef;
import io.hstore.db.security.Access;
import io.hstore.db.security.Role;
import io.hstore.db.semantic.SemanticPlane;
import io.hstore.db.temporal.BranchMerge;
import io.hstore.db.value.TypeTag;
import io.hstore.db.value.Value;
import io.hstore.db.value.Values;
import io.hstore.db.view.MaterializedViews;
import io.hstore.engine.Dictionary;
import io.hstore.engine.HStoreException;
import io.hstore.engine.topology.Validity;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.function.Supplier;

public final class Parser {

    private static final int MAX_NESTING = 256;
    private static final int MAX_SIMILAR = 1000;

    private final List<Token> tokens;
    private int index;
    private int nesting;

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

    public static boolean mentionsAuthenticate(String source) {
        try {
            return Lexer.tokenize(source).stream().anyMatch(token -> token.is("AUTHENTICATE"));
        } catch (HStoreException unlexable) {
            return source.toUpperCase(Locale.ROOT).matches("(?s).*\\bAUTHENTICATE\\b.*");
        }
    }

    private static Statement historical(Statement inner) {
        if (inner instanceof Statement.Authenticate) {
            throw HStoreException.invalid("AUTHENTICATE cannot run at a past generation");
        }
        Statement core = inner instanceof Statement.ExplainRetrieval(Statement explained) ? explained : inner;
        if (core instanceof Statement.BranchDelta || core instanceof Statement.BranchSurvivors || core instanceof Statement.CompareBranches
                || core instanceof Statement.NearestBranch || core instanceof Statement.BranchAfter) {
            throw HStoreException.invalid("branch statements compare branch heads, so AT GENERATION and AS OF do not apply to them");
        }
        return inner;
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
            long generation = longValue();
            if (peekWord("AT") || peekWord("AS")) {
                throw HStoreException.invalid("a statement takes one AT GENERATION or AS OF");
            }
            return new Statement.At(generation, historical(statement()));
        }
        if (peek().is("AS") && peek(1).is("OF")) {
            index += 2;
            long instant = instant();
            if (peekWord("AT") || peekWord("AS")) {
                throw HStoreException.invalid("a statement takes one AT GENERATION or AS OF");
            }
            return new Statement.AsOf(instant, historical(statement()));
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
                policy = option(BranchMerge.Policy.class, identifier());
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
            Optional<String> password = Optional.empty();
            Optional<Role> role = Optional.empty();
            OptionalLong validUntil = OptionalLong.empty();
            OptionalLong connectionLimit = OptionalLong.empty();
            boolean changed = false;
            while (true) {
                if (password.isEmpty() && accept("PASSWORD")) {
                    password = Optional.of(string());
                } else if (role.isEmpty() && accept("ROLE")) {
                    role = Optional.of(option(Role.class, identifier()));
                } else if (validUntil.isEmpty() && peek().is("VALID")) {
                    validUntil = OptionalLong.of(validUntil());
                } else if (connectionLimit.isEmpty() && peek().is("CONNECTION")) {
                    connectionLimit = OptionalLong.of(connectionLimit());
                } else {
                    break;
                }
                changed = true;
            }
            if (!changed) {
                throw error("expected PASSWORD, ROLE, VALID UNTIL or CONNECTION LIMIT");
            }
            return new Statement.AlterUser(name, password, role, validUntil, connectionLimit);
        }
        if (accept("GRANT")) {
            Access.Privilege privilege = option(Access.Privilege.class, identifier());
            Optional<String> type = typeOf(privilege);
            expect("TO");
            return new Statement.Grant(privilege, type, identifierOrString());
        }
        if (accept("REVOKE")) {
            Access.Privilege privilege = option(Access.Privilege.class, identifier());
            Optional<String> type = typeOf(privilege);
            expect("FROM");
            return new Statement.Revoke(privilege, type, identifierOrString());
        }
        if (peek().is("FORMAT")) {
            index++;
            return new Statement.SetFormat(identifier().toLowerCase());
        }
        if (accept("DROP")) {
            if (accept("HIERARCHY")) {
                return new Statement.DropHierarchy(identifier());
            }
            if (accept("USER")) {
                return new Statement.DropUser(identifierOrString());
            }
            if (accept("VIEW")) {
                return new Statement.DropView(identifier());
            }
            expect("BRANCH");
            return new Statement.DropBranch(identifier());
        }
        if (accept("REFRESH")) {
            if (accept("HIERARCHY")) {
                return new Statement.RefreshHierarchy(identifier());
            }
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
            if (accept("VIEW")) {
                String view = string();
                if (accept("TEXT")) {
                    return new Statement.EmbedView(atom, view, Optional.of(string()), Optional.empty(), Optional.empty());
                }
                expect("VECTOR");
                float[] values = vector();
                expect("MODEL");
                return new Statement.EmbedView(atom, view, Optional.empty(), Optional.of(values), Optional.of(identifierOrString()));
            }
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
            int depth = accept("DEPTH") ? intValue() : 8;
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
            if (accept("HYPERMATCH")) {
                return new Statement.ExplainStructural(structuralMatch());
            }
            if (accept("MATCH")) {
                return new Statement.Explain(match());
            }
            if (peekWord("EXPLAIN") || peekWord("AT") || peekWord("AS")) {
                throw HStoreException.invalid("EXPLAIN takes one retrieval statement");
            }
            Statement inner = statement();
            if (!explainable(inner)) {
                throw HStoreException.invalid("EXPLAIN takes MATCH, HYPERMATCH or a retrieval statement such as RANK, TRANSITIONS, "
                        + "BRANCH DELTA, REFINE, DIFFUSE, EXPAND FROM, SELECT EVIDENCE, MOTIFS, MOTIF, SIMILAR TO or FUSE");
            }
            return new Statement.ExplainRetrieval(inner);
        }
        if (accept("FUSE")) {
            List<Statement> parts = new ArrayList<>();
            do {
                expectSymbol("(");
                if (peekWord("FUSE") || peekWord("EXPLAIN") || peekWord("AT") || peekWord("AS")) {
                    throw HStoreException.invalid("FUSE parts are single retrieval statements");
                }
                Statement part = statement();
                if (!fusable(part)) {
                    throw HStoreException.invalid("FUSE takes MATCH, RANK, HYPERMATCH, REFINE, DIFFUSE, EXPAND FROM, SELECT EVIDENCE, MOTIF, "
                            + "SIMILAR TO or TRANSITIONS");
                }
                expectSymbol(")");
                parts.add(part);
            } while (acceptSymbol(","));
            if (parts.size() < 2 || parts.size() > 8) {
                throw HStoreException.invalid("FUSE combines between 2 and 8 statements");
            }
            return new Statement.Fuse(List.copyOf(parts), limit());
        }
        if (accept("HYPERMATCH")) {
            return structuralMatch();
        }
        if (accept("MOTIFS")) {
            Optional<String> type = accept("OF") ? Optional.of(identifier()) : Optional.empty();
            String mode = "AUTO";
            long samples = 0;
            if (accept("EXACT")) {
                mode = "EXACT";
            } else if (accept("SAMPLE")) {
                mode = "SAMPLE";
                samples = longValue();
                if (samples < 1 || samples > 1_000_000) {
                    throw HStoreException.invalid("SAMPLE must be between 1 and 1000000 draws");
                }
            } else {
                accept("AUTO");
            }
            long seed = 0;
            if (accept("SEED")) {
                if (mode.equals("EXACT")) {
                    throw HStoreException.invalid("SEED only applies to sampled counts");
                }
                seed = longValue();
            }
            return new Statement.Motifs(type, mode, samples, seed);
        }
        if (accept("MOTIF")) {
            return motif();
        }
        if (accept("REVISE")) {
            return revise();
        }
        if (accept("REBUILD")) {
            expect("HIERARCHY");
            return new Statement.RebuildHierarchy(identifier());
        }
        if (accept("REGIONS")) {
            expect("OF");
            expect("HIERARCHY");
            return new Statement.RegionsOf(identifier());
        }
        if (accept("CALIBRATE")) {
            expect("HIERARCHY");
            String name = identifier();
            OptionalDouble temperature = accept("TEMPERATURE") ? OptionalDouble.of(number().doubleValue()) : OptionalDouble.empty();
            Map<Integer, double[]> levels = new LinkedHashMap<>();
            while (accept("LEVEL")) {
                int level = (int) bounded(longValue(), 0, 7, "LEVEL");
                expect("SCALE");
                double scale = number().doubleValue();
                expect("SHIFT");
                levels.put(level, new double[]{scale, number().doubleValue()});
            }
            return new Statement.CalibrateHierarchy(name, temperature, levels);
        }
        if (accept("REFERENCE")) {
            expect("REGION");
            long region = longValue();
            expect("OF");
            expect("HIERARCHY");
            String name = identifier();
            expect("VECTORS");
            expectSymbol("(");
            List<float[]> vectors = new ArrayList<>();
            do {
                vectors.add(vector());
            } while (acceptSymbol(","));
            expectSymbol(")");
            return new Statement.ReferenceRegion(region, name, vectors);
        }
        if (accept("ROUTE")) {
            return route();
        }
        if (accept("RETRIEVE")) {
            return retrieve();
        }
        if (accept("SEARCH")) {
            expect("TEXT");
            String query = string();
            List<String> types = new ArrayList<>();
            if (accept("IN")) {
                do {
                    types.add(identifierOrString());
                } while (acceptSymbol(","));
            }
            boolean history = accept("HISTORY");
            return new Statement.SearchText(query, types, history, top(10));
        }
        if (accept("VECTORS")) {
            expect("OF");
            return new Statement.VectorsOf(ref());
        }
        if (accept("REVISIONS")) {
            expect("OF");
            return new Statement.Revisions(ref());
        }
        if (accept("REVISION")) {
            expect("OF");
            return revisionOf();
        }
        if (accept("SECTIONS")) {
            expect("OF");
            return new Statement.SectionsOf(ref());
        }
        if (accept("SECTION")) {
            return new Statement.ShowSection(ref());
        }
        if (accept("PURGE")) {
            return new Statement.Purge(ref());
        }
        if (accept("SIMILAR")) {
            return similar();
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
        if (peekWord("SELECT") && peekWordAt(1, "EVIDENCE")) {
            expect("SELECT");
            expect("EVIDENCE");
            expect("FOR");
            expectSymbol("(");
            expect("RANK");
            Statement.Rank query = rank();
            expectSymbol(")");
            List<Ref> seeds = new ArrayList<>();
            if (accept("FROM")) {
                do {
                    seeds.add(ref());
                } while (acceptSymbol(","));
            }
            boolean connected = accept("CONNECTED");
            if (connected && seeds.isEmpty()) {
                throw HStoreException.invalid("CONNECTED needs seeds given with FROM");
            }
            OptionalLong budget = accept("BUDGET") ? OptionalLong.of(longValue()) : OptionalLong.empty();
            OptionalLong top = accept("TOP") ? OptionalLong.of(longValue()) : OptionalLong.empty();
            if (budget.isEmpty() && top.isEmpty()) {
                throw HStoreException.invalid("SELECT EVIDENCE needs TOP, BUDGET or both");
            }
            double kappa = accept("KAPPA") ? number().doubleValue() : 0.5;
            double lambda = accept("LAMBDA") ? number().doubleValue() : 1.0;
            boolean counted = accept("CANDIDATES");
            if (counted && seeds.isEmpty()) {
                throw HStoreException.invalid("CANDIDATES counts the hyperedges reached from the seeds, so it needs FROM");
            }
            long candidates = counted ? longValue() : 200;
            if (candidates < 1 || candidates > 10_000) {
                throw HStoreException.invalid("CANDIDATES must be between 1 and 10000");
            }
            return new Statement.SelectEvidence(query, List.copyOf(seeds), connected, top, budget, kappa, lambda, candidates);
        }
        if (accept("DIFFUSE")) {
            expect("FROM");
            List<Ast.DiffuseSeed> seeds = new ArrayList<>();
            do {
                Ref seed = ref();
                seeds.add(new Ast.DiffuseSeed(seed, accept("WEIGHT") ? number().doubleValue() : 1.0));
                if (seeds.size() > 1_000) {
                    throw HStoreException.invalid("DIFFUSE takes at most 1000 seeds");
                }
            } while (acceptSymbol(","));
            List<Ast.DiffuseType> through = new ArrayList<>();
            if (accept("THROUGH")) {
                do {
                    String type = identifier();
                    through.add(new Ast.DiffuseType(type, accept("WEIGHT") || accept("FACTOR") ? number().doubleValue() : 1.0));
                } while (acceptSymbol(","));
            }
            List<Ast.RoleFactor> exit = accept("EXIT") ? roleFactors() : List.of();
            List<Ast.RoleFactor> entry = accept("ENTER") ? roleFactors() : List.of();
            Optional<Expr> validAt = Optional.empty();
            Optional<Validity> during = Optional.empty();
            if (peek().is("VALID") && peek(1).is("DURING")) {
                index += 2;
                during = Optional.of(interval());
            } else {
                validAt = validAt();
            }
            Optional<EvidencePolicy> policy = accept("POLICY") ? Optional.of(policy()) : Optional.empty();
            boolean confidence = false;
            if (accept("WEIGHTED")) {
                expect("BY");
                expect("CONFIDENCE");
                confidence = true;
            }
            double alpha = accept("ALPHA") ? number().doubleValue() : 0.2;
            double epsilon = 1e-4;
            OptionalLong reads = OptionalLong.empty();
            if (accept("EPSILON")) {
                epsilon = number().doubleValue();
            } else if (accept("READS")) {
                long budget = longValue();
                if (budget < 1 || !(alpha > 0 && alpha < 1)) {
                    throw HStoreException.invalid("READS needs at least one read and ALPHA between 0 and 1");
                }
                epsilon = (2 - alpha) / (alpha * budget);
                reads = OptionalLong.of(budget);
            }
            expect("TOP");
            long top = longValue();
            long edges = accept("EDGES") ? longValue() : 0;
            if (top < 0 || top > 10_000 || edges < 0 || edges > 10_000) {
                throw HStoreException.invalid("TOP and EDGES must be between 0 and 10000");
            }
            return new Statement.Diffuse(List.copyOf(seeds), List.copyOf(through), exit, entry, validAt, policy, alpha, epsilon, reads, top, edges,
                    during, confidence);
        }
        if (accept("REFINE")) {
            expect("FROM");
            List<Ref> roots = new ArrayList<>();
            Optional<String> rootType = Optional.empty();
            if (accept("TYPE")) {
                rootType = Optional.of(identifierOrString());
            } else {
                do {
                    roots.add(ref());
                } while (acceptSymbol(","));
            }
            List<Ast.RefineLevel> levels = new ArrayList<>();
            while (accept("THROUGH")) {
                levels.add(new Ast.RefineLevel(identifierOrString(), refineRoles()));
                if (levels.size() + 1 > io.hstore.db.hora.Budget.DEFAULT.maxDepth()) {
                    throw HStoreException.invalid("REFINE goes through at most " + (io.hstore.db.hora.Budget.DEFAULT.maxDepth() - 1) + " levels");
                }
            }
            expect("TO");
            Optional<String> leafType = peek().kind() == Token.Kind.STRING
                    || peek().kind() == Token.Kind.WORD && !peek().is("AS") && !peek().is("VALID") && !peek().is("WEIGHT")
                    && !peek().is("DISTINCT") && !peek().is("TOP") ? Optional.of(identifierOrString()) : Optional.empty();
            List<String> leafRoles = refineRoles();
            Optional<Ref> anchor = acceptSymbol("=") ? Optional.of(ref()) : Optional.empty();
            Optional<Expr> validAt = validAt();
            double low = 0;
            double high = 1;
            if (accept("WEIGHT")) {
                expect("BETWEEN");
                low = number().doubleValue();
                expect("AND");
                high = number().doubleValue();
            }
            boolean distinct = accept("DISTINCT");
            expect("TOP");
            long top = longValue();
            if (top < 1 || top > 10_000) {
                throw HStoreException.invalid("TOP must be between 1 and 10000");
            }
            return new Statement.Refine(List.copyOf(roots), rootType, List.copyOf(levels), leafType, leafRoles, anchor, validAt, low, high, distinct, top);
        }
        if (peekWord("BRANCH") && peekWordAt(1, "DELTA", "SURVIVORS", "AFTER")) {
            expect("BRANCH");
            if (accept("DELTA")) {
                String branch = identifier();
                OptionalLong since = OptionalLong.empty();
                if (accept("SINCE")) {
                    expect("GENERATION");
                    since = OptionalLong.of(longValue());
                }
                return new Statement.BranchDelta(branch, since, branchScope(), limit());
            }
            if (accept("SURVIVORS")) {
                return new Statement.BranchSurvivors(identifier(), branchScope(), limit());
            }
            expect("AFTER");
            String branch = identifier();
            expect("GENERATION");
            long generation = longValue();
            long window = 1_000;
            if (accept("WINDOW")) {
                if (peek().is("-")) {
                    throw HStoreException.invalid("WINDOW must not be negative");
                }
                window = longValue();
            }
            return new Statement.BranchAfter(branch, generation, window, limit());
        }
        if (accept("COMPARE")) {
            expect("BRANCH");
            String left = identifier();
            expect("AND");
            String right = identifier();
            return new Statement.CompareBranches(left, right, branchScope(), limit());
        }
        if (accept("NEAREST")) {
            if (accept("VECTORS")) {
                return nearestVectors();
            }
            expect("BRANCH");
            expect("TO");
            String target = identifier();
            List<String> among = new ArrayList<>();
            if (accept("AMONG")) {
                expectSymbol("(");
                do {
                    among.add(identifier());
                } while (acceptSymbol(","));
                expectSymbol(")");
            }
            BranchScope scope = branchScope();
            expect("TOP");
            long top = longValue();
            if (top < 1 || top > 1_000) {
                throw HStoreException.invalid("TOP must be between 1 and 1000");
            }
            return new Statement.NearestBranch(target, List.copyOf(among), scope, (int) top);
        }
        if (accept("TRANSITIONS")) {
            expect("EDGE");
            String variable = identifier();
            Optional<String> type = acceptSymbol(":") ? Optional.of(identifier()) : Optional.empty();
            expect("FROM");
            Ast.Instant from = transitionInstant();
            expect("TO");
            Ast.Instant to = transitionInstant();
            boolean appeared = accept("APPEARED");
            if (!appeared) {
                expect("DISAPPEARED");
            }
            boolean members = accept("MEMBERS");
            return new Statement.Transitions(variable, type, from, to, appeared, members, limit());
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
        if (accept("RANK")) {
            return rank();
        }
        if (accept("EXPAND")) {
            if (accept("FROM")) {
                return expandFrom();
            }
            Ref atom = ref();
            int depth = accept("DEPTH") ? intValue() : 4;
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
            int threshold = accept("THRESHOLD") ? intValue() : 1;
            return new Statement.Neighbors(atom, threshold, limit());
        }
        if (accept("CLOSURE")) {
            Ref edge = ref();
            expect("DIM");
            return new Statement.Closure(edge, intValue(), limit());
        }
        if (accept("PATTERN")) {
            return pattern();
        }
        if (accept("SAMPLE")) {
            int swaps = intValue();
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
            if (accept("HIERARCHIES")) {
                return new Statement.ShowHierarchies();
            }
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
            if (accept("GRANTS")) {
                return new Statement.ShowGrants(accept("FOR") ? Optional.of(identifierOrString()) : Optional.empty());
            }
            expect("VIEWS");
            return new Statement.ShowViews();
        }
        if (accept("CHECKPOINT")) {
            if (accept("AT")) {
                return new Statement.CheckpointAt(accept("AUTO") ? OptionalLong.empty() : OptionalLong.of(size()));
            }
            return new Statement.Checkpoint();
        }
        if (accept("COMPACT")) {
            return new Statement.Compact();
        }
        throw error("unknown statement");
    }

    private Statement create() {
        if (accept("HIERARCHY")) {
            String name = identifier();
            expect("ON");
            expect("MODEL");
            String model = identifierOrString();
            expect("VIEW");
            String view = string();
            Optional<String> type = accept("OF") ? Optional.of(identifierOrString()) : Optional.empty();
            int fanout = accept("FANOUT") ? (int) bounded(longValue(), 2, 64, "FANOUT") : 16;
            int capacity = accept("LEAVES") ? (int) bounded(longValue(), 2, 1024, "LEAVES") : 64;
            boolean continuous = accept("CONTINUOUS");
            return new Statement.CreateHierarchy(name, model, view, type, fanout, capacity, continuous);
        }
        if (accept("TENANT")) {
            String name = identifier();
            return new Statement.CreateTenant(name, accept("QUOTA") ? quota() : Map.of());
        }
        if (accept("USER")) {
            String name = identifierOrString();
            expect("PASSWORD");
            String password = string();
            Optional<String> tenant = accept("TENANT") ? Optional.of(identifier()) : Optional.empty();
            Role role = accept("ROLE") ? option(Role.class, identifier()) : Role.WRITER;
            OptionalLong validUntil = peek().is("VALID") ? OptionalLong.of(validUntil()) : OptionalLong.empty();
            OptionalLong connectionLimit = peek().is("CONNECTION") ? OptionalLong.of(connectionLimit()) : OptionalLong.empty();
            return new Statement.CreateUser(name, password, tenant, role, validUntil, connectionLimit);
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
            } else if (accept("MOTIFS")) {
                Optional<String> type = accept("OF") ? Optional.of(identifier()) : Optional.empty();
                return new Statement.CreateMotifView(name, type, accept("CONTINUOUS"));
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

    private Statement revise() {
        Ref document = ref();
        expect("MARKDOWN");
        String markdown = string();
        OptionalLong validFrom = OptionalLong.empty();
        long validTo = Long.MAX_VALUE;
        List<Ref> sources = new ArrayList<>();
        String note = "";
        boolean embed = false;
        while (true) {
            if (accept("VALID")) {
                if (accept("FROM")) {
                    validFrom = OptionalLong.of(instant());
                } else {
                    expect("TO");
                    validTo = instant();
                }
            } else if (accept("SOURCES")) {
                expectSymbol("(");
                do {
                    sources.add(ref());
                } while (acceptSymbol(","));
                expectSymbol(")");
            } else if (accept("NOTE")) {
                note = string();
            } else if (accept("EMBED")) {
                embed = true;
            } else {
                break;
            }
        }
        return new Statement.Revise(document, markdown, validFrom, validTo, sources, note, embed, binding());
    }

    private Statement retrieve() {
        String text = string();
        List<Ref> anchors = new ArrayList<>();
        List<String> resolveIn = new ArrayList<>();
        List<String> types = new ArrayList<>();
        OptionalLong validAt = OptionalLong.empty();
        OptionalLong recordedAt = OptionalLong.empty();
        Optional<EvidencePolicy> policy = Optional.empty();
        Optional<String> hierarchy = Optional.empty();
        boolean history = false;
        int top = 10;
        while (true) {
            if (accept("ANCHORS")) {
                expectSymbol("(");
                do {
                    anchors.add(ref());
                } while (acceptSymbol(","));
                expectSymbol(")");
            } else if (accept("RESOLVE")) {
                expect("IN");
                resolveIn.addAll(typeList());
            } else if (accept("IN")) {
                types.addAll(typeList());
            } else if (accept("VALID")) {
                expect("AT");
                validAt = OptionalLong.of(instant());
            } else if (accept("RECORDED")) {
                expect("AT");
                recordedAt = OptionalLong.of(instant());
            } else if (accept("POLICY")) {
                policy = Optional.of(policy());
            } else if (accept("THROUGH")) {
                expect("HIERARCHY");
                hierarchy = Optional.of(identifier());
            } else if (accept("HISTORY")) {
                history = true;
            } else if (peek().is("TOP")) {
                top = top(10);
                if (top > 100) {
                    throw error("RETRIEVE returns at most 100 hits");
                }
            } else {
                break;
            }
        }
        return new Statement.Retrieve(text, anchors, resolveIn, types, validAt, recordedAt, policy, hierarchy, history, top);
    }

    private List<String> typeList() {
        List<String> names = new ArrayList<>();
        expectSymbol("(");
        do {
            names.add(identifierOrString());
        } while (acceptSymbol(","));
        expectSymbol(")");
        return names;
    }

    private Statement route() {
        Optional<String> text = Optional.empty();
        Optional<float[]> values = Optional.empty();
        if (peek().kind() == Token.Kind.STRING) {
            text = Optional.of(string());
        } else {
            values = Optional.of(vector());
        }
        expect("IN");
        expect("HIERARCHY");
        String name = identifier();
        double mass = 0.9;
        int min = 1;
        int max = 32;
        int depth = 64;
        int regions = 256;
        OptionalDouble temperature = OptionalDouble.empty();
        double fallback = 0.95;
        boolean recall = false;
        int top = 10;
        while (true) {
            if (accept("MASS")) {
                mass = number().doubleValue();
                if (!(mass > 0 && mass <= 1)) {
                    throw error("MASS must be greater than 0 and at most 1");
                }
            } else if (accept("MIN")) {
                min = (int) bounded(longValue(), 1, 1024, "MIN");
            } else if (accept("MAX")) {
                max = (int) bounded(longValue(), 1, 1024, "MAX");
            } else if (accept("DEPTH")) {
                depth = (int) bounded(longValue(), 1, 64, "DEPTH");
            } else if (accept("REGIONS")) {
                regions = (int) bounded(longValue(), 1, 1_000_000, "REGIONS");
            } else if (accept("TEMPERATURE")) {
                double value = number().doubleValue();
                if (!(value >= 0.001 && value <= 100)) {
                    throw error("TEMPERATURE must be between 0.001 and 100");
                }
                temperature = OptionalDouble.of(value);
            } else if (accept("FALLBACK")) {
                fallback = number().doubleValue();
            } else if (accept("RECALL")) {
                recall = true;
            } else if (peek().is("TOP")) {
                top = top(10);
            } else {
                break;
            }
        }
        if (max < min) {
            throw error("MAX must be at least MIN");
        }
        return new Statement.Route(text, values, name, mass, min, max, depth, regions, temperature, fallback, recall, top);
    }

    private long bounded(long value, long low, long high, String what) {
        if (value < low || value > high) {
            throw error(what + " must be between " + low + " and " + high);
        }
        return value;
    }

    private Statement nearestVectors() {
        expect("TO");
        Optional<String> text = Optional.empty();
        Optional<float[]> values = Optional.empty();
        Optional<String> model = Optional.empty();
        if (peek().kind() == Token.Kind.STRING) {
            text = Optional.of(string());
        } else {
            values = Optional.of(vector());
            expect("MODEL");
            model = Optional.of(identifierOrString());
        }
        Optional<String> view = accept("VIEW") ? Optional.of(string()) : Optional.empty();
        boolean history = accept("HISTORY");
        return new Statement.NearestVectors(text, values, model, view, history, top(10));
    }

    private int top(int fallback) {
        if (!accept("TOP")) {
            return fallback;
        }
        long value = longValue();
        if (value < 1 || value > 1000) {
            throw error("TOP must be between 1 and 1000");
        }
        return (int) value;
    }

    private Statement revisionOf() {
        Ref document = ref();
        OptionalInt number = OptionalInt.empty();
        OptionalLong validAt = OptionalLong.empty();
        OptionalLong recordedAt = OptionalLong.empty();
        if (accept("NUMBER")) {
            long value = longValue();
            if (value < 1 || value > Integer.MAX_VALUE) {
                throw error("revision numbers start at 1");
            }
            number = OptionalInt.of((int) value);
        } else {
            if (accept("VALID")) {
                expect("AT");
                validAt = OptionalLong.of(instant());
            }
            if (accept("RECORDED")) {
                expect("AT");
                recordedAt = OptionalLong.of(instant());
            }
        }
        return new Statement.RevisionOf(document, number, validAt, recordedAt, binding());
    }

    private Statement qualify() {
        Ref member = ref();
        expect("IN");
        Ref edge = ref();
        expect("AS");
        AssertionType type = option(AssertionType.class, identifier());
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

    private Statement.Rank rank() {
        if (!accept("EDGE") && !accept("EDGES")) {
            throw error("expected EDGE");
        }
        String variable = identifier();
        Optional<String> type = acceptSymbol(":") ? Optional.of(identifier()) : Optional.empty();
        expect("LIKE");
        expectSymbol("(");
        List<Ast.RankSlot> slots = new ArrayList<>();
        do {
            if (slots.size() == RankQuery.MAX_SLOTS) {
                throw error("RANK accepts at most " + RankQuery.MAX_SLOTS + " slots");
            }
            slots.add(rankSlot());
        } while (acceptSymbol(","));
        expectSymbol(")");
        Optional<Ast.RankTime> time = Optional.empty();
        if (accept("VALID")) {
            if (accept("DURING")) {
                Validity interval = interval();
                time = Optional.of(new Ast.RankTime.During(interval.from(), interval.to()));
            } else {
                expect("AT");
                Expr instant = additive();
                time = Optional.of(new Ast.RankTime.At(instant, accept("TAU") ? OptionalDouble.of(number().doubleValue()) : OptionalDouble.empty()));
            }
        }
        List<Ast.RankPreference> preferences = new ArrayList<>();
        if (accept("PREFER")) {
            do {
                if (preferences.size() == RankQuery.MAX_PREFERENCES) {
                    throw error("RANK accepts at most " + RankQuery.MAX_PREFERENCES + " preferences");
                }
                preferences.add(preference());
            } while (acceptSymbol(","));
        }
        OptionalLong size = accept("SIZE") ? OptionalLong.of(longValue()) : OptionalLong.empty();
        Map<String, Double> weights = new LinkedHashMap<>();
        if (accept("WEIGHTS")) {
            expectSymbol("(");
            do {
                String component = identifier().toLowerCase(Locale.ROOT);
                if (weights.put(component, number().doubleValue()) != null) {
                    throw error("the weight of " + component + " is given twice");
                }
            } while (acceptSymbol(","));
            expectSymbol(")");
        }
        expect("TOP");
        return new Statement.Rank(variable, type, slots, time, preferences, size, weights, longValue());
    }

    private Ast.RankSlot rankSlot() {
        Ast.RankTarget target = accept("ANY") ? new Ast.RankTarget.OfType(identifier()) : new Ast.RankTarget.Atom(ref());
        List<String> roles = new ArrayList<>();
        if (accept("AS")) {
            do {
                roles.add(identifier());
            } while (accept("OR"));
        }
        OptionalDouble weight = accept("WEIGHT") ? OptionalDouble.of(number().doubleValue()) : OptionalDouble.empty();
        return new Ast.RankSlot(target, roles, weight, accept("REQUIRED"));
    }

    private Ast.RankPreference preference() {
        String variable = identifier();
        expectSymbol(".");
        String property = identifier();
        if (accept("NEAR")) {
            double target = number().doubleValue();
            expect("SCALE");
            double scale = number().doubleValue();
            return new Ast.RankPreference.Near(variable, property, target, scale, preferenceWeight());
        }
        expectSymbol("=");
        return new Ast.RankPreference.Equals(variable, property, literal(), preferenceWeight());
    }

    private OptionalDouble preferenceWeight() {
        return accept("WEIGHT") ? OptionalDouble.of(number().doubleValue()) : OptionalDouble.empty();
    }

    private Optional<String> typeOf(Access.Privilege privilege) {
        if (!privilege.onType()) {
            return Optional.empty();
        }
        expect("ON");
        expect("TYPE");
        return Optional.of(identifier());
    }

    private long validUntil() {
        expect("VALID");
        expect("UNTIL");
        if (peek().kind() == Token.Kind.STRING && peek().text().equalsIgnoreCase("infinity")) {
            index++;
            return Long.MAX_VALUE;
        }
        return instant();
    }

    private long connectionLimit() {
        expect("CONNECTION");
        expect("LIMIT");
        long limit = longValue();
        if (limit < -1 || limit > 100_000) {
            throw error("CONNECTION LIMIT must be -1 (no limit) or between 0 and 100000");
        }
        return limit;
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
        return accept("NOT") ? new Expr.Not(nested(this::negation)) : predicate();
    }

    private Expr nested(Supplier<Expr> parse) {
        if (++nesting > MAX_NESTING) {
            throw error("expression nested deeper than " + MAX_NESTING + " levels");
        }
        try {
            return parse.get();
        } finally {
            nesting--;
        }
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
                int k = accept("TOP") ? intValue() : 10;
                if (k < 1 || k > MAX_SIMILAR) {
                    throw error("SIMILAR TO ... TOP must be between 1 and " + MAX_SIMILAR);
                }
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
            Expr inner = nested(this::expression);
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
        if (from >= to) {
            throw error("the interval [" + from + ", " + to + ") is empty");
        }
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
            return new Value.Decimal(Values.number(peek().kind() == Token.Kind.STRING ? string() : next().text()));
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
            BigDecimal value = Values.number(token.text());
            return negative ? value.negate() : value;
        } catch (NumberFormatException e) {
            throw error("malformed number " + token.text());
        }
    }

    private long size() {
        BigDecimal amount = number();
        Token unit = next();
        long scale = unit.kind() != Token.Kind.WORD ? 0 : switch (unit.text().toUpperCase()) {
            case "B" -> 1;
            case "KB", "KIB" -> 1L << 10;
            case "MB", "MIB" -> 1L << 20;
            case "GB", "GIB" -> 1L << 30;
            case "TB", "TIB" -> 1L << 40;
            default -> 0;
        };
        if (scale == 0) {
            throw error("expected a size unit (B, KB, MB, GB or TB) but found " + unit);
        }
        long bytes;
        try {
            bytes = amount.multiply(BigDecimal.valueOf(scale)).longValueExact();
        } catch (ArithmeticException e) {
            throw error(amount.toPlainString() + " " + unit.text() + " is not a whole number of bytes");
        }
        if (bytes < 1) {
            throw error("a size must be positive, not " + amount.toPlainString() + " " + unit.text());
        }
        return bytes;
    }

    private long longValue() {
        try {
            return number().longValueExact();
        } catch (ArithmeticException e) {
            throw error("expected an integer");
        }
    }

    private <E extends Enum<E>> E option(Class<E> type, String name) {
        try {
            return Enum.valueOf(type, name.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw error("unknown " + type.getSimpleName() + " '" + name + "'; expected one of " + Arrays.toString(type.getEnumConstants()));
        }
    }

    private int intValue() {
        long value = longValue();
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw error("expected an integer between " + Integer.MIN_VALUE + " and " + Integer.MAX_VALUE);
        }
        return (int) value;
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

    private Statement.ExpandFrom expandFrom() {
        List<Ast.DiffuseSeed> seeds = new ArrayList<>();
        do {
            Ref seed = ref();
            seeds.add(new Ast.DiffuseSeed(seed, accept("WEIGHT") ? number().doubleValue() : 1.0));
            if (seeds.size() > 1_000) {
                throw HStoreException.invalid("EXPAND FROM takes at most 1000 seeds");
            }
        } while (acceptSymbol(","));
        List<Ast.DiffuseType> through = new ArrayList<>();
        if (accept("THROUGH")) {
            do {
                String type = identifier();
                through.add(new Ast.DiffuseType(type, accept("FACTOR") || accept("WEIGHT") ? number().doubleValue() : 1.0));
            } while (acceptSymbol(","));
        }
        List<Ast.RoleFactor> exit = accept("EXIT") ? roleFactors() : List.of();
        List<Ast.RoleFactor> entry = accept("ENTER") ? roleFactors() : List.of();
        Optional<Expr> validAt = validAt();
        Optional<Statement.Rank> towards = Optional.empty();
        double gate = 0.1;
        if (accept("TOWARDS")) {
            expectSymbol("(");
            expect("RANK");
            towards = Optional.of(rank());
            expectSymbol(")");
            if (accept("GATE")) {
                gate = number().doubleValue();
            }
        } else {
            gate = 1.0;
        }
        double hub = 0.5;
        double edge = 0.5;
        if (accept("DAMPING")) {
            hub = number().doubleValue();
            edge = number().doubleValue();
        }
        double minimum = accept("MIN") ? number().doubleValue() : 0;
        expect("TOP");
        long top = longValue();
        if (top < 1 || top > 10_000) {
            throw HStoreException.invalid("TOP must be between 1 and 10000");
        }
        return new Statement.ExpandFrom(List.copyOf(seeds), List.copyOf(through), exit, entry, validAt, towards, gate, hub, edge, minimum, top);
    }

    private List<Ast.RoleFactor> roleFactors() {
        expectSymbol("(");
        List<Ast.RoleFactor> factors = new ArrayList<>();
        do {
            String role = identifier();
            factors.add(new Ast.RoleFactor(role, number().doubleValue()));
        } while (acceptSymbol(","));
        expectSymbol(")");
        return List.copyOf(factors);
    }

    private List<String> refineRoles() {
        List<String> roles = new ArrayList<>();
        if (accept("AS")) {
            do {
                roles.add(identifier());
            } while (accept("OR"));
        }
        return List.copyOf(roles);
    }

    private BranchScope branchScope() {
        if (!accept("SCOPE")) {
            return new BranchScope.Everything();
        }
        if (accept("INCIDENCES")) {
            return new BranchScope.Incidences();
        }
        if (accept("TYPE")) {
            return new BranchScope.OfType(identifier());
        }
        expect("AROUND");
        return new BranchScope.Around(ref());
    }

    private Statement.StructuralMatch structuralMatch() {
        List<Ast.StructuralEdge> edges = patternEdges();
        return structuralRest(edges);
    }

    private List<Ast.StructuralEdge> patternEdges() {
        List<Ast.StructuralEdge> edges = new ArrayList<>();
        do {
            String name = identifier();
            expectSymbol(":");
            String type = identifier();
            expectSymbol("(");
            List<Ast.StructuralVertex> vertices = new ArrayList<>();
            do {
                String vertex = identifier();
                vertices.add(new Ast.StructuralVertex(vertex, acceptSymbol(":") ? Optional.of(identifier()) : Optional.empty()));
            } while (acceptSymbol(","));
            expectSymbol(")");
            edges.add(new Ast.StructuralEdge(name, type, List.copyOf(vertices)));
            if (edges.size() > 64) {
                throw HStoreException.invalid("a structural match has at most 64 hyperedges");
            }
        } while (acceptSymbol(","));
        return edges;
    }

    private Statement.StructuralMatch structuralRest(List<Ast.StructuralEdge> edges) {
        List<Ast.StructuralAnchor> anchors = new ArrayList<>();
        if (accept("WHERE")) {
            do {
                String vertex = identifier();
                expectSymbol("=");
                anchors.add(new Ast.StructuralAnchor(vertex, ref()));
            } while (accept("AND"));
        }
        Optional<String> mode = Optional.empty();
        if (accept("MODE")) {
            String found = identifier().toUpperCase(java.util.Locale.ROOT);
            if (!List.of("AUTO", "HGMATCH", "MACH").contains(found)) {
                throw HStoreException.invalid("MODE must be AUTO, HGMATCH or MACH");
            }
            mode = Optional.of(found);
        }
        if (accept("COUNT")) {
            return new Statement.StructuralMatch(List.copyOf(edges), List.copyOf(anchors), mode, true, false, OptionalLong.empty());
        }
        boolean bindings = accept("BINDINGS");
        return new Statement.StructuralMatch(List.copyOf(edges), List.copyOf(anchors), mode, false, bindings, limit());
    }

    private Ast.Instant transitionInstant() {
        return accept("GENERATION") ? new Ast.Instant.Generation(longValue()) : new Ast.Instant.Time(parseInstant(string()));
    }

    private static boolean fusable(Statement statement) {
        return switch (statement) {
            case Statement.Query _, Statement.Rank _, Statement.StructuralMatch _, Statement.Refine _, Statement.Diffuse _, Statement.ExpandFrom _,
                 Statement.SelectEvidence _, Statement.MotifMatch _, Statement.Similar _, Statement.Transitions _ -> true;
            default -> false;
        };
    }

    private static boolean explainable(Statement statement) {
        return fusable(statement) || switch (statement) {
            case Statement.BranchDelta _, Statement.BranchSurvivors _, Statement.CompareBranches _, Statement.NearestBranch _,
                 Statement.BranchAfter _, Statement.Motifs _, Statement.MotifProfile _, Statement.Fuse _ -> true;
            default -> false;
        };
    }

    private Statement.Similar similar() {
        expect("TO");
        List<Ast.StructuralEdge> pattern = List.of();
        List<Ref> example = List.of();
        Optional<Ref> container = Optional.empty();
        if (accept("EDGES")) {
            expectSymbol("(");
            List<Ref> refs = new ArrayList<>();
            do {
                refs.add(ref());
            } while (acceptSymbol(","));
            expectSymbol(")");
            example = List.copyOf(refs);
        } else if (accept("CHILDREN")) {
            expect("OF");
            container = Optional.of(ref());
        } else {
            expectSymbol("(");
            pattern = List.copyOf(patternEdges());
            expectSymbol(")");
        }
        boolean identity = accept("IDENTITY");
        if (identity && !pattern.isEmpty()) {
            throw HStoreException.invalid("IDENTITY compares atoms, so it needs an example from the data: EDGES (...) or CHILDREN OF ...");
        }
        expect("IN");
        Ast.SimilarSpace space;
        if (accept("CHILDREN")) {
            expect("OF");
            space = new Ast.SimilarSpace.Children(identifier());
        } else if (accept("AROUND")) {
            Ref anchor = ref();
            long size = accept("SIZE") ? longValue() : 3;
            List<String> through = new ArrayList<>();
            if (accept("THROUGH")) {
                do {
                    through.add(identifier());
                } while (acceptSymbol(","));
            }
            space = new Ast.SimilarSpace.Around(anchor, size, List.copyOf(through));
        } else {
            expect("SETS");
            expectSymbol("(");
            List<List<Ref>> sets = new ArrayList<>();
            do {
                expectSymbol("(");
                List<Ref> set = new ArrayList<>();
                do {
                    set.add(ref());
                } while (acceptSymbol(","));
                expectSymbol(")");
                sets.add(List.copyOf(set));
            } while (acceptSymbol(","));
            expectSymbol(")");
            space = new Ast.SimilarSpace.Sets(List.copyOf(sets));
        }
        OptionalDouble within = OptionalDouble.empty();
        OptionalLong top = OptionalLong.empty();
        if (accept("WITHIN")) {
            within = OptionalDouble.of(number().doubleValue());
        } else {
            expect("TOP");
            top = OptionalLong.of(longValue());
            if (top.getAsLong() < 1 || top.getAsLong() > 10_000) {
                throw HStoreException.invalid("TOP must be between 1 and 10000");
            }
        }
        return new Statement.Similar(pattern, example, container, identity, space, within, top);
    }

    private Statement motif() {
        if (accept("PROFILE")) {
            Ref edge = ref();
            return new Statement.MotifProfile(edge, accept("OF") ? Optional.of(identifier()) : Optional.empty());
        }
        long code = longValue();
        if (code < 1 || code > 127) {
            throw HStoreException.invalid("a motif is a code between 1 and 127, got " + code);
        }
        List<Optional<String>> types = new ArrayList<>(List.of(Optional.empty(), Optional.empty(), Optional.empty()));
        if (accept("TYPES")) {
            expectSymbol("(");
            for (int i = 0; i < 3; i++) {
                if (i > 0) {
                    expectSymbol(",");
                }
                types.set(i, accept("ANY") ? Optional.empty() : Optional.of(identifier()));
            }
            expectSymbol(")");
        }
        Optional<Ref> around = accept("AROUND") ? Optional.of(ref()) : Optional.empty();
        List<Ast.MotifRegion> regions = new ArrayList<>();
        while (accept("REGION")) {
            String bits = string();
            if (!bits.matches("[01]{3}") || bits.equals("000")) {
                throw HStoreException.invalid("a region is written as three bits for e3, e2 and e1, such as '011'; got '" + bits + "'");
            }
            int region = Integer.parseInt(bits, 2);
            expect("HAS");
            Optional<Ref> member = Optional.empty();
            Optional<String> memberType = Optional.empty();
            if (accept("ANY")) {
                if (peek().kind() == Token.Kind.STRING) {
                    memberType = Optional.of(string());
                } else if (peek().kind() == Token.Kind.WORD && !peekWordAt(0, "ROLES", "REGION", "NESTED", "RANK", "LIMIT", "AROUND")) {
                    memberType = Optional.of(identifier());
                }
            } else {
                member = Optional.of(ref());
            }
            List<List<String>> roles = new ArrayList<>();
            if (accept("ROLES")) {
                expectSymbol("(");
                do {
                    List<String> alternatives = new ArrayList<>();
                    if (!accept("ANY")) {
                        do {
                            alternatives.add(identifier());
                        } while (accept("OR"));
                    }
                    roles.add(List.copyOf(alternatives));
                } while (acceptSymbol(","));
                expectSymbol(")");
            }
            regions.add(new Ast.MotifRegion(region, member, memberType, List.copyOf(roles)));
        }
        boolean nested = accept("NESTED");
        Optional<String> rankBy = Optional.empty();
        if (accept("RANK")) {
            expect("BY");
            String weight = identifier().toUpperCase(java.util.Locale.ROOT);
            if (!List.of("COMMON", "DISJOINT", "INDEPENDENT").contains(weight)) {
                throw HStoreException.invalid("RANK BY takes COMMON, DISJOINT or INDEPENDENT");
            }
            rankBy = Optional.of(weight);
        }
        return new Statement.MotifMatch((int) code, List.copyOf(types), around, List.copyOf(regions), nested, rankBy, limit());
    }

    private OptionalLong limit() {
        return accept("LIMIT") ? OptionalLong.of(longValue()) : OptionalLong.empty();
    }

    private String identifier() {
        Token token = next();
        if (token.kind() != Token.Kind.WORD) {
            throw error("expected an identifier but found " + token);
        }
        if (token.text().length() > Dictionary.NAME_LIMIT) {
            throw error("a name can be at most " + Dictionary.NAME_LIMIT + " characters long");
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

    private boolean peekWord(String word) {
        return peek().is(word);
    }

    private boolean peekWordAt(int ahead, String... words) {
        Token token = peek(ahead);
        return java.util.Arrays.stream(words).anyMatch(token::is);
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

package io.hstore.db.query;

import io.hstore.db.Atom;
import io.hstore.db.Reader;
import io.hstore.db.query.Ast.Expr;
import io.hstore.db.query.QueryResult.Cell;
import io.hstore.db.semantic.SemanticPlane;
import io.hstore.db.stats.Statistics;
import io.hstore.db.temporal.StateBindings;
import io.hstore.db.value.Value;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.AtomRecord.EdgeRecord;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.tree.TreeAlgebra;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

final class Evaluator {

    interface Context {
        Reader reader();

        long resolve(Ast.Ref ref);

        Set<Long> similar(String text, int k, SemanticPlane.Consistency consistency);
    }

    private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max");

    private final Context context;

    Evaluator(Context context) {
        this.context = context;
    }

    boolean test(Expr expr, Map<String, Long> binding) {
        return switch (expr) {
            case Expr.And(List<Expr> terms) -> terms.stream().allMatch(term -> test(term, binding));
            case Expr.Or(List<Expr> terms) -> terms.stream().anyMatch(term -> test(term, binding));
            case Expr.Not(Expr term) -> !test(term, binding);
            case Expr.Contains(String variable, List<Ast.Ref> members) -> isEdge(atom(variable, binding))
                    && members.stream().allMatch(member -> reader().view().contains(atom(variable, binding), context.resolve(member)));
            case Expr.Has(String variable, Ast.Ref member, Optional<String> role) -> reader().view()
                    .incidence(atom(variable, binding), context.resolve(member))
                    .map(incidence -> role.map(name -> reader().roles(incidence.roleSet()).contains(name)).orElse(true))
                    .orElse(false);
            case Expr.In(String variable, Ast.Ref edge) -> reader().view().contains(context.resolve(edge), atom(variable, binding));
            case Expr.Similar(String variable, String text, int k, var consistency) -> context.similar(text, k, consistency).contains(atom(variable, binding));
            case Expr.ValidAt(String variable, Expr instant) -> validAt(atom(variable, binding), (long) number(instant, binding));
            case Expr.Between(Expr value, Expr low, Expr high) -> {
                Value actual = value(value, binding);
                yield !(actual instanceof Value.Null) && actual.compareTo(value(low, binding)) >= 0 && actual.compareTo(value(high, binding)) <= 0;
            }
            case Expr.Compare(Ast.Comparison op, Expr left, Expr right) -> compare(op, value(left, binding), value(right, binding));
            default -> value(expr, binding).truthy();
        };
    }

    private boolean validAt(long atom, long instant) {
        if (isEdge(atom)) {
            return reader().edge(atom).validAt(instant).findAny().isPresent();
        }
        return reader().view().incident(atom).anyMatch(incident -> reader().view().incidence(incident.edge(), atom)
                .map(incidence -> incidence.validAt(instant)).orElse(false));
    }

    private static boolean compare(Ast.Comparison op, Value left, Value right) {
        if (left instanceof Value.Null || right instanceof Value.Null) {
            return op == Ast.Comparison.NE && !(left instanceof Value.Null && right instanceof Value.Null);
        }
        int order = left.compareTo(right);
        return switch (op) {
            case EQ -> order == 0;
            case NE -> order != 0;
            case LT -> order < 0;
            case LE -> order <= 0;
            case GT -> order > 0;
            case GE -> order >= 0;
        };
    }

    Value value(Expr expr, Map<String, Long> binding) {
        return switch (expr) {
            case Expr.Literal(Value value) -> value;
            case Expr.Atom(Ast.Ref ref) -> new Value.Int(context.resolve(ref));
            case Expr.Var(String name) -> new Value.Int(atom(name, binding));
            case Expr.Prop(String variable, String property) -> reader().property(atom(variable, binding), property).orElse(Value.NULL);
            case Expr.Call(String function, List<Expr> arguments) -> call(function, arguments, binding);
            case Expr.Arithmetic(char op, Expr left, Expr right) -> arithmetic(op, value(left, binding), value(right, binding));
            case Expr.Star _ -> throw HStoreException.invalid("'*' is only valid inside count(*)");
            default -> new Value.Bool(test(expr, binding));
        };
    }

    private Value call(String function, List<Expr> arguments, Map<String, Long> binding) {
        return switch (function) {
            case "id" -> new Value.Int(atomArgument(arguments, 0, binding));
            case "type" -> new Value.Text(reader().require(atomArgument(arguments, 0, binding)).type().name());
            case "key" -> reader().require(atomArgument(arguments, 0, binding)).key().<Value>map(Value.Text::new).orElse(Value.NULL);
            case "card", "cardinality" -> new Value.Int(reader().cardinality(atomArgument(arguments, 0, binding)));
            case "degree" -> new Value.Int(reader().degree(atomArgument(arguments, 0, binding)));
            case "weight", "weightsum" -> new Value.Real(Statistics.weight(reader().view(), atomArgument(arguments, 0, binding)));
            case "overlap" -> new Value.Int(Statistics.overlap(reader().view(), atomArgument(arguments, 0, binding), atomArgument(arguments, 1, binding)));
            case "jaccard" -> new Value.Real(TreeAlgebra.jaccard(reader().edge(atomArgument(arguments, 0, binding)).membership(),
                    reader().edge(atomArgument(arguments, 1, binding)).membership()));
            case "state" -> arguments.size() > 1
                    ? StateBindings.stateAt(reader(), atomArgument(arguments, 0, binding), (long) number(arguments.get(1), binding)).orElse(Value.NULL)
                    : StateBindings.state(reader(), atomArgument(arguments, 0, binding)).map(StateBindings.Binding::value).orElse(Value.NULL);
            case "json" -> reader().document(atomArgument(arguments, 0, binding))
                    .flatMap(document -> document.select(text(arguments.subList(1, arguments.size()), binding)).findFirst())
                    .orElse(Value.NULL);
            case "lower" -> new Value.Text(text(arguments, binding).toLowerCase());
            case "upper" -> new Value.Text(text(arguments, binding).toUpperCase());
            case "length" -> new Value.Int(text(arguments, binding).length());
            case "now" -> new Value.Time(System.currentTimeMillis());
            case "members" -> new Value.Text(reader().edge(atomArgument(arguments, 0, binding)).stream()
                    .map(incidence -> "@" + incidence.member()).collect(Collectors.joining(",")));
            default -> throw HStoreException.invalid("unknown function " + function + "()");
        };
    }

    private String text(List<Expr> arguments, Map<String, Long> binding) {
        Value value = value(arguments.getFirst(), binding);
        return value instanceof Value.Text(String text) ? text : value.render();
    }

    private long atomArgument(List<Expr> arguments, int index, Map<String, Long> binding) {
        if (arguments.size() <= index) {
            throw HStoreException.invalid("missing function argument " + (index + 1));
        }
        Value value = value(arguments.get(index), binding);
        if (!(value instanceof Value.Int(long atom))) {
            throw HStoreException.invalid("expected an atom argument but got " + value.render());
        }
        return atom;
    }

    private static Value arithmetic(char op, Value left, Value right) {
        if (left instanceof Value.Null || right instanceof Value.Null) {
            return Value.NULL;
        }
        if (left instanceof Value.Int(long a) && right instanceof Value.Int(long b) && op != '/') {
            return new Value.Int(switch (op) {
                case '+' -> Math.addExact(a, b);
                case '-' -> Math.subtractExact(a, b);
                default -> Math.multiplyExact(a, b);
            });
        }
        double a = left.number().orElseThrow(() -> HStoreException.invalid(left.render() + " is not numeric"));
        double b = right.number().orElseThrow(() -> HStoreException.invalid(right.render() + " is not numeric"));
        return new Value.Real(switch (op) {
            case '+' -> a + b;
            case '-' -> a - b;
            case '*' -> a * b;
            default -> a / b;
        });
    }

    private double number(Expr expr, Map<String, Long> binding) {
        Value value = value(expr, binding);
        return value.number().orElseThrow(() -> HStoreException.invalid(value.render() + " is not numeric"));
    }

    Cell cell(Expr expr, Map<String, Long> binding) {
        return switch (expr) {
            case Expr.Var(String name) -> atomCell(atom(name, binding));
            case Expr.Atom(Ast.Ref ref) -> atomCell(context.resolve(ref));
            case Expr.Call(String function, List<Expr> arguments) when function.equals("members") -> new Cell.Items(
                    reader().edge(atomArgument(arguments, 0, binding)).stream().map(Incidence::member).map(this::atomCell).toList());
            default -> QueryResult.scalar(value(expr, binding));
        };
    }

    Cell atomCell(long atom) {
        return reader().atom(atom)
                .map(found -> new Cell.AtomCell(atom, label(found)))
                .orElseGet(() -> new Cell.AtomCell(atom, ""));
    }

    private static String label(Atom atom) {
        return atom.type().name() + atom.key().map(key -> ":'" + key + "'").orElse("");
    }

    static boolean isAggregate(Expr expr) {
        return expr instanceof Expr.Call(String function, List<Expr> _) && AGGREGATES.contains(function);
    }

    Cell aggregate(Expr expr, List<Map<String, Long>> bindings) {
        Expr.Call call = (Expr.Call) expr;
        if (call.function().equals("count")) {
            boolean star = call.arguments().isEmpty() || call.arguments().getFirst() instanceof Expr.Star;
            return QueryResult.scalar(new Value.Int(star ? bindings.size()
                    : bindings.stream().filter(binding -> !(value(call.arguments().getFirst(), binding) instanceof Value.Null)).count()));
        }
        List<Value> values = bindings.stream()
                .map(binding -> value(call.arguments().getFirst(), binding))
                .filter(value -> !(value instanceof Value.Null))
                .toList();
        if (values.isEmpty()) {
            return QueryResult.scalar(Value.NULL);
        }
        return switch (call.function()) {
            case "sum" -> QueryResult.number(values.stream().mapToDouble(value -> value.number().orElse(0)).sum());
            case "avg" -> QueryResult.scalar(new Value.Real(values.stream().mapToDouble(value -> value.number().orElse(0)).average().orElse(0)));
            case "min" -> QueryResult.scalar(values.stream().min(Value::compareTo).orElseThrow());
            default -> QueryResult.scalar(values.stream().max(Value::compareTo).orElseThrow());
        };
    }

    private long atom(String variable, Map<String, Long> binding) {
        Long atom = binding.get(variable);
        if (atom == null) {
            throw HStoreException.invalid("unbound variable " + variable);
        }
        return atom;
    }

    private boolean isEdge(long atom) {
        return reader().view().atom(atom).map(record -> record instanceof EdgeRecord).orElse(false);
    }

    private Reader reader() {
        return context.reader();
    }

    static String describe(Expr expr) {
        return switch (expr) {
            case Expr.Literal(Value value) -> value.render();
            case Expr.Atom(Ast.Ref ref) -> describe(ref);
            case Expr.Var(String name) -> name;
            case Expr.Prop(String variable, String property) -> variable + "." + property;
            case Expr.Call(String function, List<Expr> arguments) ->
                    function + "(" + arguments.stream().map(Evaluator::describe).collect(Collectors.joining(", ")) + ")";
            case Expr.Star _ -> "*";
            case Expr.Compare(Ast.Comparison op, Expr left, Expr right) -> describe(left) + " " + symbol(op) + " " + describe(right);
            case Expr.Arithmetic(char op, Expr left, Expr right) -> describe(left) + " " + op + " " + describe(right);
            case Expr.And(List<Expr> terms) -> terms.stream().map(Evaluator::describe).collect(Collectors.joining(" AND "));
            case Expr.Or(List<Expr> terms) -> terms.stream().map(Evaluator::describe).collect(Collectors.joining(" OR ", "(", ")"));
            case Expr.Not(Expr term) -> "NOT " + describe(term);
            case Expr.Between(Expr value, Expr low, Expr high) -> describe(value) + " BETWEEN " + describe(low) + " AND " + describe(high);
            case Expr.Contains(String variable, List<Ast.Ref> members) ->
                    variable + " CONTAINS " + members.stream().map(Evaluator::describe).collect(Collectors.joining(", ", "(", ")"));
            case Expr.Has(String variable, Ast.Ref member, Optional<String> role) ->
                    variable + " HAS " + describe(member) + role.map(name -> " AS " + name).orElse("");
            case Expr.In(String variable, Ast.Ref edge) -> variable + " IN " + describe(edge);
            case Expr.Similar(String variable, String text, int k, var consistency) -> variable + " SIMILAR TO '" + text + "' TOP " + k + " " + consistency;
            case Expr.ValidAt(String variable, Expr instant) -> variable + " VALID AT " + describe(instant);
        };
    }

    static String describe(Ast.Ref ref) {
        return switch (ref) {
            case Ast.Ref.Id(long id) -> "@" + id;
            case Ast.Ref.Keyed(String type, String key) -> "@" + type + ":'" + key + "'";
            case Ast.Ref.Variable(String name) -> "$" + name;
        };
    }

    private static String symbol(Ast.Comparison op) {
        return switch (op) {
            case EQ -> "=";
            case NE -> "!=";
            case LT -> "<";
            case LE -> "<=";
            case GT -> ">";
            case GE -> ">=";
        };
    }
}

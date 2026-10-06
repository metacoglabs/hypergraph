// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public record Pattern(List<Variable> variables, List<Constraint> constraints) {

    public record Variable(String name, boolean edge, Optional<String> type) {
        public static Variable edge(String name, String type) {
            return new Variable(name, true, Optional.ofNullable(type));
        }

        public static Variable node(String name, String type) {
            return new Variable(name, false, Optional.ofNullable(type));
        }
    }

    public sealed interface Constraint {
        Set<String> variables();

        record Contains(String edge, String member) implements Constraint {
            public Set<String> variables() {
                return Set.of(edge, member);
            }
        }

        record HasRole(String edge, String member, String role) implements Constraint {
            public Set<String> variables() {
                return Set.of(edge, member);
            }
        }

        record Cardinality(String edge, long min, long max) implements Constraint {
            public Set<String> variables() {
                return Set.of(edge);
            }
        }

        record Shares(String left, String right, long min, long max) implements Constraint {
            public Set<String> variables() {
                return Set.of(left, right);
            }
        }

        record SubsetOf(String inner, String outer) implements Constraint {
            public Set<String> variables() {
                return Set.of(inner, outer);
            }
        }

        record Distinct(String left, String right) implements Constraint {
            public Set<String> variables() {
                return Set.of(left, right);
            }
        }

        record Bound(String variable, long atom) implements Constraint {
            public Set<String> variables() {
                return Set.of(variable);
            }
        }

        record ValidAt(String edge, long instant) implements Constraint {
            public Set<String> variables() {
                return Set.of(edge);
            }
        }
    }

    public Pattern {
        variables = List.copyOf(variables);
        constraints = List.copyOf(constraints);
    }

    public Variable variable(String name) {
        return variables.stream().filter(variable -> variable.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("undeclared pattern variable " + name));
    }
}

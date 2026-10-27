// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.schema.TypeDef;
import io.hstore.db.value.Value;
import io.hstore.engine.HStoreException;

import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

public record RankQuery(Optional<TypeDef> type, List<Slot> slots, Optional<TimeSpec> time, List<Preference> preferences,
                        OptionalLong size, Weights weights, int top) {

    public static final int MAX_SLOTS = 16;
    public static final int MAX_PREFERENCES = 16;
    public static final int MAX_TOP = 10_000;

    public RankQuery {
        slots = List.copyOf(slots);
        preferences = List.copyOf(preferences);
        if (slots.isEmpty() || slots.size() > MAX_SLOTS) {
            throw HStoreException.invalid("RANK needs between 1 and " + MAX_SLOTS + " slots");
        }
        if (preferences.size() > MAX_PREFERENCES) {
            throw HStoreException.invalid("RANK accepts at most " + MAX_PREFERENCES + " preferences");
        }
        if (top < 1 || top > MAX_TOP) {
            throw HStoreException.invalid("TOP must be between 1 and " + MAX_TOP);
        }
        if (size.isPresent() && size.getAsLong() < 1) {
            throw HStoreException.invalid("SIZE must be at least 1");
        }
        type.ifPresent(found -> {
            if (!found.kind().isEdge()) {
                throw HStoreException.invalid(found.name() + " is not a hyperedge type");
            }
        });
    }

    public Weights effectiveWeights() {
        return weights.restrictedTo(!preferences.isEmpty(), time.isPresent(), size.isPresent());
    }

    public sealed interface Target {
        record Atom(long id) implements Target {
        }

        record OfType(TypeDef type) implements Target {
        }
    }

    public record Slot(Target target, Set<String> roles, double weight, boolean required) {
        public Slot {
            roles = Set.copyOf(roles);
            requireWeight("slot", weight);
        }
    }

    public sealed interface TimeSpec {
        record At(long instant, double tau) implements TimeSpec {
            public At {
                if (!Double.isFinite(tau) || tau < 0) {
                    throw HStoreException.invalid("TAU must be a number of at least 0");
                }
            }
        }

        record During(long from, long to) implements TimeSpec {
            public During {
                if (from >= to || from == Long.MIN_VALUE || to == Long.MAX_VALUE) {
                    throw HStoreException.invalid("VALID DURING needs a finite, non-empty interval");
                }
            }
        }
    }

    public sealed interface Preference {
        String property();

        double weight();

        record Equals(String property, Value target, double weight) implements Preference {
            public Equals {
                requireWeight("preference", weight);
            }
        }

        record Near(String property, double target, double scale, double weight) implements Preference {
            public Near {
                requireWeight("preference", weight);
                if (!Double.isFinite(target) || !Double.isFinite(scale) || scale <= 0) {
                    throw HStoreException.invalid("NEAR needs a finite target and a positive SCALE");
                }
            }
        }
    }

    public record Weights(double members, double roles, double attributes, double time, double topology) {
        public static final Weights DEFAULT = new Weights(0.3, 0.2, 0.2, 0.2, 0.1);

        public Weights {
            for (double weight : new double[]{members, roles, attributes, time, topology}) {
                if (!Double.isFinite(weight) || weight < 0) {
                    throw HStoreException.invalid("component weights must be finite and not negative");
                }
            }
        }

        Weights restrictedTo(boolean useAttributes, boolean useTime, boolean useTopology) {
            Weights used = new Weights(members, roles, useAttributes ? attributes : 0, useTime ? time : 0, useTopology ? topology : 0);
            double total = used.members + used.roles + used.attributes + used.time + used.topology;
            if (total <= 0) {
                throw HStoreException.invalid("the weights of the components this query uses add up to zero");
            }
            return new Weights(used.members / total, used.roles / total, used.attributes / total, used.time / total, used.topology / total);
        }
    }

    private static void requireWeight(String kind, double weight) {
        if (!Double.isFinite(weight) || weight <= 0 || weight > 1_000_000) {
            throw HStoreException.invalid("a " + kind + " WEIGHT must be a positive number up to 1000000");
        }
    }
}

// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.Atom;
import io.hstore.db.Reader;
import io.hstore.db.hora.Budget;
import io.hstore.db.retrieve.RankQuery.Preference;
import io.hstore.db.retrieve.RankQuery.Slot;
import io.hstore.db.retrieve.RankQuery.Target;
import io.hstore.db.retrieve.RankQuery.TimeSpec;
import io.hstore.db.retrieve.RankQuery.Weights;
import io.hstore.db.value.Value;
import io.hstore.engine.Dictionary;
import io.hstore.engine.topology.Incidence;

import java.util.BitSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

public final class RoleTimeScorer {

    private static final int MEMBER_TYPE_CACHE = 65_536;

    public record Components(double members, double roles, double attributes, double time, double topology) {
    }

    public record EdgeScore(long edge, double score, Components components, double[] slotCover) {
    }

    private record Fill(int value, double time) {
        static final Fill EMPTY = new Fill(0, 0);

        boolean better(Fill other) {
            return value > other.value || value == other.value && time > other.time;
        }
    }

    private final Reader reader;
    private final RankQuery query;
    private final Weights alpha;
    private final List<BitSet> accepted;
    private final double slotWeights;
    private final double preferenceWeights;
    private final Map<Long, OptionalInt> memberTypes = new HashMap<>();
    private final Budget.Meter meter;

    public RoleTimeScorer(Reader reader, RankQuery query, Budget.Meter meter) {
        this.reader = reader;
        this.query = query;
        this.meter = meter;
        this.alpha = query.effectiveWeights();
        Dictionary dictionary = reader.database().engine().dictionary();
        this.accepted = query.slots().stream().map(slot -> anyOf(dictionary, slot)).toList();
        this.slotWeights = query.slots().stream().mapToDouble(Slot::weight).sum();
        this.preferenceWeights = query.preferences().stream().mapToDouble(Preference::weight).sum();
    }

    static BitSet anyOf(Dictionary dictionary, Slot slot) {
        BitSet sets = new BitSet();
        slot.roles().forEach(role -> sets.or(dictionary.roleSetsWithAll(List.of(role))));
        return sets;
    }

    BitSet accepted(int slot) {
        return accepted.get(slot);
    }

    public double bound(long edge) {
        Fill[] fills = new Fill[query.slots().size()];
        for (int i = 0; i < fills.length; i++) {
            fills[i] = query.slots().get(i).target() instanceof Target.Atom(long atom)
                    ? fixed(edge, i, atom)
                    : new Fill(2, 1);
        }
        return combine(edge, fills, 1).score();
    }

    public Optional<EdgeScore> score(long edge) {
        Fill[] fills = new Fill[query.slots().size()];
        boolean typed = false;
        for (int i = 0; i < fills.length; i++) {
            if (query.slots().get(i).target() instanceof Target.Atom(long atom)) {
                fills[i] = fixed(edge, i, atom);
            } else {
                fills[i] = Fill.EMPTY;
                typed = true;
            }
        }
        if (typed) {
            reader.edge(edge).stream().forEach(incidence -> {
                meter.consume(1);
                OptionalInt type = memberType(incidence.member());
                if (type.isEmpty()) {
                    return;
                }
                for (int i = 0; i < fills.length; i++) {
                    if (query.slots().get(i).target() instanceof Target.OfType(var slotType) && slotType.id() == type.getAsInt()) {
                        Fill candidate = fill(i, incidence);
                        if (candidate.better(fills[i])) {
                            fills[i] = candidate;
                        }
                    }
                }
            });
        }
        for (int i = 0; i < fills.length; i++) {
            if (query.slots().get(i).required() && fills[i].value() < 2) {
                return Optional.empty();
            }
        }
        return Optional.of(combine(edge, fills, attributes(edge)));
    }

    private OptionalInt memberType(long member) {
        OptionalInt cached = memberTypes.get(member);
        if (cached != null) {
            return cached;
        }
        OptionalInt type = reader.atom(member).map(found -> OptionalInt.of(found.type().id())).orElse(OptionalInt.empty());
        if (memberTypes.size() < MEMBER_TYPE_CACHE) {
            memberTypes.put(member, type);
        }
        return type;
    }

    private Fill fixed(long edge, int slot, long atom) {
        return reader.incidence(edge, atom).map(incidence -> fill(slot, incidence)).orElse(Fill.EMPTY);
    }

    private Fill fill(int slot, Incidence incidence) {
        boolean inRole = query.slots().get(slot).roles().isEmpty() || accepted.get(slot).get(incidence.roleSet());
        return new Fill(inRole ? 2 : 1, query.time().map(spec -> time(spec, incidence)).orElse(1.0));
    }

    static double time(TimeSpec spec, Incidence incidence) {
        return switch (spec) {
            case TimeSpec.At(long instant, double tau) -> {
                if (incidence.validAt(instant)) {
                    yield 1;
                }
                double distance = instant < incidence.validFrom()
                        ? gap(incidence.validFrom(), instant)
                        : gap(instant, incidence.validTo() - 1);
                yield tau == 0 ? 0 : Math.exp(-distance / tau);
            }
            case TimeSpec.During(long start, long end) -> {
                long low = Math.max(incidence.validFrom(), start);
                long high = Math.min(incidence.validTo(), end);
                yield high <= low ? 0 : Math.min(1, gap(high, low) / gap(end, start));
            }
        };
    }

    private static double gap(long high, long low) {
        long difference = high - low;
        return ((high ^ low) & (high ^ difference)) < 0 ? (double) high - (double) low : difference;
    }

    private double attributes(long edge) {
        if (query.preferences().isEmpty()) {
            return 0;
        }
        double total = 0;
        for (Preference preference : query.preferences()) {
            Optional<Value> value = reader.property(edge, preference.property());
            total += preference.weight() * value.map(found -> similarity(preference, found)).orElse(0.0);
        }
        return total / preferenceWeights;
    }

    static double similarity(Preference preference, Value value) {
        return switch (preference) {
            case Preference.Equals(String _, Value target, double _) ->
                    comparable(value) && comparable(target) && value.compareTo(target) == 0 ? 1 : 0;
            case Preference.Near(String _, double target, double scale, double _) -> {
                double number = value.tag().numeric() ? value.number().orElse(Double.NaN) : Double.NaN;
                yield Double.isFinite(number) ? Math.max(0, 1 - Math.abs(number - target) / scale) : 0;
            }
        };
    }

    private static boolean comparable(Value value) {
        return !(value instanceof Value.Null) && !(value instanceof Value.Real(double number) && !Double.isFinite(number));
    }

    private EdgeScore combine(long edge, Fill[] fills, double attributes) {
        double members = 0;
        double roles = 0;
        double filledWeight = 0;
        double time = 0;
        double[] cover = new double[fills.length];
        for (int i = 0; i < fills.length; i++) {
            double weight = query.slots().get(i).weight();
            Fill fill = fills[i];
            if (fill.value() > 0) {
                members += weight;
                filledWeight += weight;
                time += weight * fill.time();
            }
            if (fill.value() == 2) {
                roles += weight;
            }
            cover[i] = fill.value() / 2.0;
        }
        Components components = new Components(members / slotWeights, roles / slotWeights,
                query.preferences().isEmpty() ? Double.NaN : attributes,
                query.time().isEmpty() ? Double.NaN : filledWeight == 0 ? 0 : time / filledWeight,
                query.size().isEmpty() ? Double.NaN : topology(edge));
        double score = alpha.members() * components.members() + alpha.roles() * components.roles()
                + (query.preferences().isEmpty() ? 0 : alpha.attributes() * components.attributes())
                + (query.time().isEmpty() ? 0 : alpha.time() * components.time())
                + (query.size().isEmpty() ? 0 : alpha.topology() * components.topology());
        return new EdgeScore(edge, score, components, cover);
    }

    private double topology(long edge) {
        double size = reader.cardinality(edge);
        double target = query.size().orElseThrow();
        return size == 0 ? 0 : Math.min(size, target) / Math.max(size, target);
    }

    static Optional<Atom> admitted(Reader reader, long edge, RankQuery query) {
        return reader.atom(edge).filter(Atom::isEdge).filter(atom -> query.type().map(type -> type.id() == atom.type().id()).orElse(true));
    }
}

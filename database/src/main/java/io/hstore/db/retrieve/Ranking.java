// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.Atom;
import io.hstore.db.Reader;
import io.hstore.db.hora.Budget;
import io.hstore.db.retrieve.RankQuery.Slot;
import io.hstore.db.retrieve.RankQuery.Target;
import io.hstore.db.retrieve.RoleTimeScorer.EdgeScore;
import io.hstore.engine.index.IndexValues;
import io.hstore.engine.index.PostingCursor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

public final class Ranking {

    private static final double TOLERANCE = 1e-9;
    private static final Comparator<EdgeScore> BEST_FIRST =
            Comparator.comparingDouble(EdgeScore::score).reversed().thenComparingLong(EdgeScore::edge);

    private final Reader reader;
    private final RankQuery query;

    private Ranking(Reader reader, RankQuery query) {
        this.reader = reader;
        this.query = query;
    }

    public static Ranking of(Reader reader, RankQuery query) {
        return new Ranking(reader, query);
    }

    public String access() {
        List<Integer> anchors = requiredAnchors();
        if (!anchors.isEmpty()) {
            return "IncidentIntersection" + anchors.stream().map(i -> describe(query.slots().get(i))).toList();
        }
        return query.type().map(type -> "TypeScan(" + type.name() + ")").orElse("CatalogScan(edges)");
    }

    public List<EdgeScore> top(Budget.Meter meter) {
        RoleTimeScorer scorer = new RoleTimeScorer(reader, query, meter);
        long[] edges = new long[64];
        double[] bounds = new double[64];
        int count = 0;
        for (var candidates = candidates(scorer).iterator(); candidates.hasNext(); ) {
            long edge = candidates.nextLong();
            meter.consume(1);
            Optional<Atom> admitted = RoleTimeScorer.admitted(reader, edge, query);
            if (admitted.isEmpty()) {
                continue;
            }
            if (count == edges.length) {
                edges = Arrays.copyOf(edges, count * 2);
                bounds = Arrays.copyOf(bounds, count * 2);
            }
            edges[count] = edge;
            bounds[count] = scorer.bound(edge);
            count++;
        }
        long[] ids = edges;
        double[] limits = bounds;
        Integer[] order = IntStream.range(0, count).boxed().toArray(Integer[]::new);
        Arrays.sort(order, Comparator.<Integer>comparingDouble(i -> limits[i]).reversed().thenComparingLong(i -> ids[i]));
        PriorityQueue<EdgeScore> best = new PriorityQueue<>(BEST_FIRST.reversed());
        for (int i : order) {
            if (best.size() == query.top() && limits[i] + TOLERANCE < best.peek().score()) {
                break;
            }
            meter.consume(1);
            scorer.score(ids[i]).ifPresent(scored -> {
                best.add(scored);
                if (best.size() > query.top()) {
                    best.poll();
                }
            });
        }
        List<EdgeScore> result = new ArrayList<>(best);
        result.sort(BEST_FIRST);
        return result;
    }

    private List<Integer> requiredAnchors() {
        return IntStream.range(0, query.slots().size())
                .filter(i -> query.slots().get(i).required() && query.slots().get(i).target() instanceof Target.Atom)
                .boxed().toList();
    }

    private LongStream candidates(RoleTimeScorer scorer) {
        List<Integer> anchors = requiredAnchors();
        if (!anchors.isEmpty()) {
            List<PostingCursor<IndexValues.Incident>> cursors = anchors.stream().map(i -> {
                Slot slot = query.slots().get(i);
                long atom = ((Target.Atom) slot.target()).id();
                if (slot.roles().isEmpty()) {
                    return reader.view().incidentCursor(atom);
                }
                BitSet accepted = scorer.accepted(i);
                return reader.view().incidentCursor(atom, incident -> accepted.get(incident.roleSet()));
            }).toList();
            return PostingCursor.intersect(cursors);
        }
        return query.type().map(type -> reader.atomsOfType(type.id()))
                .orElseGet(() -> reader.allAtoms(true).mapToLong(Atom::id));
    }

    private static String describe(Slot slot) {
        long atom = ((Target.Atom) slot.target()).id();
        return "@" + atom + (slot.roles().isEmpty() ? "" : " AS " + String.join(" OR ", slot.roles().stream().sorted().toList()));
    }
}

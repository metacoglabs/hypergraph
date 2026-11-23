// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Member;
import io.hstore.db.MemberSpec;
import io.hstore.db.Reader;
import io.hstore.db.hora.Budget;
import io.hstore.db.query.Session;
import io.hstore.db.retrieve.MotifMatcher.Occurrence;
import io.hstore.db.retrieve.MotifMatcher.Query;
import io.hstore.db.retrieve.MotifMatcher.Region;
import io.hstore.db.retrieve.MotifMatcher.Weight;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MotifTest {

    private static final List<String> ROLES = List.of("actor", "participant", "decision");
    private static final int[][] ORDERS = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};

    @TempDir
    Path directory;

    HypergraphDatabase database;
    List<Long> people = new ArrayList<>();
    List<Long> edges = new ArrayList<>();
    int names;

    @BeforeEach
    void open() {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        try (Session admin = new Session(database)) {
            admin.executeAll("""
                    CREATE NODE TYPE Person;
                    CREATE NODE TYPE Topic;
                    CREATE SET EDGE TYPE Meeting ROLES (actor, participant, decision);
                    CREATE SET EDGE TYPE Project ROLES (actor, participant, decision);
                    """);
        }
    }

    @AfterEach
    void close() {
        database.close();
    }

    private void randomGraph(RandomGenerator random, int count, int pool) {
        database.write(writer -> {
            for (int i = 0; i < pool; i++) {
                people.add(writer.node(i % 4 == 0 ? "Topic" : "Person", "p" + names++));
            }
            for (int e = 0; e < count; e++) {
                long edge = writer.edge(random.nextInt(3) == 0 ? "Project" : "Meeting");
                if (!edges.isEmpty() && random.nextInt(8) == 0) {
                    long copied = edges.get(random.nextInt(edges.size()));
                    writer.members(copied).forEach(member -> writer.add(edge, member.atom(), MemberSpec.roles(member.roles().toArray(String[]::new))));
                } else {
                    for (int m = 0; m < 1 + random.nextInt(5); m++) {
                        writer.add(edge, people.get(random.nextInt(people.size())), MemberSpec.roles(ROLES.get(random.nextInt(3))));
                    }
                    if (!edges.isEmpty() && random.nextInt(10) == 0) {
                        writer.add(edge, edges.get(random.nextInt(edges.size())), MemberSpec.PLAIN);
                    }
                }
                edges.add(edge);
            }
            return null;
        });
    }

    private record Graph(Map<Long, Set<Long>> members, Map<Long, Integer> types, Map<List<Long>, List<String>> roles) {
    }

    private Graph graph(Reader reader) {
        Map<Long, Set<Long>> members = new TreeMap<>();
        Map<Long, Integer> types = new HashMap<>();
        Map<List<Long>, List<String>> roles = new HashMap<>();
        for (long edge : edges) {
            types.put(edge, reader.typeOf(edge).orElseThrow());
            Set<Long> set = new HashSet<>();
            for (Member member : reader.members(edge).toList()) {
                set.add(member.atom());
                roles.put(List.of(edge, member.atom()), member.roles());
            }
            members.put(edge, set);
        }
        return new Graph(members, types, roles);
    }

    private static boolean in(int region, int position) {
        return (region >> position & 1) != 0;
    }

    private static int positionalCode(Graph graph, long[] e) {
        Set<Long> all = new HashSet<>();
        for (long edge : e) {
            all.addAll(graph.members().get(edge));
        }
        int code = 0;
        for (long v : all) {
            int region = 0;
            for (int i = 0; i < 3; i++) {
                if (graph.members().get(e[i]).contains(v)) {
                    region |= 1 << i;
                }
            }
            code |= 1 << (region - 1);
        }
        return code;
    }

    private static int canonicalByHand(Graph graph, long[] e) {
        int best = Integer.MAX_VALUE;
        for (int[] order : ORDERS) {
            best = Math.min(best, positionalCode(graph, new long[]{e[order[0]], e[order[1]], e[order[2]]}));
        }
        return best;
    }

    private static boolean overlap(Graph graph, long a, long b) {
        return graph.members().get(a).stream().anyMatch(graph.members().get(b)::contains);
    }

    private static List<long[]> triples(Graph graph, Set<Integer> scope) {
        List<Long> inScope = graph.members().keySet().stream().filter(edge -> scope.contains(graph.types().get(edge))).sorted().toList();
        List<long[]> triples = new ArrayList<>();
        for (int i = 0; i < inScope.size(); i++) {
            for (int j = i + 1; j < inScope.size(); j++) {
                for (int k = j + 1; k < inScope.size(); k++) {
                    long a = inScope.get(i);
                    long b = inScope.get(j);
                    long c = inScope.get(k);
                    int pairs = (overlap(graph, a, b) ? 1 : 0) + (overlap(graph, b, c) ? 1 : 0) + (overlap(graph, a, c) ? 1 : 0);
                    if (pairs >= 2) {
                        triples.add(new long[]{a, b, c});
                    }
                }
            }
        }
        return triples;
    }

    private static Map<Integer, Long> countByClass(Graph graph, List<long[]> triples) {
        Map<Integer, Long> counts = new TreeMap<>();
        triples.forEach(triple -> counts.merge(canonicalByHand(graph, triple), 1L, Long::sum));
        return counts;
    }

    private static Map<Integer, Long> asMap(List<MotifCensus.Count> counts) {
        Map<Integer, Long> map = new TreeMap<>();
        counts.forEach(count -> map.put(count.code(), Math.round(count.estimate())));
        return map;
    }

    @Test
    void exactCensusAndProfilesMatchEveryTriple() {
        for (long seed = 70; seed < 76; seed++) {
            people.clear();
            edges.clear();
            randomGraph(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(seed), 30, 12 + (int) seed % 10);
            database.read(reader -> {
                Graph graph = graph(reader);
                int meeting = reader.requireReadableType("Meeting").id();
                int project = reader.requireReadableType("Project").id();
                for (OptionalInt scope : List.of(OptionalInt.empty(), OptionalInt.of(meeting), OptionalInt.of(project))) {
                    Set<Integer> types = scope.isPresent() ? Set.of(scope.getAsInt()) : Set.of(meeting, project);
                    List<long[]> triples = triples(graph, types);
                    MotifCensus census = MotifCensus.of(reader, scope);
                    MotifCensus.Result result = census.exact(Budget.UNBOUNDED.start());
                    assertEquals(countByClass(graph, triples), asMap(result.counts()), "scope " + scope);
                    for (long edge : graph.members().keySet()) {
                        if (!types.contains(graph.types().get(edge))) {
                            continue;
                        }
                        List<long[]> containing = triples.stream().filter(t -> t[0] == edge || t[1] == edge || t[2] == edge).toList();
                        assertEquals(countByClass(graph, containing), asMap(census.profile(edge, Budget.UNBOUNDED.start())), "profile of " + edge);
                    }
                }
                return null;
            });
            database.write(writer -> {
                edges.forEach(writer::delete);
                return null;
            });
            edges.clear();
        }
    }

    @Test
    void countsSurviveAReopen() {
        randomGraph(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(81L), 40, 12);
        Query query = new Query(94, List.of(OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty()), OptionalLong.empty(), List.of(), false,
                Optional.of(Weight.DISJOINT), 10);
        var before = database.read(reader -> List.of(MotifCensus.of(reader, OptionalInt.empty()).exact(Budget.UNBOUNDED.start()),
                MotifCensus.of(reader, OptionalInt.empty()).sample(30, 9, Budget.UNBOUNDED.start()), MotifMatcher.find(reader, query, Budget.UNBOUNDED.start())));
        database.close();
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(EngineOptions.defaults().withPageSize(4096)));
        var after = database.read(reader -> List.of(MotifCensus.of(reader, OptionalInt.empty()).exact(Budget.UNBOUNDED.start()),
                MotifCensus.of(reader, OptionalInt.empty()).sample(30, 9, Budget.UNBOUNDED.start()), MotifMatcher.find(reader, query, Budget.UNBOUNDED.start())));
        assertEquals(before, after);
    }

    @Test
    void sampledCensusIsUnbiased() {
        randomGraph(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(77L), 40, 14);
        database.read(reader -> {
            MotifCensus census = MotifCensus.of(reader, OptionalInt.empty());
            Map<Integer, Long> exact = asMap(census.exact(Budget.UNBOUNDED.start()).counts());
            Map<Integer, Double> sum = new TreeMap<>();
            int runs = 400;
            for (int run = 0; run < runs; run++) {
                MotifCensus.Result sampled = census.sample(5, run, Budget.UNBOUNDED.start());
                assertEquals(5, sampled.samples());
                sampled.counts().forEach(count -> sum.merge(count.code(), count.estimate(), Double::sum));
            }
            MotifCensus.Result one = census.sample(2000, 1, Budget.UNBOUNDED.start());
            for (Map.Entry<Integer, Long> entry : exact.entrySet()) {
                double mean = sum.getOrDefault(entry.getKey(), 0.0) / runs;
                double error = one.counts().stream().filter(count -> count.code() == entry.getKey()).findFirst().orElseThrow().standardError();
                double perRun = error * Math.sqrt(2000.0 / 5);
                assertTrue(Math.abs(mean - entry.getValue()) <= 5 * perRun / Math.sqrt(runs) + 1e-9,
                        "class " + entry.getKey() + ": mean " + mean + " exact " + entry.getValue());
            }
            return null;
        });
    }

    @Test
    void autoFallsBackToSamplingAndExactFailsOnTheBudget() {
        randomGraph(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(78L), 60, 10);
        database.read(reader -> {
            MotifCensus census = MotifCensus.of(reader, OptionalInt.empty());
            Budget.Meter full = Budget.UNBOUNDED.start();
            census.exact(full);
            long cost = full.consumed();
            assertThrows(HStoreException.ResourceLimit.class, () -> census.exact(Budget.DEFAULT.withRows(cost - 1).start()));
            MotifCensus.Result auto = census.auto(3, Budget.DEFAULT.withRows(cost));
            assertTrue(!auto.exact() && auto.samples() >= 1, auto.toString());
            MotifCensus.Result roomy = census.auto(3, Budget.UNBOUNDED);
            assertTrue(roomy.exact());
            return null;
        });
    }

    private boolean holds(Graph graph, Region region, long[] positions) {
        Set<Long> candidates = new HashSet<>();
        for (int i = 0; i < 3; i++) {
            candidates.addAll(graph.members().get(positions[i]));
        }
        for (long v : candidates) {
            boolean inside = true;
            for (int i = 0; i < 3; i++) {
                inside &= graph.members().get(positions[i]).contains(v) == in(region.region(), i);
            }
            if (!inside || region.member().isPresent() && region.member().getAsLong() != v) {
                continue;
            }
            if (region.memberType().isPresent() && database.read(reader -> reader.typeOf(v).orElse(-1)) != region.memberType().getAsInt()) {
                continue;
            }
            boolean roles = true;
            int index = 0;
            for (int i = 0; i < 3; i++) {
                if (!in(region.region(), i)) {
                    continue;
                }
                Set<String> allowed = region.roles().isEmpty() ? Set.of() : region.roles().get(index);
                index++;
                if (!allowed.isEmpty() && graph.roles().get(List.of(positions[i], v)).stream().noneMatch(allowed::contains)) {
                    roles = false;
                }
            }
            if (roles) {
                return true;
            }
        }
        return false;
    }

    private static long[] regionSizes(Graph graph, long[] e) {
        long[] size = new long[8];
        Set<Long> all = new HashSet<>();
        for (long edge : e) {
            all.addAll(graph.members().get(edge));
        }
        for (long v : all) {
            int region = 0;
            for (int i = 0; i < 3; i++) {
                if (graph.members().get(e[i]).contains(v)) {
                    region |= 1 << i;
                }
            }
            size[region]++;
        }
        return size;
    }

    private static double weight(Weight weight, long[] s) {
        return switch (weight) {
            case COMMON -> s[7];
            case DISJOINT -> Math.min(s[3], Math.min(s[5], s[6])) / (1.0 + s[7]);
            case INDEPENDENT -> Math.min(s[1], Math.min(s[2], s[4])) / (1.0 + s[3] + s[5] + s[6] + s[7]);
        };
    }

    private Map<List<Long>, Double> expected(Graph graph, Query query, Set<Integer> scope) {
        Map<List<Long>, Double> result = new HashMap<>();
        for (long[] triple : triples(graph, scope)) {
            if (query.anchor().isPresent() && Arrays.stream(triple).noneMatch(edge -> edge == query.anchor().getAsLong())) {
                continue;
            }
            for (int[] order : ORDERS) {
                long[] positions = {triple[order[0]], triple[order[1]], triple[order[2]]};
                if (positionalCode(graph, positions) != query.code()) {
                    continue;
                }
                boolean typed = true;
                for (int i = 0; i < 3; i++) {
                    typed &= query.types().get(i).isEmpty() || query.types().get(i).getAsInt() == graph.types().get(positions[i]);
                }
                boolean nested = false;
                for (int i = 0; i < 3; i++) {
                    for (int j = 0; j < 3; j++) {
                        nested |= i != j && graph.members().get(positions[j]).contains(positions[i]);
                    }
                }
                if (typed && (!query.nested() || nested) && query.regions().stream().allMatch(region -> holds(graph, region, positions))) {
                    long[] sorted = triple.clone();
                    Arrays.sort(sorted);
                    result.put(Arrays.stream(sorted).boxed().toList(), query.rankBy().map(w -> weight(w, regionSizes(graph, positions))).orElse(0.0));
                    break;
                }
            }
        }
        return result;
    }

    @Test
    void rankedOccurrencesAreTheBestOnes() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(80L);
        database.write(writer -> {
            for (int i = 0; i < 7; i++) {
                people.add(writer.node("Person", "dense" + i));
            }
            for (int e = 0; e < 30; e++) {
                long edge = writer.edge("Meeting");
                for (int m = 0; m < 2 + random.nextInt(4); m++) {
                    writer.add(edge, people.get(random.nextInt(people.size())), MemberSpec.roles(ROLES.get(random.nextInt(3))));
                }
                edges.add(edge);
            }
            return null;
        });
        database.read(reader -> {
            Graph graph = graph(reader);
            int meeting = reader.requireReadableType("Meeting").id();
            int checked = 0;
            for (int canonical : MotifClasses.CLASSES) {
                for (Weight weight : List.of(Weight.COMMON, Weight.DISJOINT, Weight.INDEPENDENT)) {
                    for (int limit : new int[]{1, 3, 8}) {
                        Query query = new Query(canonical, List.of(OptionalInt.empty(), OptionalInt.empty(), OptionalInt.empty()), OptionalLong.empty(),
                                List.of(), false, Optional.of(weight), limit);
                        List<Double> best = expected(graph, query, Set.of(meeting)).values().stream().sorted(Comparator.reverseOrder()).limit(limit).toList();
                        assertEquals(best, MotifMatcher.find(reader, query, Budget.UNBOUNDED.start()).occurrences().stream().map(Occurrence::weight).toList(),
                                "class " + canonical + " " + weight + " top " + limit);
                        checked += best.stream().filter(value -> value > 0).count() == limit ? 1 : 0;
                    }
                }
            }
            assertTrue(checked > 20, "too few full positive rankings: " + checked);
            return null;
        });
    }

    @Test
    void occurrencesMatchTheBruteForce() {
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(79L);
        randomGraph(random, 45, 16);
        database.read(reader -> {
            Graph graph = graph(reader);
            int meeting = reader.requireReadableType("Meeting").id();
            int project = reader.requireReadableType("Project").id();
            int person = reader.requireReadableType("Person").id();
            Map<Integer, Long> seen = new TreeMap<>();
            for (int q = 0; q < 400; q++) {
                int canonical = MotifClasses.CLASSES.get(random.nextInt(MotifClasses.CLASSES.size()));
                int code = MotifClasses.permute(canonical, ORDERS[random.nextInt(6)]);
                List<OptionalInt> types = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    types.add(random.nextInt(3) == 0 ? OptionalInt.of(random.nextBoolean() ? meeting : project) : OptionalInt.empty());
                }
                OptionalLong anchor = random.nextInt(3) == 0 ? OptionalLong.of(edges.get(random.nextInt(edges.size()))) : OptionalLong.empty();
                List<Region> regions = new ArrayList<>();
                if (random.nextInt(3) == 0) {
                    List<Integer> nonEmpty = new ArrayList<>();
                    for (int r = 1; r < 8; r++) {
                        if ((code >> (r - 1) & 1) != 0) {
                            nonEmpty.add(r);
                        }
                    }
                    int region = nonEmpty.get(random.nextInt(nonEmpty.size()));
                    List<Set<String>> roles = new ArrayList<>();
                    if (random.nextBoolean()) {
                        for (int i = 0; i < Integer.bitCount(region); i++) {
                            roles.add(random.nextBoolean() ? Set.of() : new HashSet<>(List.of(ROLES.get(random.nextInt(3)), ROLES.get(random.nextInt(3)))));
                        }
                    }
                    regions.add(new Region(region, random.nextBoolean() ? OptionalInt.of(person) : OptionalInt.empty(),
                            random.nextInt(4) == 0 ? OptionalLong.of(people.get(random.nextInt(people.size()))) : OptionalLong.empty(), roles));
                }
                Optional<Weight> rank = random.nextInt(3) == 0 ? Optional.of(Weight.values()[random.nextInt(3)]) : Optional.empty();
                int limit = random.nextBoolean() ? 1_000_000 : 1 + random.nextInt(4);
                Query query = new Query(code, types, anchor, regions, random.nextInt(6) == 0, rank, limit);
                Set<Integer> scope = types.stream().allMatch(OptionalInt::isPresent)
                        ? new HashSet<>(List.of(types.get(0).getAsInt(), types.get(1).getAsInt(), types.get(2).getAsInt())) : Set.of(meeting, project);
                if (anchor.isPresent() && !scope.contains(graph.types().get(anchor.getAsLong()))) {
                    assertThrows(HStoreException.class, () -> MotifMatcher.find(reader, query, Budget.UNBOUNDED.start()));
                    continue;
                }
                Map<List<Long>, Double> expected = expected(graph, query, scope);
                MotifMatcher.Result result = MotifMatcher.find(reader, query, Budget.UNBOUNDED.start());
                assertTrue(result.complete());
                for (Occurrence occurrence : result.occurrences()) {
                    long[] positions = {occurrence.first(), occurrence.second(), occurrence.third()};
                    assertEquals(query.code(), positionalCode(graph, positions), "query " + q);
                    long[] sorted = positions.clone();
                    Arrays.sort(sorted);
                    List<Long> key = Arrays.stream(sorted).boxed().toList();
                    assertTrue(expected.containsKey(key), "query " + q + " returned " + key);
                    assertEquals(expected.get(key), occurrence.weight(), 1e-12);
                }
                assertEquals(result.occurrences().stream().map(o -> {
                    long[] s = {o.first(), o.second(), o.third()};
                    Arrays.sort(s);
                    return Arrays.stream(s).boxed().toList();
                }).distinct().count(), result.occurrences().size(), "duplicates in query " + q);
                assertEquals(Math.min(limit, expected.size()), result.occurrences().size(), "query " + q);
                if (rank.isPresent()) {
                    List<Double> best = expected.values().stream().sorted(Comparator.reverseOrder()).limit(limit).toList();
                    assertEquals(best, result.occurrences().stream().map(Occurrence::weight).toList(), "query " + q);
                }
                seen.merge(expected.isEmpty() ? 0 : 1, 1L, Long::sum);
            }
            assertTrue(seen.getOrDefault(1, 0L) > 100, "too few queries with results: " + seen);
            return null;
        });
    }
}

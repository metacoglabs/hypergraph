// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.temporal;

import io.hstore.db.DatabaseOptions;
import io.hstore.db.HypergraphDatabase;
import io.hstore.db.MemberSpec;
import io.hstore.db.Reader;
import io.hstore.db.hora.Budget;
import io.hstore.db.query.Session;
import io.hstore.db.temporal.FeedFold.Change;
import io.hstore.db.temporal.Transitions.Direction;
import io.hstore.db.temporal.Transitions.Route;
import io.hstore.engine.EngineOptions;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.txn.TxnOptions;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeSet;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TransitionsTest {

    @TempDir
    Path directory;

    HypergraphDatabase database;

    private void open(EngineOptions engine) {
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withEngine(engine));
        try (Session admin = new Session(database)) {
            admin.executeAll("""
                    CREATE NODE TYPE Person;
                    CREATE SET EDGE TYPE Collaboration;
                    CREATE SET EDGE TYPE Meeting;
                    """);
        }
    }

    @AfterEach
    void close() {
        if (database != null) {
            database.close();
        }
    }

    private record State(Set<Change> memberships, Set<Change> edges) {
    }

    private static State state(Reader reader, int type) {
        Set<Change> memberships = new TreeSet<>(FeedFold.ORDER);
        Set<Change> edges = new TreeSet<>(FeedFold.ORDER);
        reader.atomsOfType(type).forEach(edge -> {
            edges.add(new Change.Edge(edge));
            reader.members(edge).forEach(member -> memberships.add(new Change.Membership(edge, member.atom())));
        });
        return new State(memberships, edges);
    }

    private State stateAt(long generation, int branch, int type) {
        Branch found = database.engine().branches().stream().filter(candidate -> candidate.id() == branch).findFirst().orElseThrow();
        int at = branch != Branch.MAIN && generation <= found.baseGeneration() ? found.parent() : branch;
        return database.readAt(generation, at, reader -> state(reader, type));
    }

    private static List<Change> difference(Set<Change> from, Set<Change> to) {
        return from.stream().filter(change -> !to.contains(change)).toList();
    }

    private record History(List<Long> generations, int branch, int type) {
    }

    private History randomHistory(RandomGenerator random, int commits) {
        List<Long> people = new ArrayList<>();
        database.write(writer -> {
            for (int i = 0; i < 25; i++) {
                people.add(writer.node("Person", "p" + i));
            }
            return null;
        });
        Map<Integer, List<Long>> edges = new HashMap<>();
        Map<Long, Set<Long>> members = new HashMap<>();
        List<Long> generations = new ArrayList<>();
        int branch = -1;
        for (int c = 0; c < commits; c++) {
            if (c == commits / 3) {
                branch = database.engine().createBranch("side", Branch.MAIN).id();
                edges.put(branch, new ArrayList<>(edges.getOrDefault(Branch.MAIN, List.of())));
            }
            int on = branch >= 0 && random.nextBoolean() ? branch : Branch.MAIN;
            List<Long> mine = edges.computeIfAbsent(on, _ -> new ArrayList<>());
            database.write(TxnOptions.defaults().onBranch(on), writer -> {
                int choice = mine.isEmpty() ? 0 : random.nextInt(10);
                if (choice < 2) {
                    long edge = writer.edge(random.nextInt(3) == 0 ? "Meeting" : "Collaboration");
                    mine.add(edge);
                    members.put(on * 1_000_000_000L + edge, new LinkedHashSet<>());
                } else if (choice < 6) {
                    long edge = mine.get(random.nextInt(mine.size()));
                    long person = people.get(random.nextInt(people.size()));
                    writer.add(edge, person, MemberSpec.PLAIN.withWeight(random.nextInt(4) / 4.0));
                    members.computeIfAbsent(on * 1_000_000_000L + edge, _ -> new LinkedHashSet<>()).add(person);
                } else if (choice < 9) {
                    long edge = mine.get(random.nextInt(mine.size()));
                    List<Long> current = List.copyOf(members.computeIfAbsent(on * 1_000_000_000L + edge, _ -> new LinkedHashSet<>()));
                    if (!current.isEmpty()) {
                        long person = current.get(random.nextInt(current.size()));
                        writer.remove(edge, person);
                        members.get(on * 1_000_000_000L + edge).remove(person);
                    }
                } else {
                    writer.delete(mine.remove(random.nextInt(mine.size())));
                }
                return null;
            });
            generations.add(database.engine().transactions().current().id());
        }
        int type = database.read(reader -> reader.requireReadableType("Collaboration").id());
        return new History(generations, branch, type);
    }

    private void compare(History history, RandomGenerator random, Route route, int windows) {
        for (int q = 0; q < windows; q++) {
            int branch = random.nextBoolean() ? history.branch() : Branch.MAIN;
            long g1 = history.generations().get(random.nextInt(history.generations().size() - 1));
            long g2 = g1 + 1 + random.nextInt(60);
            long last = history.generations().getLast();
            long end = Math.min(g2, last);
            State before = stateAt(g1, branch, history.type());
            State after = stateAt(end, branch, history.type());
            database.read(branch, reader -> {
                String where = "branch " + branch + " (" + g1 + ", " + end + "] " + route;
                assertEquals(difference(before.memberships(), after.memberships()),
                        Transitions.of(reader, OptionalInt.of(history.type()), g1, end, Direction.DISAPPEARED, true, Budget.DEFAULT.start(), route), where);
                assertEquals(difference(after.memberships(), before.memberships()),
                        Transitions.of(reader, OptionalInt.of(history.type()), g1, end, Direction.APPEARED, true, Budget.DEFAULT.start(), route), where);
                assertEquals(difference(before.edges(), after.edges()),
                        Transitions.of(reader, OptionalInt.of(history.type()), g1, end, Direction.DISAPPEARED, false, Budget.DEFAULT.start(), route), where);
                assertEquals(difference(after.edges(), before.edges()),
                        Transitions.of(reader, OptionalInt.of(history.type()), g1, end, Direction.APPEARED, false, Budget.DEFAULT.start(), route), where);
                return null;
            });
        }
    }

    @Test
    void feedFoldAgreesWithSnapshotsOnRandomHistories() {
        open(EngineOptions.defaults().withPageSize(4096).withHistoryLimit(5000));
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(20261123L);
        History history = randomHistory(random, 600);
        compare(history, random, Route.FEED, 150);
    }

    @Test
    void diffPathAgreesWithSnapshotsOnRandomHistories() {
        open(EngineOptions.defaults().withPageSize(4096).withHistoryLimit(5000));
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(20261124L);
        History history = randomHistory(random, 400);
        compare(history, random, Route.DIFF, 100);
    }

    @Test
    void traceFromTheTemporalPage() {
        open(EngineOptions.defaults().withPageSize(4096).withHistoryLimit(5000));
        try (Session session = new Session(database)) {
            session.executeAll("""
                    INSERT NODE Person 'a' AS $a;
                    INSERT NODE Person 'b' AS $b;
                    INSERT NODE Person 'c' AS $c;
                    INSERT NODE Person 'd' AS $d;
                    INSERT NODE Person 'f' AS $f;
                    INSERT EDGE Collaboration AS $e30;
                    INSERT EDGE Collaboration MEMBERS ($b) AS $e31;
                    INSERT EDGE Collaboration MEMBERS ($c) AS $e32;
                    INSERT EDGE Collaboration MEMBERS ($d, $f) AS $e33;
                    """);
            long g100 = database.engine().transactions().current().id();
            session.executeAll("BEGIN; ADD $a TO $e30; ADD $c TO $e32 WEIGHT 0.5; COMMIT;");
            session.execute("REMOVE $b FROM $e31");
            session.executeAll("BEGIN; ADD $b TO $e31; REMOVE $c FROM $e32; COMMIT;");
            session.executeAll("BEGIN; REMOVE $a FROM $e30; DELETE $e33; COMMIT;");
            long g104 = database.engine().transactions().current().id();
            Map<String, Long> v = session.variables();
            database.read(reader -> {
                int type = reader.requireReadableType("Collaboration").id();
                for (Route route : List.of(Route.FEED, Route.DIFF)) {
                    assertEquals(List.of(new Change.Membership(v.get("e32"), v.get("c")), new Change.Membership(v.get("e33"), v.get("d")),
                                    new Change.Membership(v.get("e33"), v.get("f"))),
                            Transitions.of(reader, OptionalInt.of(type), g100, g104, Direction.DISAPPEARED, true, Budget.DEFAULT.start(), route), route.name());
                    assertEquals(List.of(new Change.Edge(v.get("e33"))),
                            Transitions.of(reader, OptionalInt.of(type), g100, g104, Direction.DISAPPEARED, false, Budget.DEFAULT.start(), route), route.name());
                }
                return null;
            });
        }
    }

    @Test
    void windowBeforeTheFeedIsAClearError() {
        open(EngineOptions.defaults().withPageSize(4096).withHistoryLimit(2).withWalSegmentBytes(4096).withFeedRetention(5));
        RandomGenerator random = RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(7L);
        randomHistory(random, 300);
        database.engine().checkpoint();
        long first = database.engine().feed().firstGeneration();
        assertTrue(first > 10, "feed still starts at " + first);
        database.read(reader -> {
            HStoreException refused = assertThrows(HStoreException.class, () -> Transitions.of(reader, OptionalInt.empty(), 3, first + 1,
                    Direction.DISAPPEARED, false, Budget.DEFAULT.start(), Route.AUTO));
            assertTrue(refused.getMessage().contains("precedes the change feed"), refused.getMessage());
            return null;
        });
    }

    @Test
    void overBudgetFailsWholeNotPartial() {
        open(EngineOptions.defaults().withPageSize(4096).withHistoryLimit(5000));
        randomHistory(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(9L), 100);
        database.read(reader -> {
            assertThrows(HStoreException.class, () -> Transitions.of(reader, OptionalInt.empty(), 1, reader.generation(),
                    Direction.DISAPPEARED, true, Budget.DEFAULT.withRows(5).start(), Route.FEED));
            return null;
        });
    }

    @Test
    void feedPathWaitsForTheReadersNewestCommitToReachTheFeed() throws Exception {
        java.util.concurrent.CountDownLatch reached = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean armed = new java.util.concurrent.atomic.AtomicBoolean();
        open(EngineOptions.defaults().withPageSize(4096).withHistoryLimit(5000).withFaults(point -> {
            if (point == io.hstore.engine.txn.CrashPoint.FEED_APPEND && armed.compareAndSet(true, false)) {
                reached.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
        }));
        long[] ids = database.write(writer -> new long[]{writer.edge("Collaboration"), writer.node("Person", "seed")});
        int type = database.read(reader -> reader.requireReadableType("Collaboration").id());
        armed.set(true);
        var pool = java.util.concurrent.Executors.newCachedThreadPool();
        try {
            var commit = pool.submit(() -> database.write(writer -> {
                writer.add(ids[0], ids[1], MemberSpec.PLAIN);
                return null;
            }));
            reached.await();
            var query = pool.submit(() -> database.read(reader -> Transitions.of(reader, OptionalInt.of(type), reader.generation() - 1,
                    reader.generation(), Direction.APPEARED, true, Budget.DEFAULT.start(), Route.FEED)));
            assertThrows(java.util.concurrent.TimeoutException.class, () -> query.get(300, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            commit.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(List.of(new Change.Membership(ids[0], ids[1])), query.get(10, java.util.concurrent.TimeUnit.SECONDS));
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    @Test
    void timesInsideAHistoryHoleResolveThroughTheFeed() throws Exception {
        open(EngineOptions.defaults().withPageSize(4096).withHistoryLimit(2));
        database.write(writer -> writer.node("Person", "start"));
        Map<Long, Long> wallTimes = new HashMap<>();
        try (var _ = database.engine().snapshot()) {
            for (int i = 0; i < 8; i++) {
                Thread.sleep(3);
                int n = i;
                database.write(writer -> writer.node("Person", "h" + n));
                var current = database.engine().transactions().current();
                wallTimes.put(current.id(), current.wallTime());
            }
            long middle = wallTimes.keySet().stream().sorted().toList().get(3);
            database.read(reader -> {
                assertEquals(middle, Transitions.generationAt(reader, wallTimes.get(middle)).orElseThrow());
                return null;
            });
        }
    }

    @Test
    void twoTimesWithoutACommitBetweenGiveNoRows() {
        open(EngineOptions.defaults().withPageSize(4096).withHistoryLimit(5000));
        try (Session session = new Session(database)) {
            session.execute("INSERT NODE Person 'a'");
            long now = System.currentTimeMillis() + 10;
            String from = java.time.Instant.ofEpochMilli(now).toString();
            String to = java.time.Instant.ofEpochMilli(now + 5).toString();
            assertEquals(0, session.execute("TRANSITIONS EDGE x FROM '" + from + "' TO '" + to + "' APPEARED").rows().size());
        }
    }

    @Test
    void transitionsCountPagesAgainstTheQueryBudget() {
        open(EngineOptions.defaults().withPageSize(4096).withHistoryLimit(5000));
        History history = randomHistory(RandomGeneratorFactory.<RandomGenerator>of("L64X128MixRandom").create(13L), 200);
        database.close();
        database = HypergraphDatabase.open(directory, DatabaseOptions.defaults().withQueryPages(1)
                .withEngine(EngineOptions.defaults().withPageSize(4096).withHistoryLimit(5000)));
        try (Session session = new Session(database)) {
            HStoreException refused = assertThrows(HStoreException.class, () -> session.execute("TRANSITIONS EDGE x FROM GENERATION "
                    + history.generations().getFirst() + " TO GENERATION " + history.generations().getLast() + " APPEARED MEMBERS"));
            assertTrue(refused.getMessage().contains("page visits"), refused.getMessage());
        }
    }

    @Test
    void lineageThroughAMergedParentKeepsItsEvents() {
        open(EngineOptions.defaults().withPageSize(4096).withHistoryLimit(5000));
        try (Session session = new Session(database)) {
            session.executeAll("""
                    INSERT NODE Person 'a' AS $a;
                    INSERT EDGE Collaboration AS $e;
                    CREATE BRANCH parent;
                    USE BRANCH parent;
                    """);
            long before = database.engine().transactions().current().id();
            session.executeAll("""
                    ADD $a TO $e;
                    CREATE BRANCH child FROM parent;
                    USE MAIN;
                    MERGE BRANCH parent;
                    """);
            int child = database.engine().branches().stream().filter(found -> found.name().equals("child")).findFirst().orElseThrow().id();
            long now = database.engine().transactions().current().id();
            database.read(child, reader -> {
                int type = reader.requireReadableType("Collaboration").id();
                assertEquals(List.of(new Change.Membership(session.variables().get("e"), session.variables().get("a"))),
                        Transitions.of(reader, OptionalInt.of(type), before, now, Direction.APPEARED, true, Budget.DEFAULT.start(), Route.FEED));
                return null;
            });
        }
    }

    @Test
    void statementReturnsEdgesAndMembers() {
        open(EngineOptions.defaults().withPageSize(4096).withHistoryLimit(5000));
        try (Session session = new Session(database)) {
            session.executeAll("""
                    INSERT NODE Person 'a' AS $a;
                    INSERT EDGE Collaboration MEMBERS ($a) AS $e;
                    """);
            long g1 = database.engine().transactions().current().id();
            session.execute("DELETE $e");
            long g2 = database.engine().transactions().current().id();
            var edges = session.execute("TRANSITIONS EDGE x:Collaboration FROM GENERATION " + g1 + " TO GENERATION " + g2 + " DISAPPEARED");
            assertEquals(List.of("edge"), edges.columns());
            assertEquals(1, edges.rows().size());
            var memberships = session.execute("TRANSITIONS EDGE x FROM GENERATION " + g1 + " TO GENERATION " + g2 + " DISAPPEARED MEMBERS LIMIT 5");
            assertEquals(List.of("edge", "member"), memberships.columns());
            assertEquals(1, memberships.rows().size());
            assertTrue(memberships.rows().getFirst().get(1).render().contains("'a'"), memberships.rows().getFirst().get(1).render());
            assertEquals(0, session.execute("TRANSITIONS EDGE x FROM GENERATION " + g1 + " TO GENERATION " + g2 + " APPEARED").rows().size());
        }
    }
}

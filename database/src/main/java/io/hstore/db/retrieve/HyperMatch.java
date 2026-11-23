// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.retrieve;

import io.hstore.db.Reader;
import io.hstore.db.hora.Budget;
import io.hstore.engine.HStoreException;
import io.hstore.engine.page.IoTrace;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.function.Supplier;
import java.util.stream.Collectors;

public final class HyperMatch {

    public enum Mode { AUTO, HGMATCH, MACH }

    static final long CHS_CANDIDATE_LIMIT = 1L << 20;
    static final int BATCH = 64;
    private static final Comparator<long[]> TUPLE_ORDER = Arrays::compare;

    private final Reader reader;
    private final Mode mode;
    private final int threads;
    private final Map<Long, long[]> incident = new ConcurrentHashMap<>();
    private final Map<Long, long[]> members = new ConcurrentHashMap<>();
    private final Map<Long, Integer> labels = new ConcurrentHashMap<>();

    private HyperMatch(Reader reader, Mode mode, int threads) {
        this.reader = reader;
        this.mode = mode;
        this.threads = threads;
    }

    public static HyperMatch on(Reader reader) {
        return new HyperMatch(reader, Mode.AUTO, 1);
    }

    public HyperMatch withMode(Mode next) {
        return new HyperMatch(reader, next, threads);
    }

    public HyperMatch withThreads(int next) {
        if (next < 1) {
            throw HStoreException.invalid("match threads must be at least 1");
        }
        return new HyperMatch(reader, mode, next);
    }

    record Step(int queryEdge, long anchor, Signature signature, int[] vertices, int[] classLabels, long[] classMasks, int[] classCounts) {
        boolean isAnchor() {
            return queryEdge < 0;
        }
    }

    record Plan(HyperQuery query, Step[] steps, Mode mode, long[][] candidateSpace) {
    }

    private record Embedding(long[] stepEdges, long[] vertices, int[] vertexLabels, long[] masks) {
        static final Embedding EMPTY = new Embedding(new long[0], new long[0], new int[0], new long[0]);

        int find(long vertex) {
            return Arrays.binarySearch(vertices, vertex);
        }

        boolean uses(long edge) {
            for (long used : stepEdges) {
                if (used == edge) {
                    return true;
                }
            }
            return false;
        }
    }

    public long count(HyperQuery query, Budget.Meter meter) {
        Plan plan = plan(query);
        return run(plan, meter, false).count;
    }

    public List<long[]> tuples(HyperQuery query, Budget.Meter meter, long limit) {
        Plan plan = plan(query);
        List<long[]> found = run(plan, meter, true).tuples;
        found.sort(TUPLE_ORDER);
        return found.size() > limit ? List.copyOf(found.subList(0, (int) Math.min(Integer.MAX_VALUE, limit))) : List.copyOf(found);
    }

    public String describe(HyperQuery query) {
        Plan plan = plan(query);
        return "HyperMatch(" + plan.mode().name().toLowerCase(Locale.ROOT) + ")" + Arrays.stream(plan.steps())
                .map(step -> step.isAnchor()
                        ? "\n  -> Anchor(" + query.vertices().get(step.vertices()[0]).name() + " = @" + step.anchor() + ")"
                        : "\n  -> " + (step.classLabels().length == 0 ? "Scan(" : "Expand(") + query.edges().get(step.queryEdge()).name()
                        + " rows≈" + reader.signatureCount(step.signature()) + ")")
                .collect(Collectors.joining());
    }

    Plan plan(HyperQuery query) {
        int edgeCount = query.edges().size();
        Signature[] signatures = new Signature[edgeCount];
        long[] cards = new long[edgeCount];
        for (int e = 0; e < edgeCount; e++) {
            signatures[e] = signature(query, query.edges().get(e));
            cards[e] = reader.signatureCount(signatures[e]);
        }
        List<int[]> order = new ArrayList<>();
        BitSet bound = new BitSet(query.vertices().size());
        query.anchors().keySet().stream().sorted().forEach(vertex -> {
            order.add(new int[]{-1, vertex});
            bound.set(vertex);
        });
        BitSet matched = new BitSet(edgeCount);
        while (matched.cardinality() < edgeCount) {
            int best = -1;
            double bestScore = Double.MAX_VALUE;
            for (int e = 0; e < edgeCount; e++) {
                if (matched.get(e)) {
                    continue;
                }
                int[] vertices = query.edges().get(e).vertices();
                long shared = Arrays.stream(vertices).filter(bound::get).count();
                if (!bound.isEmpty() && shared == 0) {
                    continue;
                }
                double score = bound.isEmpty() ? cards[e] : (double) cards[e] / shared;
                if (best < 0 || score < bestScore
                        || (score == bestScore && vertices.length < query.edges().get(best).vertices().length)) {
                    best = e;
                    bestScore = score;
                }
            }
            matched.set(best);
            order.add(new int[]{best});
            Arrays.stream(query.edges().get(best).vertices()).forEach(bound::set);
        }
        Step[] steps = new Step[order.size()];
        for (int k = 0; k < steps.length; k++) {
            int[] entry = order.get(k);
            boolean anchor = entry[0] < 0;
            int[] vertices = anchor ? new int[]{entry[1]} : query.edges().get(entry[0]).vertices();
            int[] classLabels = new int[vertices.length];
            long[] classMasks = new long[vertices.length];
            int[] classCounts = new int[vertices.length];
            int i = 0;
            for (int vertex : vertices) {
                long prior = priorMask(order, query, vertex, k);
                if (prior == 0) {
                    continue;
                }
                int label = query.vertices().get(vertex).label();
                int at = -1;
                for (int c = 0; c < i; c++) {
                    if (classLabels[c] == label && classMasks[c] == prior) {
                        at = c;
                    }
                }
                if (at < 0) {
                    at = i++;
                    classLabels[at] = label;
                    classMasks[at] = prior;
                }
                classCounts[at]++;
            }
            steps[k] = new Step(anchor ? -1 : entry[0], anchor ? query.anchors().get(entry[1]) : 0,
                    anchor ? null : signatures[entry[0]], vertices, Arrays.copyOf(classLabels, i), Arrays.copyOf(classMasks, i),
                    Arrays.copyOf(classCounts, i));
        }
        Mode chosen = mode;
        if (chosen == Mode.AUTO) {
            long total = Arrays.stream(cards).sum();
            chosen = !query.anchors().isEmpty() || edgeCount <= 2 || total > CHS_CANDIDATE_LIMIT ? Mode.HGMATCH : Mode.MACH;
        }
        long[][] space = chosen == Mode.MACH ? candidateSpace(query, signatures) : null;
        return new Plan(query, steps, chosen, space);
    }

    private static long priorMask(List<int[]> order, HyperQuery query, int vertex, int before) {
        long mask = 0;
        for (int k = 0; k < before; k++) {
            int[] entry = order.get(k);
            boolean contains = entry[0] < 0 ? entry[1] == vertex : Arrays.stream(query.edges().get(entry[0]).vertices()).anyMatch(v -> v == vertex);
            if (contains) {
                mask |= 1L << k;
            }
        }
        return mask;
    }

    private static Signature signature(HyperQuery query, HyperQuery.Edge edge) {
        Signature signature = Signature.empty(edge.edgeType());
        for (int vertex : edge.vertices()) {
            signature = signature.withAdded(query.vertices().get(vertex).label());
        }
        return signature;
    }

    private long[][] candidateSpace(HyperQuery query, Signature[] signatures) {
        int edgeCount = query.edges().size();
        long[][] space = new long[edgeCount][];
        for (int e = 0; e < edgeCount; e++) {
            space[e] = reader.signatureEdges(signatures[e]);
        }
        Map<Long, Map<Long, Long>> witnesses = new HashMap<>();
        boolean changed = true;
        while (changed) {
            changed = false;
            for (int e = 0; e < edgeCount; e++) {
                for (int other = 0; other < edgeCount; other++) {
                    if (other == e) {
                        continue;
                    }
                    int[] shared = shared(query.edges().get(e).vertices(), query.edges().get(other).vertices());
                    if (shared.length == 0) {
                        continue;
                    }
                    int[] sharedLabels = Arrays.stream(shared).map(v -> query.vertices().get(v).label()).sorted().toArray();
                    long[] partners = space[other];
                    Map<Long, Long> witness = witnesses.computeIfAbsent(((long) e << 32) | other, _ -> new HashMap<>());
                    long[] kept = Arrays.stream(space[e]).filter(f -> {
                        Long known = witness.get(f);
                        if (known != null && Arrays.binarySearch(partners, known) >= 0) {
                            return true;
                        }
                        long found = supporter(f, sharedLabels, partners);
                        if (found < 0) {
                            return false;
                        }
                        witness.put(f, found);
                        return true;
                    }).toArray();
                    if (kept.length != space[e].length) {
                        space[e] = kept;
                        changed = true;
                    }
                }
            }
        }
        return space;
    }

    private static int[] shared(int[] left, int[] right) {
        return Arrays.stream(left).filter(v -> Arrays.stream(right).anyMatch(w -> w == v)).toArray();
    }

    private long supporter(long f, int[] sharedLabels, long[] partners) {
        long[] fMembers = membersOf(f);
        Set<Long> seen = new HashSet<>();
        for (long vertex : fMembers) {
            if (Arrays.binarySearch(sharedLabels, label(vertex)) < 0) {
                continue;
            }
            for (long candidate : incidentOf(vertex)) {
                if (candidate != f && Arrays.binarySearch(partners, candidate) >= 0 && seen.add(candidate)
                        && Arrays.equals(intersectionLabels(fMembers, membersOf(candidate)), sharedLabels)) {
                    return candidate;
                }
            }
        }
        return -1;
    }

    private int[] intersectionLabels(long[] left, long[] right) {
        return Arrays.stream(left).filter(v -> Arrays.binarySearch(right, v) >= 0).mapToInt(this::label).sorted().toArray();
    }

    private static final class Result {
        long count;
        final List<long[]> tuples = new ArrayList<>();

        void merge(Result other) {
            count += other.count;
            tuples.addAll(other.tuples);
        }
    }

    private Result run(Plan plan, Budget.Meter meter, boolean collect) {
        List<Embedding> roots = new ArrayList<>();
        for (long candidate : candidates(plan, 0, Embedding.EMPTY)) {
            meter.consume(1);
            extend(plan, 0, Embedding.EMPTY, candidate).ifPresent(roots::add);
        }
        if (plan.steps().length == 1) {
            Result result = new Result();
            roots.forEach(root -> sink(plan, root, result, collect));
            return result;
        }
        if (threads == 1 || !reader.concurrentReads() || roots.size() <= BATCH) {
            Result result = new Result();
            for (Embedding root : roots) {
                search(plan, 1, root, meter, result, collect);
            }
            return result;
        }
        Optional<IoTrace> trace = IoTrace.current();
        try (ForkJoinPool pool = new ForkJoinPool(threads)) {
            return pool.invoke(new SearchTask(plan, roots, 0, roots.size(), meter, collect, trace));
        }
    }

    private final class SearchTask extends RecursiveTask<Result> {
        private final Plan plan;
        private final List<Embedding> roots;
        private final int low;
        private final int high;
        private final Budget.Meter meter;
        private final boolean collect;
        private final Optional<IoTrace> trace;

        SearchTask(Plan plan, List<Embedding> roots, int low, int high, Budget.Meter meter, boolean collect, Optional<IoTrace> trace) {
            this.plan = plan;
            this.roots = roots;
            this.low = low;
            this.high = high;
            this.meter = meter;
            this.collect = collect;
            this.trace = trace;
        }

        @Override
        protected Result compute() {
            if (high - low > BATCH) {
                int middle = (low + high) >>> 1;
                SearchTask left = new SearchTask(plan, roots, low, middle, meter, collect, trace);
                SearchTask right = new SearchTask(plan, roots, middle, high, meter, collect, trace);
                left.fork();
                Result result = right.compute();
                result.merge(left.join());
                return result;
            }
            Supplier<Result> work = () -> {
                Result result = new Result();
                for (int i = low; i < high; i++) {
                    search(plan, 1, roots.get(i), meter, result, collect);
                }
                return result;
            };
            return trace.map(found -> found.call(work)).orElseGet(work);
        }
    }

    private void search(Plan plan, int k, Embedding embedding, Budget.Meter meter, Result result, boolean collect) {
        if (k == plan.steps().length) {
            sink(plan, embedding, result, collect);
            return;
        }
        for (long candidate : candidates(plan, k, embedding)) {
            meter.consume(1);
            Optional<Embedding> next = extend(plan, k, embedding, candidate);
            if (next.isPresent()) {
                search(plan, k + 1, next.get(), meter, result, collect);
            }
        }
    }

    private void sink(Plan plan, Embedding embedding, Result result, boolean collect) {
        result.count++;
        if (collect) {
            long[] tuple = new long[plan.query().edges().size()];
            for (int k = 0; k < plan.steps().length; k++) {
                if (!plan.steps()[k].isAnchor()) {
                    tuple[plan.steps()[k].queryEdge()] = embedding.stepEdges()[k];
                }
            }
            result.tuples.add(tuple);
        }
    }

    private long[] candidates(Plan plan, int k, Embedding embedding) {
        Step step = plan.steps()[k];
        if (step.isAnchor()) {
            return new long[]{step.anchor()};
        }
        long[] base = plan.candidateSpace() != null ? plan.candidateSpace()[step.queryEdge()] : signatureEdges(step);
        List<long[]> sets = new ArrayList<>();
        sets.add(base);
        for (int c = 0; c < step.classLabels().length; c++) {
            List<long[]> union = new ArrayList<>();
            for (int i = 0; i < embedding.vertices().length; i++) {
                if (embedding.vertexLabels()[i] == step.classLabels()[c] && embedding.masks()[i] == step.classMasks()[c]) {
                    union.add(incidentOf(embedding.vertices()[i]));
                }
            }
            if (union.isEmpty()) {
                return new long[0];
            }
            sets.add(union(union));
        }
        return intersect(sets);
    }

    private final Map<Signature, long[]> signatureEdges = new ConcurrentHashMap<>();

    private long[] signatureEdges(Step step) {
        return signatureEdges.computeIfAbsent(step.signature(), reader::signatureEdges);
    }

    private Optional<Embedding> extend(Plan plan, int k, Embedding embedding, long candidate) {
        Step step = plan.steps()[k];
        long bit = 1L << k;
        long[] memberIds;
        if (step.isAnchor()) {
            if (label(candidate) != plan.query().vertices().get(step.vertices()[0]).label() || embedding.find(candidate) >= 0) {
                return Optional.empty();
            }
            memberIds = new long[]{candidate};
        } else {
            if (embedding.uses(candidate)) {
                return Optional.empty();
            }
            memberIds = membersOf(candidate);
            if (memberIds.length != step.vertices().length) {
                return Optional.empty();
            }
            int[] remaining = step.classCounts().clone();
            for (long member : memberIds) {
                int at = embedding.find(member);
                if (at >= 0) {
                    int matchedClass = -1;
                    for (int c = 0; c < remaining.length; c++) {
                        if (step.classLabels()[c] == embedding.vertexLabels()[at] && step.classMasks()[c] == embedding.masks()[at]) {
                            matchedClass = c;
                        }
                    }
                    if (matchedClass < 0 || remaining[matchedClass]-- == 0) {
                        return Optional.empty();
                    }
                } else if (label(member) < 0) {
                    return Optional.empty();
                }
            }
            for (int count : remaining) {
                if (count != 0) {
                    return Optional.empty();
                }
            }
        }
        int added = 0;
        for (long member : memberIds) {
            if (embedding.find(member) < 0) {
                added++;
            }
        }
        int size = embedding.vertices().length + added;
        long[] vertices = new long[size];
        int[] vertexLabels = new int[size];
        long[] masks = new long[size];
        int i = 0;
        int j = 0;
        int o = 0;
        long[] sortedMembers = memberIds.clone();
        Arrays.sort(sortedMembers);
        while (i < embedding.vertices().length || j < sortedMembers.length) {
            if (j >= sortedMembers.length || (i < embedding.vertices().length && embedding.vertices()[i] < sortedMembers[j])) {
                vertices[o] = embedding.vertices()[i];
                vertexLabels[o] = embedding.vertexLabels()[i];
                masks[o++] = embedding.masks()[i++];
            } else if (i >= embedding.vertices().length || sortedMembers[j] < embedding.vertices()[i]) {
                vertices[o] = sortedMembers[j];
                vertexLabels[o] = label(sortedMembers[j]);
                masks[o++] = bit;
                j++;
            } else {
                vertices[o] = embedding.vertices()[i];
                vertexLabels[o] = embedding.vertexLabels()[i];
                masks[o++] = embedding.masks()[i++] | bit;
                j++;
            }
        }
        long[] stepEdges = Arrays.copyOf(embedding.stepEdges(), k + 1);
        stepEdges[k] = step.isAnchor() ? -1 : candidate;
        return Optional.of(new Embedding(stepEdges, vertices, vertexLabels, masks));
    }

    public List<long[]> bindings(HyperQuery query, long[] tuple) {
        int edgeCount = query.edges().size();
        List<Integer> anchorVertices = query.anchors().keySet().stream().sorted().toList();
        Map<Long, Long> dataMasks = new TreeMap<>();
        for (int e = 0; e < edgeCount; e++) {
            for (long member : membersOf(tuple[e])) {
                dataMasks.merge(member, 1L << e, (a, b) -> a | b);
            }
        }
        for (int a = 0; a < anchorVertices.size(); a++) {
            dataMasks.merge(query.anchors().get(anchorVertices.get(a)), 1L << (edgeCount + a), (x, y) -> x | y);
        }
        int n = query.vertices().size();
        long[] queryMasks = new long[n];
        for (int e = 0; e < edgeCount; e++) {
            for (int vertex : query.edges().get(e).vertices()) {
                queryMasks[vertex] |= 1L << e;
            }
        }
        for (int a = 0; a < anchorVertices.size(); a++) {
            queryMasks[anchorVertices.get(a)] |= 1L << (edgeCount + a);
        }
        List<List<Integer>> classes = new ArrayList<>();
        List<List<Long>> images = new ArrayList<>();
        for (int u = 0; u < n; u++) {
            int label = query.vertices().get(u).label();
            long mask = queryMasks[u];
            int found = -1;
            for (int c = 0; c < classes.size(); c++) {
                int representative = classes.get(c).getFirst();
                if (query.vertices().get(representative).label() == label && queryMasks[representative] == mask) {
                    found = c;
                }
            }
            if (found < 0) {
                classes.add(new ArrayList<>());
                images.add(dataMasks.entrySet().stream()
                        .filter(entry -> entry.getValue() == mask && label(entry.getKey()) == label)
                        .map(Map.Entry::getKey).toList());
                found = classes.size() - 1;
            }
            classes.get(found).add(u);
        }
        List<long[]> bindings = new ArrayList<>();
        bindings.add(new long[n]);
        for (int c = 0; c < classes.size(); c++) {
            List<long[]> next = new ArrayList<>();
            List<Integer> members = classes.get(c);
            List<long[]> permutations = permutations(images.get(c).stream().mapToLong(Long::longValue).toArray());
            for (long[] partial : bindings) {
                for (long[] permutation : permutations) {
                    long[] extended = partial.clone();
                    for (int i = 0; i < members.size(); i++) {
                        extended[members.get(i)] = permutation[i];
                    }
                    next.add(extended);
                }
            }
            bindings = next;
        }
        return List.copyOf(bindings);
    }

    private static List<long[]> permutations(long[] values) {
        List<long[]> out = new ArrayList<>();
        permute(values.clone(), 0, out);
        return out;
    }

    private static void permute(long[] values, int i, List<long[]> out) {
        if (i == values.length) {
            out.add(values.clone());
            return;
        }
        for (int j = i; j < values.length; j++) {
            long swap = values[i];
            values[i] = values[j];
            values[j] = swap;
            permute(values, i + 1, out);
            values[j] = values[i];
            values[i] = swap;
        }
    }

    private int label(long atom) {
        return labels.computeIfAbsent(atom, id -> reader.typeOf(id).orElse(-1));
    }

    private long[] membersOf(long edge) {
        return members.computeIfAbsent(edge, id -> {
            List<Long> found = new ArrayList<>();
            reader.forEachMemberId(id, found::add);
            return found.stream().mapToLong(Long::longValue).sorted().toArray();
        });
    }

    private long[] incidentOf(long atom) {
        return incident.computeIfAbsent(atom, id -> reader.incidentEdges(id).mapToLong(found -> found.edge()).sorted().toArray());
    }

    private static long[] union(List<long[]> sets) {
        if (sets.size() == 1) {
            return sets.getFirst();
        }
        return sets.stream().flatMapToLong(Arrays::stream).sorted().distinct().toArray();
    }

    private static long[] intersect(List<long[]> sets) {
        List<long[]> ordered = new ArrayList<>(sets);
        ordered.sort(Comparator.comparingInt(set -> set.length));
        long[] result = ordered.getFirst();
        for (int i = 1; i < ordered.size() && result.length > 0; i++) {
            long[] other = ordered.get(i);
            result = Arrays.stream(result).filter(value -> Arrays.binarySearch(other, value) >= 0).toArray();
        }
        return result;
    }
}

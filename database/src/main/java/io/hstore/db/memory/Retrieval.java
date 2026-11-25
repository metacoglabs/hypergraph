// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.db.Member;
import io.hstore.db.Reader;
import io.hstore.db.evidence.EvidencePolicy;
import io.hstore.db.evidence.Provenance;
import io.hstore.db.evidence.Qualifier;
import io.hstore.db.hora.Budget;
import io.hstore.db.hora.Diffusion;
import io.hstore.db.retrieve.Rrf;
import io.hstore.db.retrieve.Selection;
import io.hstore.db.semantic.Encoder;
import io.hstore.db.semantic.Reranker;
import io.hstore.db.semantic.SemanticPlane;
import io.hstore.engine.HStoreException;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.txn.IncidentEdge;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

public final class Retrieval {

    public static final int MAX_LIMIT = 100;
    public static final int SEEDS = 10;
    public static final int RERANKED = 30;
    public static final double COVERAGE = 1.5;

    public record Query(String text, List<Long> anchors, Set<String> resolveIn, Set<String> types, OptionalLong validAt,
                        OptionalLong recordedAt, EvidencePolicy policy, Optional<String> hierarchy, boolean history, int limit) {

        public Query {
            anchors = List.copyOf(anchors);
            resolveIn = Set.copyOf(resolveIn);
            types = Set.copyOf(types);
            if (limit < 1 || limit > MAX_LIMIT) {
                throw HStoreException.invalid("a retrieval returns between 1 and " + MAX_LIMIT + " hits");
            }
            if (text.isBlank() && anchors.isEmpty()) {
                throw HStoreException.invalid("a retrieval needs text or anchors");
            }
        }

        public static Query of(String text) {
            return new Query(text, List.of(), Set.of(), Set.of(), OptionalLong.empty(), OptionalLong.empty(), EvidencePolicy.ANY, Optional.empty(),
                    false, 10);
        }

        public Query withAnchors(List<Long> ids) {
            return new Query(text, ids, resolveIn, types, validAt, recordedAt, policy, hierarchy, history, limit);
        }

        public Query resolvingIn(Set<String> names) {
            return new Query(text, anchors, names, types, validAt, recordedAt, policy, hierarchy, history, limit);
        }

        public Query inTypes(Set<String> names) {
            return new Query(text, anchors, resolveIn, names, validAt, recordedAt, policy, hierarchy, history, limit);
        }

        public Query withValidAt(OptionalLong instant) {
            return new Query(text, anchors, resolveIn, types, instant, recordedAt, policy, hierarchy, history, limit);
        }

        public Query withRecordedAt(OptionalLong instant) {
            return new Query(text, anchors, resolveIn, types, validAt, instant, policy, hierarchy, history, limit);
        }

        public Query withPolicy(EvidencePolicy value) {
            return new Query(text, anchors, resolveIn, types, validAt, recordedAt, value, hierarchy, history, limit);
        }

        public Query inHierarchy(Optional<String> name) {
            return new Query(text, anchors, resolveIn, types, validAt, recordedAt, policy, name, history, limit);
        }

        public Query withHistory(boolean value) {
            return new Query(text, anchors, resolveIn, types, validAt, recordedAt, policy, hierarchy, value, limit);
        }

        public Query limit(int value) {
            return new Query(text, anchors, resolveIn, types, validAt, recordedAt, policy, hierarchy, history, value);
        }

        boolean timed() {
            return validAt.isPresent() || recordedAt.isPresent();
        }
    }

    public record Hit(long section, long document, long revision, double score, double lexical, double semantic, double structural,
                      double temporal, double provenance, boolean current, List<Long> later, List<Long> path, List<Long> evidence,
                      List<String> heading, String text) {
        public Hit {
            later = List.copyOf(later);
            path = List.copyOf(path);
            evidence = List.copyOf(evidence);
            heading = List.copyOf(heading);
        }
    }

    public record Result(List<Hit> hits, List<Long> anchors, List<String> notes) {
        public Result {
            hits = List.copyOf(hits);
            anchors = List.copyOf(anchors);
            notes = List.copyOf(notes);
        }
    }

    private static final class Candidate {
        final Section section;
        final Revision inForce;
        final Set<Long> matched = new LinkedHashSet<>();
        double score;
        double lexical;
        double semantic;
        double structural;

        Candidate(Section section, Revision inForce) {
            this.section = section;
            this.inForce = inForce;
        }
    }

    private static final class Stages {
        private final Budget.Meter parent;
        private final long started = System.nanoTime();
        private final List<String> notes;

        Stages(Budget.Meter parent, List<String> notes) {
            this.parent = parent;
            this.notes = notes;
        }

        Budget.Meter next(double share) {
            Budget budget = parent.budget();
            long left = Math.max(0, budget.maxRows() - parent.consumed());
            long rows = Math.max(1, (long) Math.min(left, Math.ceil(left * share)));
            Duration time = budget.maxTime().minusNanos(System.nanoTime() - started);
            return new Budget(rows, budget.maxDepth(), time.isNegative() || time.isZero() ? Duration.ofNanos(1) : time).start();
        }

        void settle(Budget.Meter stage) {
            parent.tryConsume(Math.min(stage.consumed(), stage.budget().maxRows()));
        }

        void note(String text) {
            notes.add(text);
        }
    }

    private static final class Cache {
        final Reader reader;
        final Map<Long, List<Section>> sections = new HashMap<>();
        final Map<Long, List<Revision>> revisions = new HashMap<>();
        final Map<Long, byte[]> markdown = new HashMap<>();

        Cache(Reader reader) {
            this.reader = reader;
        }

        List<Section> sections(long revision) {
            return sections.computeIfAbsent(revision, reader::sections);
        }

        List<Revision> revisions(long document) {
            return revisions.computeIfAbsent(document, reader::revisions);
        }

        String text(Section section) {
            byte[] bytes = markdown.computeIfAbsent(section.revision(), id -> reader.revision(id)
                    .map(found -> reader.markdown(found).getBytes(StandardCharsets.UTF_8)).orElse(new byte[0]));
            int end = Math.min(bytes.length, section.end());
            return section.start() >= end ? "" : new String(bytes, section.start(), end - section.start(), StandardCharsets.UTF_8);
        }

        Optional<Section> sameAnchor(long revision, String anchor) {
            return sections(revision).stream().filter(other -> other.anchor().equals(anchor)).findFirst();
        }
    }

    private Retrieval() {
    }

    public static Result retrieve(Reader reader, Query query, Budget.Meter meter) {
        int pool = Math.max(50, query.limit() * 5);
        List<String> notes = new ArrayList<>();
        Stages stages = new Stages(meter, notes);
        Cache cache = new Cache(reader);
        Set<Integer> types = new HashSet<>();
        query.types().forEach(name -> types.add(reader.requireReadableType(name).id()));
        LinkedHashSet<Long> anchors = new LinkedHashSet<>();
        for (long anchor : query.anchors()) {
            reader.require(anchor);
            anchors.add(anchor);
        }
        anchors.addAll(resolve(reader, query.text(), query.resolveIn(), meter));
        long now = query.validAt().orElse(System.currentTimeMillis());
        boolean searchHistory = query.history() || query.timed();

        Map<Long, Double> lexical = new LinkedHashMap<>();
        Map<Long, Double> semantic = new LinkedHashMap<>();
        if (!query.text().isBlank()) {
            Budget.Meter stage = stages.next(0.2);
            try {
                reader.searchText(query.text(), new TextIndex.Options(Math.min(pool, TextIndex.MAX_TOP), searchHistory, query.types()), stage)
                        .forEach(hit -> lexical.put(hit.section(), hit.score()));
            } catch (HStoreException.ResourceLimit exhausted) {
                stages.note("the text search stopped at its budget");
            }
            stages.settle(stage);
            stage = stages.next(0.25);
            try {
                Encoder.Encoded encoded = reader.database().semantic().encoder().encode(query.text());
                if (query.hierarchy().isPresent()) {
                    Hierarchies.route(reader, query.hierarchy().get(), encoded.vector(), Router.Policy.DEFAULT.withTop(Math.min(pool, 1000)), stage)
                            .leaves().stream()
                            .filter(leaf -> reader.section(leaf.id()).filter(section -> typeMatches(reader, types, section)).isPresent())
                            .forEach(leaf -> semantic.put(leaf.id(), leaf.score()));
                    stages.note("semantic candidates routed through hierarchy " + query.hierarchy().get());
                } else {
                    reader.nearestVectors(encoded.model(), encoded.vector(), pool, Optional.of("content"), searchHistory,
                                    SemanticPlane.Consistency.FRESH, stage).stream()
                            .filter(hit -> reader.section(hit.owner()).filter(section -> typeMatches(reader, types, section)).isPresent())
                            .forEach(hit -> semantic.putIfAbsent(hit.owner(), 1.0 - hit.distance()));
                }
            } catch (HStoreException.ResourceLimit exhausted) {
                stages.note("the vector search stopped at its budget");
            }
            stages.settle(stage);
        }

        Map<Long, Double> seeds = new LinkedHashMap<>();
        anchors.forEach(anchor -> seeds.put(anchor, 1.0));
        List<Rrf.Fused<Long>> early = Rrf.combine(List.of(List.copyOf(lexical.keySet()), List.copyOf(semantic.keySet())));
        double top = early.isEmpty() ? 1 : early.getFirst().score();
        Set<Long> seededSections = new HashSet<>();
        if (anchors.isEmpty()) {
            early.stream().limit(SEEDS).forEach(fused -> {
                seeds.merge(fused.item(), fused.score() / top, Math::max);
                seededSections.add(fused.item());
            });
        }
        Map<Long, Double> structural = new LinkedHashMap<>();
        if (!seeds.isEmpty()) {
            Budget.Meter stage = stages.next(0.5);
            Diffusion.Model model = new Diffusion.Model(Set.of(), Map.of(), Diffusion.RoleFactors.NONE, query.validAt(), OptionalLong.empty(),
                    query.policy(), true);
            Diffusion.Result walk = Diffusion.on(reader).run(seeds, model, new Diffusion.Params(0.2, 1e-4, pool * 4, 0), stage);
            if (walk.exhausted()) {
                stages.note("the structural walk stopped at its budget");
            }
            walk.atoms().stream()
                    .filter(scored -> !seededSections.contains(scored.id()))
                    .filter(scored -> reader.section(scored.id()).filter(section -> typeMatches(reader, types, section)).isPresent())
                    .limit(pool)
                    .forEach(scored -> structural.put(scored.id(), scored.estimate()));
            stages.settle(stage);
        }

        List<List<Long>> lists = new ArrayList<>();
        for (Map<Long, Double> list : List.of(lexical, semantic, structural)) {
            if (!list.isEmpty()) {
                lists.add(List.copyOf(list.keySet()));
            }
        }
        Budget.Meter assembly = stages.next(1.0);
        LinkedHashMap<Long, Candidate> resolved = new LinkedHashMap<>();
        boolean cut = false;
        for (Rrf.Fused<Long> item : Rrf.combine(lists)) {
            if (!assembly.tryConsume(1)) {
                cut = true;
                break;
            }
            Optional<Section> found = reader.section(item.item());
            if (found.isEmpty()) {
                continue;
            }
            Section section = found.get();
            Optional<Revision> inForce = reader.documentAt(section.document(), now, query.recordedAt().orElse(Long.MAX_VALUE));
            if (inForce.isEmpty() && !query.timed()) {
                inForce = reader.latestRevision(section.document());
            }
            if (inForce.isEmpty()) {
                continue;
            }
            Section target = section;
            if (!query.history() && inForce.get().id() != section.revision()) {
                Optional<Section> same = cache.sameAnchor(inForce.get().id(), section.anchor());
                if (same.isPresent()) {
                    target = same.get();
                } else if (query.timed()) {
                    continue;
                }
            }
            Revision force = inForce.get();
            Candidate candidate = resolved.computeIfAbsent(target.id(), key -> new Candidate(reader.section(key).orElseThrow(), force));
            candidate.matched.add(section.id());
            candidate.score = Math.max(candidate.score, item.score());
            candidate.lexical = Math.max(candidate.lexical, lexical.getOrDefault(item.item(), 0.0));
            candidate.semantic = Math.max(candidate.semantic, semantic.getOrDefault(item.item(), 0.0));
            candidate.structural = Math.max(candidate.structural, structural.getOrDefault(item.item(), 0.0));
            if (resolved.size() >= pool) {
                break;
            }
        }
        List<Candidate> ordered = new ArrayList<>(resolved.values());
        Reranker reranker = reader.database().options().reranker();
        if (reranker != Reranker.NONE && !ordered.isEmpty() && !query.text().isBlank()) {
            List<Candidate> head = new ArrayList<>(ordered.subList(0, Math.min(RERANKED, ordered.size())));
            try {
                double[] scores = reranker.score(query.text(), head.stream().map(candidate -> cache.text(candidate.section)).toList());
                Map<Candidate, Double> byCandidate = new HashMap<>();
                for (int i = 0; i < head.size(); i++) {
                    byCandidate.put(head.get(i), i < scores.length && Double.isFinite(scores[i]) ? scores[i] : Double.NEGATIVE_INFINITY);
                }
                List<Candidate> reranked = new ArrayList<>(head);
                reranked.sort(Comparator.<Candidate>comparingDouble(candidate -> -byCandidate.get(candidate)).thenComparingInt(head::indexOf));
                List<Candidate> order = new ArrayList<>(reranked);
                order.addAll(ordered.subList(head.size(), ordered.size()));
                for (int rank = 1; rank <= order.size(); rank++) {
                    order.get(rank - 1).score = Rrf.term(rank);
                }
                ordered = order;
                stages.note("the top " + head.size() + " candidates were reordered by " + reranker.name());
            } catch (RuntimeException failure) {
                stages.note("the reranker " + reranker.name() + " failed and was skipped: " + failure.getMessage());
            }
        }

        List<Hit> hits = new ArrayList<>();
        for (Candidate candidate : ordered) {
            Optional<Hit> hit = hit(reader, cache, query, candidate, anchors, now, assembly);
            if (hit.isEmpty()) {
                cut = true;
                break;
            }
            hits.add(hit.get());
        }
        if (cut) {
            stages.note("candidate assembly stopped at its budget; the answer uses the candidates found so far");
        }
        List<Hit> chosen = select(hits, query, anchors, assembly, stages);
        stages.settle(assembly);
        return new Result(chosen, List.copyOf(anchors), notes);
    }

    private static boolean typeMatches(Reader reader, Set<Integer> types, Section section) {
        return types.isEmpty() || reader.atom(section.document()).map(atom -> types.contains(atom.type().id())).orElse(false);
    }

    private static Set<Long> resolve(Reader reader, String text, Set<String> types, Budget.Meter meter) {
        Set<Long> found = new LinkedHashSet<>();
        if (types.isEmpty() || text.isBlank()) {
            return found;
        }
        String[] words = text.replaceAll("(?i)'s\\b", "").split("[^\\p{L}\\p{N}_.-]+");
        List<String> grams = new ArrayList<>();
        for (int size = 3; size >= 1; size--) {
            for (int i = 0; i + size <= words.length; i++) {
                String gram = String.join(" ", Arrays.asList(words).subList(i, i + size)).replaceAll("^[.-]+|[.-]+$", "");
                if (gram.codePointCount(0, gram.length()) >= 3) {
                    grams.add(gram);
                }
            }
        }
        for (String type : types) {
            reader.requireReadableType(type);
            for (String gram : grams) {
                for (String key : new LinkedHashSet<>(List.of(gram, gram.toLowerCase(Locale.ROOT)))) {
                    if (!meter.tryConsume(1)) {
                        return found;
                    }
                    reader.find(type, key).ifPresent(found::add);
                }
            }
        }
        return found;
    }

    private static Optional<Hit> hit(Reader reader, Cache cache, Query query, Candidate candidate, Set<Long> anchors, long now, Budget.Meter meter) {
        Section section = candidate.section;
        Revision revision = reader.revision(section.revision()).orElseThrow();
        boolean inForce = candidate.inForce.id() == section.revision() && candidate.inForce.validAt(now);
        Set<Long> lineage = new LinkedHashSet<>();
        lineage.add(section.id());
        for (long matched : candidate.matched) {
            reader.section(matched).flatMap(found -> reader.revision(found.revision()))
                    .filter(found -> found.number() <= revision.number())
                    .ifPresent(_ -> lineage.add(matched));
        }
        for (Revision earlier : cache.revisions(section.document())) {
            if (earlier.number() <= revision.number()) {
                cache.sameAnchor(earlier.id(), section.anchor()).ifPresent(same -> lineage.add(same.id()));
            }
        }
        if (!meter.tryConsume(lineage.size())) {
            return Optional.empty();
        }
        List<Long> path = List.of();
        boolean linked = false;
        boolean live = false;
        double confidence = 0;
        Set<Long> evidence = new LinkedHashSet<>();
        for (long source : revision.sources()) {
            if (Provenance.evidence(reader, source).isPresent() || reader.visible(source)) {
                evidence.add(source);
            }
        }
        double provenance = evidence.isEmpty() ? 0 : 1;
        for (long member : lineage) {
            for (IncidentEdge incident : reader.incidentEdges(member).toList()) {
                if (!meter.tryConsume(1 + anchors.size())) {
                    return Optional.empty();
                }
                if (!reader.visible(incident.edge())) {
                    continue;
                }
                linked = true;
                live |= incident.validFrom() <= now && now < incident.validTo();
                Optional<Incidence> incidence = reader.incidence(incident.edge(), member);
                long qualifierId = incidence.map(Incidence::qualifier).orElse(0L);
                if (qualifierId != 0) {
                    Optional<Qualifier> qualifier = Provenance.qualifier(reader, qualifierId);
                    if (qualifier.isPresent() && query.policy().admits(qualifier)) {
                        confidence = Math.max(confidence, qualifier.get().confidence());
                        qualifier.get().evidence().stream().filter(id -> Provenance.evidence(reader, id).isPresent() || reader.visible(id))
                                .forEach(evidence::add);
                    }
                }
                if (path.isEmpty()) {
                    for (long anchor : anchors) {
                        if (reader.incidence(incident.edge(), anchor).isPresent()) {
                            path = List.of(anchor, incident.edge(), section.id());
                            break;
                        }
                    }
                }
            }
        }
        provenance = Math.max(provenance, confidence);
        boolean current = inForce && (!linked || live);
        List<Long> later = new ArrayList<>();
        for (Revision next : cache.revisions(section.document())) {
            if (next.id() == revision.id() || next.validFrom() <= revision.validFrom()
                    || query.recordedAt().isPresent() && next.recordedAt() > query.recordedAt().getAsLong()) {
                continue;
            }
            Optional<Section> same = cache.sameAnchor(next.id(), section.anchor());
            if (same.isEmpty() || same.get().contentHash() != section.contentHash()) {
                later.add(next.id());
            }
        }
        return Optional.of(new Hit(section.id(), section.document(), section.revision(), candidate.score, candidate.lexical, candidate.semantic,
                candidate.structural, current ? 1 : 0, provenance, current, later, path, List.copyOf(evidence), reader.headingPath(section),
                cache.text(section)));
    }

    private static List<Hit> select(List<Hit> hits, Query query, Set<Long> anchors, Budget.Meter meter, Stages stages) {
        if (hits.size() <= query.limit()) {
            return hits;
        }
        List<String> terms = new ArrayList<>(new LinkedHashSet<>(Tokenizer.tokens(query.text())));
        List<Long> anchorList = List.copyOf(anchors);
        int slots = terms.size() + anchorList.size();
        List<double[]> covers = new ArrayList<>();
        for (Hit hit : hits) {
            Set<String> words = new HashSet<>(Tokenizer.tokens(hit.text()));
            double[] cover = new double[slots];
            for (int i = 0; i < terms.size(); i++) {
                cover[i] = words.contains(terms.get(i)) ? 1 : 0;
            }
            for (int i = 0; i < anchorList.size(); i++) {
                cover[terms.size() + i] = !hit.path().isEmpty() && hit.path().getFirst().equals(anchorList.get(i)) ? 1 : 0;
            }
            covers.add(cover);
        }
        double[] first = covers.getFirst();
        double[] counts = new double[slots];
        for (int h = 1; h < hits.size(); h++) {
            for (int i = 0; i < slots; i++) {
                counts[i] += first[i] > 0 ? 0 : covers.get(h)[i];
            }
        }
        double[] weights = new double[slots];
        double total = 0;
        for (int i = 0; i < slots; i++) {
            weights[i] = counts[i] == 0 ? 0 : 1 / counts[i];
            total += weights[i];
        }
        for (int i = 0; i < slots && total > 0; i++) {
            weights[i] /= total;
        }
        double best = hits.getFirst().score();
        List<Selection.Candidate> candidates = new ArrayList<>();
        for (int h = 1; h < hits.size(); h++) {
            double[] cover = covers.get(h).clone();
            for (int i = 0; i < slots; i++) {
                cover[i] = first[i] > 0 ? 0 : cover[i];
            }
            Hit hit = hits.get(h);
            candidates.add(new Selection.Candidate(hit.section(), Math.min(1, hit.score() / best), cover, 1, new long[]{hit.section()},
                    Selection.UNREACHABLE));
        }
        Set<Long> chosen = new LinkedHashSet<>(List.of(hits.getFirst().section()));
        if (query.limit() > 1) {
            Selection.Outcome outcome = Selection.select(candidates, new Selection.Options(query.limit() - 1, Long.MAX_VALUE, 1.0, COVERAGE, weights,
                    false), meter);
            outcome.selected().forEach(selected -> chosen.add(selected.edge()));
            if (outcome.exhausted()) {
                stages.note("coverage selection stopped at its budget; the rest of the answer follows the ranking");
                for (Hit hit : hits) {
                    if (chosen.size() >= query.limit()) {
                        break;
                    }
                    chosen.add(hit.section());
                }
            }
        }
        return hits.stream().filter(hit -> chosen.contains(hit.section())).toList();
    }
}

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
import io.hstore.engine.txn.IncidentEdge;

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
        double score;
        double lexical;
        double semantic;
        double structural;

        Candidate(Section section, Revision inForce) {
            this.section = section;
            this.inForce = inForce;
        }
    }

    private Retrieval() {
    }

    public static Result retrieve(Reader reader, Query query, Budget.Meter meter) {
        int pool = Math.max(50, query.limit() * 5);
        List<String> notes = new ArrayList<>();
        Set<Integer> types = new HashSet<>();
        query.types().forEach(name -> types.add(reader.requireReadableType(name).id()));
        LinkedHashSet<Long> anchors = new LinkedHashSet<>();
        for (long anchor : query.anchors()) {
            reader.require(anchor);
            anchors.add(anchor);
        }
        anchors.addAll(resolve(reader, query.text(), query.resolveIn()));

        Map<Long, Double> lexical = new LinkedHashMap<>();
        Map<Long, Double> semantic = new LinkedHashMap<>();
        if (!query.text().isBlank()) {
            reader.searchText(query.text(), new TextIndex.Options(Math.min(pool, TextIndex.MAX_TOP), query.history(), query.types()), meter)
                    .forEach(hit -> lexical.put(hit.section(), hit.score()));
            Encoder.Encoded encoded = reader.database().semantic().encoder().encode(query.text());
            if (query.hierarchy().isPresent()) {
                Hierarchies.route(reader, query.hierarchy().get(), encoded.vector(), Router.Policy.DEFAULT.withTop(Math.min(pool, 1000)), meter)
                        .leaves().forEach(leaf -> semantic.put(leaf.id(), leaf.score()));
                notes.add("semantic candidates routed through hierarchy " + query.hierarchy().get());
            } else {
                reader.nearestVectors(encoded.model(), encoded.vector(), pool, Optional.of("content"), query.history(),
                                SemanticPlane.Consistency.FRESH, meter).stream()
                        .filter(hit -> reader.section(hit.owner()).filter(section -> typeMatches(reader, types, section)).isPresent())
                        .forEach(hit -> semantic.putIfAbsent(hit.owner(), 1.0 - hit.distance()));
            }
        }

        Map<Long, Double> seeds = new LinkedHashMap<>();
        anchors.forEach(anchor -> seeds.put(anchor, 1.0));
        List<Rrf.Fused<Long>> early = Rrf.combine(List.of(List.copyOf(lexical.keySet()), List.copyOf(semantic.keySet())));
        double top = early.isEmpty() ? 1 : early.getFirst().score();
        early.stream().limit(SEEDS).forEach(fused -> seeds.merge(fused.item(), fused.score() / top, Math::max));
        Map<Long, Double> structural = new LinkedHashMap<>();
        if (!seeds.isEmpty()) {
            Diffusion.Model model = new Diffusion.Model(Set.of(), Map.of(), Diffusion.RoleFactors.NONE, query.validAt(), OptionalLong.empty(),
                    query.policy(), true);
            try {
                Diffusion.on(reader).run(seeds, model, new Diffusion.Params(0.2, 1e-4, pool * 4, 0), meter).atoms().stream()
                        .filter(scored -> reader.section(scored.id()).filter(section -> typeMatches(reader, types, section)).isPresent())
                        .limit(pool)
                        .forEach(scored -> structural.put(scored.id(), scored.estimate()));
            } catch (HStoreException.ResourceLimit exhausted) {
                notes.add("the structural walk stopped at the budget");
            }
        }

        List<List<Long>> lists = new ArrayList<>();
        for (Map<Long, Double> list : List.of(lexical, semantic, structural)) {
            if (!list.isEmpty()) {
                lists.add(List.copyOf(list.keySet()));
            }
        }
        List<Rrf.Fused<Long>> fused = Rrf.combine(lists);
        Reranker reranker = reader.database().options().reranker();
        if (reranker != Reranker.NONE && !fused.isEmpty() && !query.text().isBlank()) {
            List<Long> head = fused.stream().limit(RERANKED).map(Rrf.Fused::item).toList();
            double[] scores = reranker.score(query.text(), head.stream().map(id -> reader.sectionText(reader.section(id).orElseThrow())).toList());
            List<Long> reranked = new ArrayList<>(head);
            Map<Long, Double> byId = new HashMap<>();
            for (int i = 0; i < head.size(); i++) {
                byId.put(head.get(i), i < scores.length ? scores[i] : 0);
            }
            reranked.sort(Comparator.<Long>comparingDouble(id -> -byId.get(id)).thenComparingInt(head::indexOf));
            List<Long> order = new ArrayList<>(reranked);
            fused.stream().skip(head.size()).map(Rrf.Fused::item).forEach(order::add);
            List<Rrf.Fused<Long>> rescored = new ArrayList<>();
            for (int rank = 1; rank <= order.size(); rank++) {
                rescored.add(new Rrf.Fused<>(order.get(rank - 1), Rrf.term(rank), List.of(rank)));
            }
            fused = rescored;
            notes.add("the top " + head.size() + " candidates were reordered by " + reranker.name());
        }

        long now = query.validAt().orElse(System.currentTimeMillis());
        LinkedHashMap<Long, Candidate> resolved = new LinkedHashMap<>();
        for (Rrf.Fused<Long> item : fused) {
            Optional<Section> found = reader.section(item.item());
            if (found.isEmpty()) {
                continue;
            }
            Section section = found.get();
            Optional<Revision> inForce = query.timed()
                    ? reader.documentAt(section.document(), now, query.recordedAt().orElse(Long.MAX_VALUE))
                    : query.history() ? reader.revision(section.revision()) : reader.latestRevision(section.document());
            if (inForce.isEmpty()) {
                continue;
            }
            Section target = section;
            if (!query.history() && inForce.get().id() != section.revision()) {
                Optional<Section> same = reader.sections(inForce.get().id()).stream().filter(other -> other.anchor().equals(section.anchor())).findFirst();
                if (same.isPresent()) {
                    target = same.get();
                } else if (query.timed()) {
                    continue;
                }
            }
            Candidate candidate = resolved.computeIfAbsent(target.id(), key -> new Candidate(reader.section(key).orElseThrow(), inForce.get()));
            candidate.score = Math.max(candidate.score, item.score());
            candidate.lexical = Math.max(candidate.lexical, lexical.getOrDefault(item.item(), 0.0));
            candidate.semantic = Math.max(candidate.semantic, semantic.getOrDefault(item.item(), 0.0));
            candidate.structural = Math.max(candidate.structural, structural.getOrDefault(item.item(), 0.0));
            if (resolved.size() >= pool) {
                break;
            }
        }

        List<Hit> hits = new ArrayList<>();
        for (Candidate candidate : resolved.values()) {
            meter.consume(1);
            hits.add(hit(reader, query, candidate, anchors, now));
        }
        List<Hit> chosen = select(hits, query, anchors, meter);
        return new Result(chosen, List.copyOf(anchors), notes);
    }

    private static boolean typeMatches(Reader reader, Set<Integer> types, Section section) {
        return types.isEmpty() || reader.atom(section.document()).map(atom -> types.contains(atom.type().id())).orElse(false);
    }

    private static Set<Long> resolve(Reader reader, String text, Set<String> types) {
        Set<Long> found = new LinkedHashSet<>();
        if (types.isEmpty() || text.isBlank()) {
            return found;
        }
        String[] words = text.split("[^\\p{L}\\p{N}_.'-]+");
        List<String> grams = new ArrayList<>();
        for (int size = 3; size >= 1; size--) {
            for (int i = 0; i + size <= words.length; i++) {
                String gram = String.join(" ", Arrays.asList(words).subList(i, i + size)).replaceAll("^[.'-]+|[.'-]+$", "");
                if (!gram.isBlank()) {
                    grams.add(gram);
                }
            }
        }
        for (String type : types) {
            reader.requireReadableType(type);
            for (String gram : grams) {
                for (String key : new LinkedHashSet<>(List.of(gram, gram.toLowerCase(Locale.ROOT)))) {
                    reader.find(type, key).ifPresent(found::add);
                }
            }
        }
        return found;
    }

    private static Hit hit(Reader reader, Query query, Candidate candidate, Set<Long> anchors, long now) {
        Section section = candidate.section;
        Revision revision = reader.revision(section.revision()).orElseThrow();
        boolean inForce = candidate.inForce.id() == section.revision();
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
        for (IncidentEdge incident : reader.incidentEdges(section.id()).toList()) {
            if (!reader.visible(incident.edge())) {
                continue;
            }
            linked = true;
            live |= incident.validFrom() <= now && now < incident.validTo();
            List<Member> members = reader.members(incident.edge()).toList();
            for (Member member : members) {
                if (member.atom() == section.id() && member.qualifier() != 0) {
                    Optional<Qualifier> qualifier = Provenance.qualifier(reader, member.qualifier());
                    if (qualifier.isPresent() && query.policy().admits(qualifier)) {
                        confidence = Math.max(confidence, qualifier.get().confidence());
                        qualifier.get().evidence().stream().filter(id -> Provenance.evidence(reader, id).isPresent() || reader.visible(id))
                                .forEach(evidence::add);
                    }
                }
            }
            if (path.isEmpty()) {
                for (long anchor : anchors) {
                    if (members.stream().anyMatch(member -> member.atom() == anchor)) {
                        path = List.of(anchor, incident.edge(), section.id());
                        break;
                    }
                }
            }
        }
        provenance = Math.max(provenance, confidence);
        boolean current = inForce && (!linked || live);
        List<Long> later = new ArrayList<>();
        if (!query.history()) {
            for (Revision next : reader.revisions(section.document())) {
                if (next.number() <= revision.number() || query.recordedAt().isPresent() && next.recordedAt() > query.recordedAt().getAsLong()) {
                    continue;
                }
                Optional<Section> same = reader.sections(next.id()).stream().filter(other -> other.anchor().equals(section.anchor())).findFirst();
                if (same.isEmpty() || same.get().contentHash() != section.contentHash()) {
                    later.add(next.id());
                }
            }
        }
        return new Hit(section.id(), section.document(), section.revision(), candidate.score, candidate.lexical, candidate.semantic,
                candidate.structural, current ? 1 : 0, provenance, current, later, path, List.copyOf(evidence), reader.headingPath(section),
                reader.sectionText(section));
    }

    private static List<Hit> select(List<Hit> hits, Query query, Set<Long> anchors, Budget.Meter meter) {
        if (hits.size() <= query.limit()) {
            return hits;
        }
        List<String> terms = new ArrayList<>(new LinkedHashSet<>(Tokenizer.tokens(query.text())));
        List<Long> anchorList = List.copyOf(anchors);
        int slots = terms.size() + anchorList.size();
        List<double[]> covers = new ArrayList<>();
        double[] counts = new double[slots];
        for (Hit hit : hits) {
            Set<String> words = new HashSet<>(Tokenizer.tokens(hit.text()));
            double[] cover = new double[slots];
            for (int i = 0; i < terms.size(); i++) {
                cover[i] = words.contains(terms.get(i)) ? 1 : 0;
            }
            for (int i = 0; i < anchorList.size(); i++) {
                cover[terms.size() + i] = !hit.path().isEmpty() && hit.path().getFirst().equals(anchorList.get(i)) ? 1 : 0;
            }
            for (int i = 0; i < slots; i++) {
                counts[i] += cover[i];
            }
            covers.add(cover);
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
        double[] first = covers.getFirst();
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
        Set<Long> chosen = new HashSet<>(List.of(hits.getFirst().section()));
        if (query.limit() > 1) {
            Selection.select(candidates, new Selection.Options(query.limit() - 1, Long.MAX_VALUE, 1.0, COVERAGE, weights, false), meter)
                    .selected().forEach(selected -> chosen.add(selected.edge()));
        }
        return hits.stream().filter(hit -> chosen.contains(hit.section())).toList();
    }
}

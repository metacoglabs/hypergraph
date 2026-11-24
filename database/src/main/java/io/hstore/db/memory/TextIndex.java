// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.db.hora.Budget;
import io.hstore.db.payload.PayloadStore;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.catalog.SlotChange;
import io.hstore.engine.index.IndexValues;
import io.hstore.engine.index.IndexValues.Marker;
import io.hstore.engine.index.PostingIndex;
import io.hstore.engine.index.Postings;
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;
import io.hstore.engine.txn.Derivation;
import io.hstore.engine.txn.View;
import io.hstore.engine.txn.Workspace;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

public final class TextIndex {

    public static final double K1 = 1.2;
    public static final double B = 0.75;
    public static final int MAX_QUERY_TERMS = 32;
    public static final int MAX_TOP = 1000;

    public record Posting(int tf, int fingerprint) {
    }

    public record Stat(long sections, long tokens, long currentSections, long currentTokens) {

        static final Stat ZERO = new Stat(0, 0, 0, 0);

        Stat plus(long sectionDelta, long tokenDelta, boolean current) {
            return new Stat(sections + sectionDelta, tokens + tokenDelta, currentSections + (current ? sectionDelta : 0),
                    currentTokens + (current ? tokenDelta : 0));
        }

        Stat shift(long sectionDelta, long tokenDelta) {
            return new Stat(sections, tokens, currentSections + sectionDelta, currentTokens + tokenDelta);
        }

        boolean empty() {
            return sections == 0 && tokens == 0 && currentSections == 0 && currentTokens == 0;
        }
    }

    public record Options(int top, boolean history, Set<String> types) {

        public Options {
            if (top < 1 || top > MAX_TOP) {
                throw new IllegalArgumentException("TOP must be between 1 and " + MAX_TOP);
            }
            types = Set.copyOf(types);
        }

        public static Options top(int top) {
            return new Options(top, false, Set.of());
        }

        public Options withHistory() {
            return new Options(top, true, types);
        }

        public Options inTypes(Set<String> names) {
            return new Options(top, history, names);
        }
    }

    public record Hit(long section, long document, long revision, double score, List<String> terms) {
        public Hit {
            terms = List.copyOf(terms);
        }
    }

    static final ValueCodec<Posting> POSTING_CODEC = ValueCodec.rows(_ -> 10,
            (out, posting) -> out.putVarInt(posting.tf()).putInt(posting.fingerprint()),
            in -> new Posting(in.getVarInt(), in.getInt()));

    static final PostingIndex<Posting> POSTINGS = PostingIndex.of(117, 118, "text-postings", POSTING_CODEC,
            posting -> Hashing.of(posting.tf(), posting.fingerprint()), 16);

    static final ValueCodec<Stat> STAT_CODEC = ValueCodec.rows(_ -> 40,
            (out, stat) -> out.putVarLong(stat.sections()).putVarLong(stat.tokens()).putVarLong(stat.currentSections()).putVarLong(stat.currentTokens()),
            in -> new Stat(in.getVarLong(), in.getVarLong(), in.getVarLong(), in.getVarLong()));

    public static final Slot<Postings<Posting>> INDEX = Slot.derived(62, "text-index", POSTINGS.schema());
    public static final Slot<Stat> STATS = Slot.derived(63, "text-stats", new TreeSchema<>(119, "text-stats", FingerprintMode.SET, STAT_CODEC,
            EntryMeasure.keyed(stat -> Hashing.of(stat.sections(), stat.tokens(), stat.currentSections(), stat.currentTokens()))));
    static final PostingIndex<Marker> REVISION_INDEX = PostingIndex.of(133, 134, "revision-sections", IndexValues.MARKER, _ -> 1, 16);
    public static final Slot<Postings<Marker>> REVISION_SECTIONS = Slot.derived(71, "revision-sections", REVISION_INDEX.schema());
    public static final List<Slot<?>> SLOTS = List.of(INDEX, STATS, REVISION_SECTIONS);

    private TextIndex() {
    }

    public static long termKey(int tenant, String term) {
        return Hashing.of(tenant, Hashing.of(term));
    }

    public static int fingerprint(String term) {
        return (int) Hashing.of(0x5EED_7E47L, Hashing.of(term));
    }

    public static long totalKey(int tenant) {
        return -1L - tenant;
    }

    public static Stat totals(View view, int tenant) {
        return view.get(STATS, totalKey(tenant)).orElse(Stat.ZERO);
    }

    public static Indexing indexing(Supplier<PayloadStore> payloads) {
        return new Indexing(payloads);
    }

    static long currentKey(long termKey) {
        return Hashing.of(termKey, 0x43555252L);
    }

    public static final class Indexing implements Derivation {

        private static final long CACHE_BYTES = 32L << 20;
        private static final int CACHE_TERMS = 8192;

        private record Span(long payload, int start, int end) {
        }

        record Terms(Map<String, Integer> counts, int length) {
        }

        private final Supplier<PayloadStore> payloads;
        private final LinkedHashMap<Long, byte[]> bytes = new LinkedHashMap<>(16, 0.75f, true);
        private final LinkedHashMap<Span, Terms> terms = new LinkedHashMap<>(16, 0.75f, true);
        private long cachedBytes;

        Indexing(Supplier<PayloadStore> payloads) {
            this.payloads = payloads;
        }

        public synchronized void forget(long payload) {
            byte[] dropped = bytes.remove(payload);
            if (dropped != null) {
                cachedBytes -= dropped.length;
            }
            terms.keySet().removeIf(span -> span.payload() == payload);
        }

        @Override
        public void onSlot(SlotChange<?> change, Workspace workspace) {
            if (change.slot() == MemorySlots.SECTIONS) {
                change.before().map(Section.class::cast).ifPresent(section -> remove(workspace, section));
                change.after().map(Section.class::cast).ifPresent(section -> add(workspace, section));
            } else if (change.slot() == MemorySlots.DOCUMENTS) {
                Optional<DocumentHead> before = change.before().map(DocumentHead.class::cast);
                Optional<DocumentHead> after = change.after().map(DocumentHead.class::cast);
                long was = before.map(DocumentHead::latest).orElse(0L);
                long now = after.map(DocumentHead::latest).orElse(0L);
                if (was == now) {
                    return;
                }
                int tenant = after.or(() -> before).orElseThrow().tenant();
                if (was != 0) {
                    sectionsOf(workspace, was).forEach(section -> postCurrent(workspace, section, false));
                }
                if (now != 0) {
                    sectionsOf(workspace, now).forEach(section -> postCurrent(workspace, section, true));
                }
                Stat leaving = was == 0 ? Stat.ZERO : workspace.get(STATS, was).orElse(Stat.ZERO);
                Stat arriving = now == 0 ? Stat.ZERO : workspace.get(STATS, now).orElse(Stat.ZERO);
                adjust(workspace, totalKey(tenant), stat -> stat.shift(arriving.sections() - leaving.sections(), arriving.tokens() - leaving.tokens()));
            }
        }

        private static List<Section> sectionsOf(Workspace workspace, long revision) {
            return REVISION_INDEX.stream(workspace.tree(REVISION_SECTIONS), revision)
                    .flatMap(entry -> workspace.get(MemorySlots.SECTIONS, entry.key()).stream())
                    .toList();
        }

        static boolean current(Workspace workspace, Section section) {
            return workspace.get(MemorySlots.DOCUMENTS, section.document()).map(head -> head.latest() == section.revision()).orElse(false);
        }

        private void add(Workspace workspace, Section section) {
            Optional<Terms> found = terms(workspace, section);
            long length = found.map(Terms::length).orElse(0);
            found.ifPresent(counted -> post(workspace, section, counted, INDEX_ALL, true));
            workspace.replace(REVISION_SECTIONS, REVISION_INDEX.add(workspace.tree(REVISION_SECTIONS), workspace.scope(), section.revision(), section.id(),
                    Marker.PRESENT));
            workspace.write(STATS, section.id(), _ -> Optional.of(new Stat(1, length, 0, 0)));
            adjust(workspace, section.revision(), stat -> stat.plus(1, length, false));
            boolean current = current(workspace, section);
            if (current) {
                found.ifPresent(counted -> post(workspace, section, counted, INDEX_CURRENT, true));
            }
            adjust(workspace, totalKey(section.tenant()), stat -> stat.plus(1, length, current));
        }

        private void remove(Workspace workspace, Section section) {
            Optional<Terms> found = terms(workspace, section);
            long length = workspace.get(STATS, section.id()).map(Stat::tokens).orElse((long) found.map(Terms::length).orElse(0));
            boolean current = current(workspace, section);
            found.ifPresent(counted -> {
                post(workspace, section, counted, INDEX_ALL, false);
                if (current) {
                    post(workspace, section, counted, INDEX_CURRENT, false);
                }
            });
            workspace.replace(REVISION_SECTIONS, REVISION_INDEX.remove(workspace.tree(REVISION_SECTIONS), workspace.scope(), section.revision(), section.id()));
            workspace.write(STATS, section.id(), _ -> Optional.empty());
            adjust(workspace, section.revision(), stat -> stat.plus(-1, -length, false));
            adjust(workspace, totalKey(section.tenant()), stat -> stat.plus(-1, -length, current));
        }

        private void postCurrent(Workspace workspace, Section section, boolean add) {
            terms(workspace, section).ifPresent(counted -> post(workspace, section, counted, INDEX_CURRENT, add));
        }

        private static final boolean INDEX_ALL = false;
        private static final boolean INDEX_CURRENT = true;

        private static void post(Workspace workspace, Section section, Terms counted, boolean currentKeys, boolean add) {
            counted.counts().forEach((term, tf) -> {
                long key = termKey(section.tenant(), term);
                long target = currentKeys ? currentKey(key) : key;
                workspace.replace(INDEX, add
                        ? POSTINGS.add(workspace.tree(INDEX), workspace.scope(), target, section.id(), new Posting(tf, fingerprint(term)))
                        : POSTINGS.remove(workspace.tree(INDEX), workspace.scope(), target, section.id()));
            });
        }

        private static void adjust(Workspace workspace, long key, UnaryOperator<Stat> change) {
            workspace.write(STATS, key, current -> {
                Stat next = change.apply(current.orElse(Stat.ZERO));
                return next.empty() ? Optional.empty() : Optional.of(next);
            });
        }

        Optional<Terms> terms(Workspace workspace, Section section) {
            Optional<Revision> revision = workspace.get(MemorySlots.REVISIONS, section.revision());
            if (revision.isEmpty()) {
                return Optional.empty();
            }
            Span span = new Span(revision.get().payload(), section.start(), section.end());
            synchronized (this) {
                Terms known = terms.get(span);
                if (known != null) {
                    return Optional.of(known);
                }
            }
            Optional<byte[]> content = content(span.payload());
            if (content.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Integer> counts = new LinkedHashMap<>();
            List<String> tokens = Tokenizer.tokens(new String(content.get(), span.start(), span.end() - span.start(), StandardCharsets.UTF_8));
            tokens.forEach(term -> counts.merge(term, 1, Integer::sum));
            Terms counted = new Terms(Map.copyOf(counts), tokens.size());
            synchronized (this) {
                terms.put(span, counted);
                while (terms.size() > CACHE_TERMS) {
                    terms.remove(terms.keySet().iterator().next());
                }
            }
            return Optional.of(counted);
        }

        private Optional<byte[]> content(long payload) {
            synchronized (this) {
                byte[] known = bytes.get(payload);
                if (known != null) {
                    return Optional.of(known);
                }
            }
            byte[] read;
            try {
                read = payloads.get().read(payload);
            } catch (RuntimeException unreadable) {
                if (payloads.get().erased(payload)) {
                    return Optional.empty();
                }
                throw unreadable;
            }
            synchronized (this) {
                if (bytes.putIfAbsent(payload, read) == null) {
                    cachedBytes += read.length;
                }
                while (cachedBytes > CACHE_BYTES && bytes.size() > 1) {
                    Map.Entry<Long, byte[]> eldest = bytes.entrySet().iterator().next();
                    cachedBytes -= eldest.getValue().length;
                    bytes.remove(eldest.getKey());
                }
            }
            return Optional.of(read);
        }
    }

    public static List<Hit> search(View view, int tenant, String query, Options options, Predicate<Section> admit, Budget.Meter meter) {
        Map<String, Integer> terms = new LinkedHashMap<>();
        for (String term : Tokenizer.tokens(query)) {
            if (terms.size() < MAX_QUERY_TERMS || terms.containsKey(term)) {
                terms.merge(term, 1, Integer::sum);
            }
        }
        Stat totals = totals(view, tenant);
        double n = options.history() ? totals.sections() : totals.currentSections();
        double tokens = options.history() ? totals.tokens() : totals.currentTokens();
        if (terms.isEmpty() || n == 0) {
            return List.of();
        }
        double average = tokens / n;
        Map<Long, Section> sections = new HashMap<>();
        Map<Long, Boolean> latest = new HashMap<>();
        Map<Long, Double> scores = new HashMap<>();
        Map<Long, List<String>> matched = new HashMap<>();
        for (Map.Entry<String, Integer> term : terms.entrySet()) {
            int fingerprint = fingerprint(term.getKey());
            List<Section> found = new ArrayList<>();
            List<Integer> frequencies = new ArrayList<>();
            long key = termKey(tenant, term.getKey());
            POSTINGS.stream(view.scan(INDEX), options.history() ? key : currentKey(key)).forEach(entry -> {
                meter.consume(1);
                if (entry.value().fingerprint() != fingerprint) {
                    return;
                }
                Section section = sections.computeIfAbsent(entry.key(), id -> view.get(MemorySlots.SECTIONS, id).orElse(null));
                if (section == null || section.tenant() != tenant) {
                    return;
                }
                if (!options.history() && !latest.computeIfAbsent(section.revision(), revision -> Documents.head(view, tenant, section.document())
                        .map(head -> head.latest() == revision).orElse(false))) {
                    return;
                }
                found.add(section);
                frequencies.add(entry.value().tf());
            });
            double df = found.size();
            double idf = Math.log(1 + (n - df + 0.5) / (df + 0.5));
            for (int i = 0; i < found.size(); i++) {
                Section section = found.get(i);
                double tf = frequencies.get(i);
                double length = view.get(STATS, section.id()).map(Stat::tokens).orElse(0L);
                double norm = tf * (K1 + 1) / (tf + K1 * (1 - B + B * length / average));
                scores.merge(section.id(), idf * norm * term.getValue(), Double::sum);
                matched.computeIfAbsent(section.id(), _ -> new ArrayList<>()).add(term.getKey());
            }
        }
        return scores.entrySet().stream()
                .filter(entry -> admit.test(sections.get(entry.getKey())))
                .sorted(Map.Entry.<Long, Double>comparingByValue(Comparator.reverseOrder()).thenComparing(Map.Entry.comparingByKey()))
                .limit(options.top())
                .map(entry -> {
                    Section section = sections.get(entry.getKey());
                    return new Hit(section.id(), section.document(), section.revision(), entry.getValue(), matched.get(section.id()));
                })
                .toList();
    }
}

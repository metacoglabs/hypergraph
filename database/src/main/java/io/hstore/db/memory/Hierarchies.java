// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Reader;
import io.hstore.db.Writer;
import io.hstore.db.hora.Budget;
import io.hstore.db.security.Principal;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.catalog.SlotChange;
import io.hstore.engine.feed.ChangeFeed;
import io.hstore.engine.feed.CommitEvent;
import io.hstore.engine.index.IndexValues.Marker;
import io.hstore.engine.index.PostingIndex;
import io.hstore.engine.index.Postings;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.TxnOptions;
import io.hstore.engine.txn.View;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

public final class Hierarchies {

    public static final int MAX_PER_TENANT = 16;
    public static final int MAX_FANOUT = 64;
    public static final int MAX_CAPACITY = 1024;
    public static final int MAX_LEVELS = 8;
    public static final String CENTROID = "centroid";
    public static final String REFERENCE = "reference:";
    private static final System.Logger LOG = System.getLogger("hstore.memory");
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    public record Spec(String name, String model, String view, Optional<String> type, int fanout, int capacity, boolean continuous) {
        public Spec {
            if (!NAME.matcher(name).matches()) {
                throw HStoreException.invalid("a hierarchy name is a letter or _ followed by up to 63 letters, digits or _, not '" + name + "'");
            }
            VectorRecord.requireView(view);
            if (fanout < 2 || fanout > MAX_FANOUT) {
                throw HStoreException.invalid("FANOUT must be between 2 and " + MAX_FANOUT);
            }
            if (capacity < 2 || capacity > MAX_CAPACITY) {
                throw HStoreException.invalid("LEAVES must be between 2 and " + MAX_CAPACITY);
            }
        }
    }

    private Hierarchies() {
    }

    public static List<Hierarchy> list(Reader reader) {
        return reader.engineView().scan(HierarchySlots.HIERARCHIES).values().filter(found -> found.tenant() == reader.tenant())
                .sorted(Comparator.comparing(Hierarchy::name)).toList();
    }

    public static Optional<Hierarchy> find(Reader reader, String name) {
        return list(reader).stream().filter(found -> found.name().equals(name)).findFirst();
    }

    private static Hierarchy require(Reader reader, String name) {
        return find(reader, name).orElseThrow(() -> HStoreException.invalid("hierarchy '" + name + "' does not exist"));
    }

    public static List<Region> regions(Reader reader, Hierarchy hierarchy) {
        View view = reader.engineView();
        return postings(view, HierarchySlots.HIERARCHY_REGIONS, HierarchySlots.REGION_INDEX, hierarchy.id()).stream()
                .flatMap(id -> view.get(HierarchySlots.REGIONS, id).stream())
                .filter(region -> region.tenant() == reader.tenant())
                .toList();
    }

    public static List<Long> children(Reader reader, long region) {
        View view = reader.engineView();
        return view.get(HierarchySlots.REGIONS, region).filter(found -> found.tenant() == reader.tenant())
                .map(_ -> postings(view, HierarchySlots.CHILDREN, HierarchySlots.CHILD_INDEX, region)).orElse(List.of());
    }

    public static Map<Long, Long> leaves(Reader reader, Hierarchy hierarchy) {
        Map<Long, Long> leaves = new LinkedHashMap<>();
        for (Region region : regions(reader, hierarchy)) {
            if (region.level() == 0) {
                children(reader, region.id()).forEach(section -> leaves.put(section, region.id()));
            }
        }
        return leaves;
    }

    public static float[] centroid(Reader reader, Hierarchy hierarchy, long region) {
        return vectorsOf(reader.engineView(), reader, region, CENTROID).stream().findFirst()
                .orElseThrow(() -> HStoreException.invalid("region " + region + " has no centroid"));
    }

    private static List<float[]> vectorsOf(View view, Reader reader, long owner, String prefix) {
        return VectorRecord.ofOwner(view, owner).stream()
                .filter(record -> record.tenant() == reader.tenant() && (prefix.endsWith(":") ? record.view().startsWith(prefix) : record.view().equals(prefix)))
                .sorted(Comparator.comparing(VectorRecord::view))
                .map(record -> reader.database().semantic().vectorOf(record))
                .toList();
    }

    private static List<Long> postings(View view, Slot<Postings<Marker>> slot, PostingIndex<Marker> index, long key) {
        return index.stream(view.scan(slot), key).map(entry -> entry.key()).toList();
    }

    public static Hierarchy create(Writer writer, Spec spec) {
        writer.principal().requireAdmin();
        requireMain(writer);
        if (find(writer, spec.name()).isPresent()) {
            throw HStoreException.invalid("hierarchy '" + spec.name() + "' already exists");
        }
        if (list(writer).size() >= MAX_PER_TENANT) {
            throw HStoreException.limit("a tenant can have at most " + MAX_PER_TENANT + " hierarchies; drop one first");
        }
        int model = writer.database().semantic().existingModel(spec.model())
                .orElseThrow(() -> HStoreException.invalid("no vectors use model '" + spec.model() + "'"));
        int type = spec.type().map(name -> writer.requireReadableType(name).id()).orElse(0);
        long id = writer.database().engine().transactions().allocateAtom();
        Hierarchy hierarchy = new Hierarchy(id, spec.name(), writer.tenant(), model, spec.view(), type, spec.fanout(), spec.capacity(),
                spec.continuous(), 0, writer.generation(), 0, Router.Calibration.TEMPERATURE, List.of(), 1);
        return new Work(writer, hierarchy).build();
    }

    public static Hierarchy refresh(Writer writer, String name) {
        writer.principal().requireWrite();
        requireMain(writer);
        return refreshed(writer, require(writer, name));
    }

    static Hierarchy refreshed(Writer writer, Hierarchy hierarchy) {
        ChangeFeed feed = writer.database().engine().feed();
        long target = Math.min(feed.lastGeneration(), writer.generation());
        if (target <= hierarchy.generation()) {
            return hierarchy;
        }
        List<CommitEvent> events = feed.replay(hierarchy.generation()).takeWhile(event -> event.generation() <= target)
                .filter(event -> event.branch() == Branch.MAIN).toList();
        if (!feed.covers(hierarchy.generation())) {
            return new Work(writer, hierarchy).rebuild();
        }
        Work work = new Work(writer, hierarchy);
        Set<Long> documents = new TreeSet<>();
        for (CommitEvent event : events) {
            documents.addAll(touched(writer, hierarchy, event));
        }
        if (documents.isEmpty()) {
            Hierarchy advanced = hierarchy.with(hierarchy.root(), target, hierarchy.leaves());
            writer.transaction().put(HierarchySlots.HIERARCHIES, hierarchy.id(), advanced);
            return advanced;
        }
        Map<Long, Map<Long, VectorRecord>> wanted = new LinkedHashMap<>();
        documents.forEach(document -> wanted.put(document, work.prune(document)));
        wanted.forEach(work::grow);
        return work.finish(target);
    }

    static boolean relevant(CommitEvent event) {
        for (SlotChange<?> change : event.slots()) {
            if (change.slot() == MemorySlots.SECTIONS || change.slot() == MemorySlots.DOCUMENTS) {
                return true;
            }
            if (change.slot() == VectorRecord.VECTORS && (Optional.ofNullable(value(change, true)).map(VectorRecord.class::cast)
                    .map(VectorRecord::indexed).orElse(false))) {
                return true;
            }
        }
        return false;
    }

    private static Object value(SlotChange<?> change, boolean afterFirst) {
        Optional<?> first = afterFirst ? change.after() : change.before();
        Optional<?> second = afterFirst ? change.before() : change.after();
        return first.isPresent() ? first.get() : second.orElse(null);
    }

    private static Set<Long> touched(Writer writer, Hierarchy hierarchy, CommitEvent event) {
        Set<Long> documents = new LinkedHashSet<>();
        View view = writer.engineView();
        for (SlotChange<?> change : event.slots()) {
            if (change.slot() == MemorySlots.SECTIONS) {
                Optional.ofNullable(value(change, false)).map(Section.class::cast).filter(section -> section.tenant() == hierarchy.tenant())
                        .ifPresent(section -> documents.add(section.document()));
            } else if (change.slot() == MemorySlots.DOCUMENTS) {
                Optional.ofNullable(value(change, false)).map(DocumentHead.class::cast).filter(head -> head.tenant() == hierarchy.tenant())
                        .ifPresent(_ -> documents.add(change.key()));
            } else if (change.slot() == VectorRecord.VECTORS) {
                Optional.ofNullable(value(change, false)).map(VectorRecord.class::cast)
                        .filter(record -> record.indexed() && record.tenant() == hierarchy.tenant() && record.model() == hierarchy.model()
                                && record.view().equals(hierarchy.view()))
                        .ifPresent(record -> view.get(MemorySlots.SECTIONS, record.owner()).map(Section::document)
                                .or(() -> view.get(HierarchySlots.LEAVES, HierarchySlots.leafKey(hierarchy.id(), record.owner())).map(Region.Leaf::document))
                                .ifPresent(documents::add));
            }
        }
        return documents;
    }

    public static Hierarchy rebuild(Writer writer, String name) {
        writer.principal().requireAdmin();
        requireMain(writer);
        return new Work(writer, require(writer, name)).rebuild();
    }

    public static void drop(Writer writer, String name) {
        writer.principal().requireAdmin();
        requireMain(writer);
        Hierarchy hierarchy = require(writer, name);
        new Work(writer, hierarchy).clear();
        writer.transaction().delete(HierarchySlots.HIERARCHIES, hierarchy.id());
    }

    public static Hierarchy calibrate(Writer writer, String name, OptionalDouble temperature, Map<Integer, double[]> levels) {
        writer.principal().requireAdmin();
        requireMain(writer);
        Hierarchy hierarchy = require(writer, name);
        if (temperature.isPresent() && !(temperature.getAsDouble() > 0 && temperature.getAsDouble() <= 100)) {
            throw HStoreException.invalid("the routing temperature must be in (0, 100]");
        }
        List<double[]> lines = new ArrayList<>(hierarchy.calibration());
        for (Map.Entry<Integer, double[]> level : new TreeMap<>(levels).entrySet()) {
            if (level.getKey() < 0 || level.getKey() >= MAX_LEVELS) {
                throw HStoreException.invalid("calibrated levels run from 0 to " + (MAX_LEVELS - 1));
            }
            if (!Double.isFinite(level.getValue()[0]) || !Double.isFinite(level.getValue()[1])) {
                throw HStoreException.invalid("calibration scale and shift must be finite");
            }
            while (lines.size() <= level.getKey()) {
                lines.add(new double[]{1, 0});
            }
            lines.set(level.getKey(), level.getValue().clone());
        }
        Hierarchy calibrated = hierarchy.calibrated(temperature.orElse(hierarchy.temperature()), lines);
        writer.transaction().put(HierarchySlots.HIERARCHIES, hierarchy.id(), calibrated);
        return calibrated;
    }

    public static void references(Writer writer, String name, long region, List<float[]> vectors) {
        writer.principal().requireAdmin();
        requireMain(writer);
        Hierarchy hierarchy = require(writer, name);
        Region found = writer.engineView().get(HierarchySlots.REGIONS, region)
                .filter(candidate -> candidate.hierarchy() == hierarchy.id() && candidate.tenant() == writer.tenant())
                .orElseThrow(() -> HStoreException.invalid("region " + region + " is not part of hierarchy '" + name + "'"));
        if (vectors.size() > 16) {
            throw HStoreException.invalid("a region keeps at most 16 reference vectors");
        }
        vectors.forEach(vector -> requireDimensions(writer.engineView(), hierarchy, vector.length));
        VectorRecord.ofOwner(writer.engineView(), found.id()).stream().filter(record -> record.view().startsWith(REFERENCE))
                .forEach(record -> writer.dropRegionVector(found.id(), record.view()));
        for (int i = 0; i < vectors.size(); i++) {
            writer.regionVector(found.id(), REFERENCE + i, hierarchy.model(), vectors.get(i));
        }
    }

    public static Router.Result route(Reader reader, String name, float[] query, Router.Policy policy, Budget.Meter meter) {
        if (reader.engineView().branch() != Branch.MAIN) {
            throw HStoreException.invalid("hierarchies are kept on the main branch");
        }
        Hierarchy hierarchy = require(reader, name);
        View view = reader.engineView();
        requireDimensions(view, hierarchy, query.length);
        Map<Long, Region> regions = new HashMap<>();
        Map<Long, float[]> vectors = new HashMap<>();
        Router.Tree tree = new Router.Tree() {
            private Region region(long id) {
                return regions.computeIfAbsent(id, key -> view.get(HierarchySlots.REGIONS, key)
                        .filter(found -> found.tenant() == reader.tenant() && found.hierarchy() == hierarchy.id())
                        .orElseThrow(() -> HStoreException.invalid("region " + key + " is missing")));
            }

            @Override
            public long root() {
                return hierarchy.root();
            }

            @Override
            public boolean leaf(long id) {
                return region(id).level() == 0;
            }

            @Override
            public int level(long id) {
                return region(id).level();
            }

            @Override
            public List<Long> children(long id) {
                List<Long> children = postings(view, HierarchySlots.CHILDREN, HierarchySlots.CHILD_INDEX, id);
                return region(id).level() == 0 ? children.stream().filter(reader::visible).toList() : children;
            }

            @Override
            public List<float[]> keys(long id) {
                List<float[]> references = vectorsOf(view, reader, id, REFERENCE);
                return references.isEmpty() ? vectorsOf(view, reader, id, CENTROID) : references;
            }

            @Override
            public float[] leafVector(long section) {
                return vectors.computeIfAbsent(section, key -> view.get(HierarchySlots.LEAVES, HierarchySlots.leafKey(hierarchy.id(), key))
                        .flatMap(leaf -> view.get(VectorRecord.VECTORS, leaf.vector()))
                        .map(record -> reader.database().semantic().vectorOf(record))
                        .orElseThrow(() -> HStoreException.invalid("section " + key + " has no vector in hierarchy '" + name + "'")));
            }
        };
        List<Long> all = policy.recall() ? leaves(reader, hierarchy).keySet().stream().filter(reader::visible).toList() : List.of();
        return Router.route(tree, query, policy, hierarchy.router(), meter, all);
    }

    public static AutoCloseable maintain(HypergraphDatabase database) {
        ChangeFeed feed = database.engine().feed();
        ChangeFeed.Subscription subscription = feed.subscribe(database.engine().transactions().current().id(), event -> {
            if (event.branch() == Branch.MAIN && relevant(event)) {
                refreshContinuous(database);
            }
        });
        ChangeFeed.Hold hold = feed.hold(() -> database.read(reader -> reader.engineView().scan(HierarchySlots.HIERARCHIES).values()
                .filter(Hierarchy::continuous).mapToLong(Hierarchy::generation).min().orElse(Long.MAX_VALUE)));
        refreshContinuous(database);
        return () -> {
            subscription.close();
            hold.close();
        };
    }

    private static void refreshContinuous(HypergraphDatabase database) {
        List<Hierarchy> continuous;
        try {
            continuous = database.read(reader -> reader.engineView().scan(HierarchySlots.HIERARCHIES).values().filter(Hierarchy::continuous).toList());
        } catch (RuntimeException failure) {
            LOG.log(System.Logger.Level.WARNING, "could not list continuous hierarchies", failure);
            return;
        }
        for (Hierarchy hierarchy : continuous) {
            try {
                database.write(TxnOptions.defaults(), Principal.SYSTEM.inTenant(hierarchy.tenant()), writer ->
                        writer.engineView().get(HierarchySlots.HIERARCHIES, hierarchy.id()).map(current -> refreshed(writer, current)));
            } catch (RuntimeException failure) {
                LOG.log(System.Logger.Level.WARNING, "continuous refresh of hierarchy " + hierarchy.name() + " failed", failure);
            }
        }
    }

    private static void requireDimensions(View view, Hierarchy hierarchy, int length) {
        if (hierarchy.root() == 0) {
            return;
        }
        VectorRecord.ofOwner(view, hierarchy.root()).stream().filter(record -> record.view().equals(CENTROID)).findFirst()
                .filter(record -> record.dimensions() != length)
                .ifPresent(record -> {
                    throw HStoreException.invalid("hierarchy " + hierarchy.name() + " holds " + record.dimensions() + "-dimensional vectors, not " + length);
                });
    }

    private static void requireMain(Writer writer) {
        if (writer.transaction().branch() != Branch.MAIN) {
            throw HStoreException.invalid("hierarchies are kept on the main branch");
        }
    }

    private static final class Work {

        private final Writer writer;
        private final Transaction txn;
        private Hierarchy hierarchy;
        private long root;
        private final Map<Long, Region> regions = new HashMap<>();
        private final Set<Long> dirty = new LinkedHashSet<>();
        private final Map<Long, float[]> centroids = new HashMap<>();
        private final Map<Long, float[]> leafVectors = new HashMap<>();
        private long leaves;
        private long seed;

        Work(Writer writer, Hierarchy hierarchy) {
            this.writer = writer;
            this.txn = writer.transaction();
            this.hierarchy = hierarchy;
            this.root = hierarchy.root();
            this.leaves = hierarchy.leaves();
            this.seed = hierarchy.id();
        }

        Hierarchy build() {
            Map<Long, VectorRecord> current = currentLeaves();
            if (!current.isEmpty()) {
                List<Long> ids = new ArrayList<>(current.keySet());
                Map<Long, Long> documents = new HashMap<>();
                current.forEach((section, record) -> {
                    leafVectors.put(section, writer.database().semantic().vectorOf(record));
                    documents.put(section, txn.get(MemorySlots.SECTIONS, section).orElseThrow().document());
                });
                root = cluster(ids, 0, current, documents);
                leaves = ids.size();
            }
            return finish(writer.generation());
        }

        Hierarchy rebuild() {
            clear();
            root = 0;
            leaves = 0;
            return build();
        }

        private long cluster(List<Long> ids, long parent, Map<Long, VectorRecord> records, Map<Long, Long> documents) {
            if (ids.size() <= hierarchy.capacity()) {
                long region = create(parent, 0);
                for (long section : ids) {
                    attach(region, section, documents.get(section), records.get(section).id());
                }
                setCount(region, ids.size());
                return region;
            }
            int k = Math.min(hierarchy.fanout(), (ids.size() + hierarchy.capacity() - 1) / hierarchy.capacity());
            int[] labels = Clustering.cluster(ids.stream().map(leafVectors::get).toList(), Math.max(2, k), seed++);
            Map<Integer, List<Long>> groups = new TreeMap<>();
            for (int i = 0; i < ids.size(); i++) {
                groups.computeIfAbsent(labels[i], _ -> new ArrayList<>()).add(ids.get(i));
            }
            long region = create(parent, 1);
            int level = 1;
            for (List<Long> group : groups.values()) {
                long child = cluster(group, region, records, documents);
                level = Math.max(level, region(child).level() + 1);
            }
            save(new Region(region, hierarchy.id(), parent, level, ids.size(), hierarchy.tenant()));
            return region;
        }

        Map<Long, VectorRecord> prune(long document) {
            Map<Long, VectorRecord> wanted = currentLeaves(document);
            long key = HierarchySlots.documentKey(hierarchy.id(), document);
            for (long section : postings(txn, HierarchySlots.DOCUMENT_LEAVES, HierarchySlots.DOCUMENT_INDEX, key)) {
                Optional<Region.Leaf> leaf = txn.get(HierarchySlots.LEAVES, HierarchySlots.leafKey(hierarchy.id(), section));
                if (!wanted.containsKey(section) || leaf.map(found -> found.vector() != wanted.get(section).id()).orElse(true)) {
                    remove(section);
                }
            }
            return wanted;
        }

        void grow(long document, Map<Long, VectorRecord> wanted) {
            for (Map.Entry<Long, VectorRecord> entry : wanted.entrySet()) {
                if (txn.get(HierarchySlots.LEAVES, HierarchySlots.leafKey(hierarchy.id(), entry.getKey())).isEmpty()) {
                    insert(entry.getKey(), document, entry.getValue());
                }
            }
        }

        private Map<Long, VectorRecord> currentLeaves() {
            Map<Long, VectorRecord> found = new LinkedHashMap<>();
            txn.scan(VectorRecord.VECTORS).values()
                    .filter(record -> record.tenant() == hierarchy.tenant() && record.indexed() && record.model() == hierarchy.model()
                            && record.view().equals(hierarchy.view()))
                    .forEach(record -> txn.get(MemorySlots.SECTIONS, record.owner()).filter(this::eligible)
                            .ifPresent(section -> found.put(section.id(), record)));
            return found;
        }

        private Map<Long, VectorRecord> currentLeaves(long document) {
            Map<Long, VectorRecord> found = new LinkedHashMap<>();
            Optional<DocumentHead> head = Documents.head(txn, hierarchy.tenant(), document);
            if (head.isEmpty() || !typeMatches(document)) {
                return found;
            }
            Optional<Revision> latest = txn.get(MemorySlots.REVISIONS, head.get().latest());
            latest.ifPresent(revision -> Documents.sections(txn, writer.database().memoryTypes(), hierarchy.tenant(), revision).forEach(section ->
                    VectorRecord.ofOwner(txn, section.id()).stream()
                            .filter(record -> record.indexed() && record.model() == hierarchy.model() && record.view().equals(hierarchy.view()))
                            .findFirst().ifPresent(record -> found.put(section.id(), record))));
            return found;
        }

        private boolean eligible(Section section) {
            return section.tenant() == hierarchy.tenant()
                    && Documents.head(txn, hierarchy.tenant(), section.document()).map(head -> head.latest() == section.revision()).orElse(false)
                    && typeMatches(section.document());
        }

        private boolean typeMatches(long document) {
            return hierarchy.type() == 0 || txn.atom(document).map(record -> record.type() == hierarchy.type()).orElse(false);
        }

        private void insert(long section, long document, VectorRecord record) {
            float[] vector = writer.database().semantic().vectorOf(record);
            leafVectors.put(section, vector);
            if (root == 0) {
                root = create(0, 0);
            }
            long region = root;
            List<Long> path = new ArrayList<>(List.of(region));
            while (region(region).level() > 0) {
                long best = 0;
                double score = Double.NEGATIVE_INFINITY;
                for (long child : children(region)) {
                    double similarity = Clustering.cosine(vector, centroid(child));
                    if (similarity > score || best == 0) {
                        score = similarity;
                        best = child;
                    }
                }
                region = best;
                path.add(region);
            }
            attach(region, section, document, record.id());
            for (long step : path) {
                setCount(step, region(step).count() + 1);
            }
            leaves++;
            if (children(region).size() > hierarchy.capacity()) {
                split(region);
            }
        }

        private void remove(long section) {
            Optional<Region.Leaf> leaf = txn.get(HierarchySlots.LEAVES, HierarchySlots.leafKey(hierarchy.id(), section));
            if (leaf.isEmpty()) {
                return;
            }
            txn.delete(HierarchySlots.LEAVES, HierarchySlots.leafKey(hierarchy.id(), section));
            long region = leaf.get().region();
            leaves--;
            for (long step = region; step != 0; step = region(step).parent()) {
                setCount(step, region(step).count() - 1);
                markDirty(step);
            }
            long current = region;
            while (current != 0 && region(current).count() == 0) {
                long parent = region(current).parent();
                delete(current);
                if (current == root) {
                    root = 0;
                }
                current = parent;
            }
            while (root != 0 && region(root).level() > 0 && children(root).size() == 1) {
                long only = children(root).getFirst();
                long old = root;
                setParent(only, 0);
                delete(old);
                root = only;
            }
        }

        private void split(long region) {
            Region found = region(region);
            List<Long> items = children(region);
            List<float[]> keys = items.stream().map(item -> found.level() == 0 ? leafVector(item) : centroid(item)).toList();
            int[] labels = Clustering.cluster(keys, 2, seed++);
            long sibling = create(found.parent(), found.level());
            long moved = 0;
            long movedCount = 0;
            for (int i = 0; i < items.size(); i++) {
                if (labels[i] != 1) {
                    continue;
                }
                long item = items.get(i);
                if (found.level() == 0) {
                    Region.Leaf leaf = txn.get(HierarchySlots.LEAVES, HierarchySlots.leafKey(hierarchy.id(), item)).orElseThrow();
                    txn.put(HierarchySlots.LEAVES, HierarchySlots.leafKey(hierarchy.id(), item),
                            new Region.Leaf(hierarchy.id(), item, leaf.document(), sibling, leaf.vector()));
                    movedCount++;
                } else {
                    setParent(item, sibling);
                    movedCount += region(item).count();
                }
                moved++;
            }
            if (moved == 0) {
                delete(sibling);
                return;
            }
            setCount(sibling, movedCount);
            setCount(region, region(region).count() - movedCount);
            markDirty(region);
            markDirty(sibling);
            if (found.parent() == 0) {
                long top = create(0, found.level() + 1);
                setParent(region, top);
                setParent(sibling, top);
                setCount(top, region(region).count() + region(sibling).count());
                root = top;
            } else if (children(found.parent()).size() > hierarchy.fanout()) {
                split(found.parent());
            }
        }

        private float[] leafVector(long section) {
            return leafVectors.computeIfAbsent(section, key -> txn.get(HierarchySlots.LEAVES, HierarchySlots.leafKey(hierarchy.id(), key))
                    .flatMap(leaf -> txn.get(VectorRecord.VECTORS, leaf.vector()))
                    .map(record -> writer.database().semantic().vectorOf(record))
                    .orElseThrow(() -> HStoreException.invalid("leaf " + key + " has lost its vector")));
        }

        private float[] centroid(long region) {
            float[] cached = centroids.get(region);
            if (cached != null) {
                return cached;
            }
            float[] stored = dirty.contains(region) ? recompute(region) : VectorRecord.ofOwner(txn, region).stream()
                    .filter(record -> record.view().equals(CENTROID)).findFirst()
                    .map(record -> writer.database().semantic().vectorOf(record)).orElseGet(() -> recompute(region));
            centroids.put(region, stored);
            return stored;
        }

        private float[] recompute(long region) {
            List<Long> items = children(region);
            if (items.isEmpty()) {
                return new float[0];
            }
            if (region(region).level() == 0) {
                return Clustering.mean(items.stream().map(this::leafVector).toList(), items.stream().map(_ -> 1L).toList());
            }
            return Clustering.combine(items.stream().map(this::centroid).toList(), items.stream().map(item -> region(item).count()).toList());
        }

        private Region region(long id) {
            return regions.computeIfAbsent(id, key -> txn.get(HierarchySlots.REGIONS, key)
                    .filter(found -> found.hierarchy() == hierarchy.id() && found.tenant() == hierarchy.tenant())
                    .orElseThrow(() -> HStoreException.invalid("region " + key + " is missing from hierarchy '" + hierarchy.name() + "'")));
        }

        private List<Long> children(long region) {
            return postings(txn, HierarchySlots.CHILDREN, HierarchySlots.CHILD_INDEX, region);
        }

        private void save(Region region) {
            regions.put(region.id(), region);
            txn.put(HierarchySlots.REGIONS, region.id(), region);
        }

        private long create(long parent, int level) {
            long id = writer.database().engine().transactions().allocateAtom();
            save(new Region(id, hierarchy.id(), parent, level, 0, hierarchy.tenant()));
            markDirty(id);
            return id;
        }

        private void delete(long region) {
            writer.dropRegionVector(region, null);
            txn.delete(HierarchySlots.REGIONS, region);
            regions.remove(region);
            centroids.remove(region);
            dirty.remove(region);
        }

        private void attach(long region, long section, long document, long vector) {
            txn.put(HierarchySlots.LEAVES, HierarchySlots.leafKey(hierarchy.id(), section), new Region.Leaf(hierarchy.id(), section, document, region, vector));
            markDirty(region);
        }

        private void markDirty(long region) {
            for (long step = region; step != 0 && regions.containsKey(step) || step != 0 && txn.get(HierarchySlots.REGIONS, step).isPresent();
                 step = region(step).parent()) {
                dirty.add(step);
                centroids.remove(step);
            }
        }

        private void setParent(long region, long parent) {
            Region found = region(region);
            long old = found.parent();
            save(found.withParent(parent));
            if (old != 0 && regions.containsKey(old)) {
                markDirty(old);
            }
            markDirty(region);
        }

        private void setCount(long region, long count) {
            save(region(region).withCount(count));
        }

        void clear() {
            for (long region : postings(txn, HierarchySlots.HIERARCHY_REGIONS, HierarchySlots.REGION_INDEX, hierarchy.id())) {
                Optional<Region> found = txn.get(HierarchySlots.REGIONS, region);
                if (found.map(Region::level).orElse(1) == 0) {
                    for (long child : children(region)) {
                        txn.delete(HierarchySlots.LEAVES, HierarchySlots.leafKey(hierarchy.id(), child));
                    }
                }
                if (found.isPresent()) {
                    writer.dropRegionVector(region, null);
                    txn.delete(HierarchySlots.REGIONS, region);
                }
            }
            regions.clear();
            dirty.clear();
            centroids.clear();
        }

        Hierarchy finish(long generation) {
            List<Long> order = dirty.stream().filter(id -> txn.get(HierarchySlots.REGIONS, id).isPresent())
                    .sorted(Comparator.comparingInt((Long id) -> region(id).level()).thenComparingLong(id -> id)).toList();
            for (long region : order) {
                centroids.remove(region);
            }
            Map<Long, float[]> changed = new LinkedHashMap<>();
            for (long region : order) {
                float[] centroid = recompute(region);
                centroids.put(region, centroid);
                if (centroid.length > 0) {
                    changed.put(region, centroid);
                }
            }
            if (!changed.isEmpty()) {
                writer.regionVectors(CENTROID, hierarchy.model(), changed);
            }
            hierarchy = hierarchy.with(root, generation, leaves);
            txn.put(HierarchySlots.HIERARCHIES, hierarchy.id(), hierarchy);
            return hierarchy;
        }
    }
}

// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.db.HypergraphDatabase;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.RootVector;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.index.IndexValues.Marker;
import io.hstore.engine.index.PostingIndex;
import io.hstore.engine.index.Postings;
import io.hstore.engine.tree.BulkBuilder;
import io.hstore.engine.tree.NodeSource;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeDiff;
import io.hstore.engine.tree.TreeDiff.Delta;
import io.hstore.engine.tree.WriteScope;
import io.hstore.engine.txn.Snapshot;
import io.hstore.engine.txn.Transaction;
import io.hstore.engine.txn.TxnOptions;
import io.hstore.engine.txn.Workspace;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

public final class TextRebuild {

    public record Result(int sections, int runs, long peakEntries) {
    }

    interface Steps {
        Steps NONE = new Steps() {
        };

        default void built() {
        }

        default void caughtUp() {
        }
    }

    private static final System.Logger LOG = System.getLogger("hstore.memory");
    static final int ENTRIES_PER_RUN = 1 << 19;
    private static final int MERGE_FAN_IN = 128;
    private static final int RUN_BUFFER = 1 << 14;
    private static final int CATCH_UP_ROUNDS = 8;
    private static final long SMALL_CATCH_UP = 256;
    private static final int POSTINGS = 0;
    private static final int STATS = 1;
    private static final int REVISIONS = 2;

    private record Item(int slot, long key, long sub, long count, long extra) implements Comparable<Item> {
        @Override
        public int compareTo(Item other) {
            int bySlot = Integer.compare(slot, other.slot);
            if (bySlot != 0) {
                return bySlot;
            }
            int byKey = Long.compare(key, other.key);
            return byKey != 0 ? byKey : Long.compare(sub, other.sub);
        }
    }

    private record Derived(Tree<Postings<TextIndex.Posting>> index, Tree<TextIndex.Stat> stats, Tree<Postings<Marker>> revisions) {

        RootVector over(RootVector roots) {
            return roots.with(TextIndex.INDEX.id(), index.root()).with(TextIndex.STATS.id(), stats.root())
                    .with(TextIndex.REVISION_SECTIONS.id(), revisions.root());
        }

        void into(Workspace workspace) {
            workspace.replace(TextIndex.INDEX, index);
            workspace.replace(TextIndex.STATS, stats);
            workspace.replace(TextIndex.REVISION_SECTIONS, revisions);
        }
    }

    private record CaughtUp(Derived derived, long changes) {
    }

    private final HypergraphDatabase database;
    private final TextIndex.Indexing indexing;
    private final int entriesPerRun;
    private final Steps steps;

    TextRebuild(HypergraphDatabase database, int entriesPerRun, Steps steps) {
        this.database = database;
        this.indexing = database.textIndexing();
        this.entriesPerRun = entriesPerRun;
        this.steps = steps;
    }

    public static Result run(HypergraphDatabase database, int branch) {
        return new TextRebuild(database, ENTRIES_PER_RUN, Steps.NONE).run(branch);
    }

    Result run(int branch) {
        return database.deferringErasures(() -> rebuild(branch));
    }

    private Result rebuild(int branch) {
        TxnOptions options = database.options().transaction().onBranch(branch).withMaxPages(Long.MAX_VALUE);
        try (Progress progress = new Progress(); Transaction txn = database.engine().begin(options)) {
            txn.apply("rebuild text index", progress::install);
            steps.built();
            for (int round = 0; round < CATCH_UP_ROUNDS; round++) {
                if (progress.advance(database.engine().snapshot(branch)) <= SMALL_CATCH_UP) {
                    break;
                }
            }
            steps.caughtUp();
            database.commit(txn);
            return progress.result();
        }
    }

    private final class Progress implements AutoCloseable {

        private Workspace origin;
        private RootVector mark;
        private Snapshot pinned;
        private Derived derived;
        private int sections;
        private int runs;
        private long peak;

        void install(Workspace workspace) {
            if (workspace.replaying()) {
                derived = catchUp(workspace, mark, workspace.base(), derived).derived();
            } else {
                origin = workspace;
                mark = workspace.base();
                derived = build(workspace);
            }
            derived.into(workspace);
        }

        long advance(Snapshot latest) {
            CaughtUp caught;
            try {
                caught = catchUp(origin, mark, latest.roots(), derived);
            } catch (RuntimeException failed) {
                latest.close();
                throw failed;
            }
            if (caught.changes() == 0) {
                latest.close();
                return 0;
            }
            derived = caught.derived();
            derived.into(origin);
            mark = latest.roots();
            if (pinned != null) {
                pinned.close();
            }
            pinned = latest;
            return caught.changes();
        }

        Result result() {
            return new Result(sections, runs, peak);
        }

        @Override
        public void close() {
            if (pinned != null) {
                pinned.close();
            }
        }

        private Derived build(Workspace workspace) {
            NodeSource source = workspace.source();
            WriteScope scope = workspace.scope();
            Feed feed = new Feed(TextIndex.POSTINGS.bulk(source, scope), TextIndex.REVISION_INDEX.bulk(source, scope),
                    new StatsBuilder(Tree.empty(TextIndex.STATS.schema(), source).builder(scope)));
            Path directory = temporary();
            try {
                Sorter sorter = new Sorter(entriesPerRun, directory);
                workspace.tree(MemorySlots.SECTIONS).forEachValue(section -> {
                    sections++;
                    emit(workspace, section, sorter);
                });
                sorter.drain(feed);
                runs = sorter.runs();
                peak = sorter.peak();
                return feed.build();
            } finally {
                delete(directory);
            }
        }

        private void emit(Workspace workspace, Section section, Sorter selection) {
            Optional<TextIndex.Indexing.Terms> terms = indexing.terms(workspace, section);
            long length = terms.map(TextIndex.Indexing.Terms::length).orElse(0);
            boolean current = TextIndex.Indexing.current(workspace, section);
            terms.ifPresent(counted -> counted.counts().forEach((term, tf) -> {
                long key = TextIndex.termKey(section.tenant(), term);
                int fingerprint = TextIndex.fingerprint(term);
                selection.offer(new Item(POSTINGS, key, section.id(), tf, fingerprint));
                if (current) {
                    selection.offer(new Item(POSTINGS, TextIndex.currentKey(key), section.id(), tf, fingerprint));
                }
            }));
            selection.offer(new Item(STATS, section.id(), 0, length, 0));
            selection.offer(new Item(STATS, section.revision(), section.id(), length, 0));
            selection.offer(new Item(STATS, TextIndex.totalKey(section.tenant()), section.id(), length, current ? 1 : 0));
            selection.offer(new Item(REVISIONS, section.revision(), section.id(), 0, 0));
        }
    }

    private CaughtUp catchUp(Workspace factory, RootVector from, RootVector to, Derived derived) {
        if (from.sameAs(to)) {
            return new CaughtUp(derived, 0);
        }
        Workspace scratch = factory.scratch(derived.over(from), indexing);
        NodeSource source = factory.source();
        long changes = replay(scratch, MemorySlots.REVISIONS, from, to, source, delta -> !(delta instanceof Delta.Removed))
                + replay(scratch, MemorySlots.SECTIONS, from, to, source, _ -> true)
                + replay(scratch, MemorySlots.DOCUMENTS, from, to, source, _ -> true)
                + replay(scratch, MemorySlots.REVISIONS, from, to, source, delta -> delta instanceof Delta.Removed);
        return new CaughtUp(new Derived(scratch.tree(TextIndex.INDEX), scratch.tree(TextIndex.STATS), scratch.tree(TextIndex.REVISION_SECTIONS)),
                changes);
    }

    private static <V> long replay(Workspace scratch, Slot<V> slot, RootVector from, RootVector to, NodeSource source, Predicate<Delta<V>> admit) {
        long[] changes = {0};
        TreeDiff.diff(new Tree<>(slot.schema(), source, from.get(slot)), new Tree<>(slot.schema(), source, to.get(slot)))
                .filter(admit)
                .forEach(delta -> {
                    changes[0]++;
                    Optional<V> after = switch (delta) {
                        case Delta.Added<V>(long _, V value) -> Optional.of(value);
                        case Delta.Changed<V>(long _, V _, V value) -> Optional.of(value);
                        case Delta.Removed<V> _ -> Optional.empty();
                    };
                    scratch.write(slot, delta.key(), _ -> after);
                });
        return changes[0];
    }

    private Path temporary() {
        try {
            return Files.createTempDirectory(database.scratchDirectory(), "text-rebuild-");
        } catch (IOException failure) {
            throw HStoreException.io("cannot create a directory for the text index rebuild", failure);
        }
    }

    private static void delete(Path directory) {
        try (Stream<Path> files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException failure) {
            LOG.log(System.Logger.Level.WARNING, "could not delete " + directory + "; it is removed when the database next opens", failure);
        }
    }

    private static final class Sorter {

        private final int limit;
        private final Path directory;
        private final List<Item> held = new ArrayList<>();
        private final List<Path> runs = new ArrayList<>();
        private int written;
        private long peak;

        Sorter(int limit, Path directory) {
            this.limit = limit;
            this.directory = directory;
        }

        void offer(Item item) {
            held.add(item);
            peak = Math.max(peak, held.size());
            if (held.size() >= limit) {
                spill();
            }
        }

        int runs() {
            return written;
        }

        long peak() {
            return peak;
        }

        void drain(Consumer<Item> into) {
            if (runs.isEmpty()) {
                held.sort(null);
                held.forEach(into);
                return;
            }
            if (!held.isEmpty()) {
                spill();
            }
            while (runs.size() > MERGE_FAN_IN) {
                List<Path> group = new ArrayList<>(runs.subList(0, MERGE_FAN_IN));
                runs.subList(0, MERGE_FAN_IN).clear();
                Path merged = next();
                try (DataOutputStream out = output(merged)) {
                    merge(group, item -> write(out, item));
                } catch (IOException failure) {
                    throw HStoreException.io("cannot write " + merged, failure);
                }
                runs.add(merged);
            }
            merge(runs, into);
        }

        private void spill() {
            held.sort(null);
            Path run = next();
            try (DataOutputStream out = output(run)) {
                for (Item item : held) {
                    write(out, item);
                }
            } catch (IOException failure) {
                throw HStoreException.io("cannot write " + run, failure);
            }
            runs.add(run);
            written++;
            held.clear();
        }

        private Path next() {
            return directory.resolve(runs.size() + "-" + written + "-" + System.nanoTime() + ".run");
        }

        private static DataOutputStream output(Path path) throws IOException {
            return new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(path), RUN_BUFFER));
        }

        private static void write(DataOutputStream out, Item item) {
            try {
                out.writeByte(item.slot());
                out.writeLong(item.key());
                out.writeLong(item.sub());
                out.writeLong(item.count());
                out.writeLong(item.extra());
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        private static void merge(List<Path> group, Consumer<Item> into) {
            List<RunReader> readers = new ArrayList<>();
            try {
                PriorityQueue<RunReader> queue = new PriorityQueue<>(Comparator.comparing(RunReader::current));
                for (Path run : group) {
                    RunReader reader = new RunReader(run);
                    readers.add(reader);
                    if (reader.advance()) {
                        queue.add(reader);
                    }
                }
                while (!queue.isEmpty()) {
                    RunReader reader = queue.poll();
                    into.accept(reader.current());
                    if (reader.advance()) {
                        queue.add(reader);
                    }
                }
            } catch (UncheckedIOException failure) {
                throw HStoreException.io("cannot merge the sorted runs of the text index rebuild", failure.getCause());
            } finally {
                readers.forEach(RunReader::close);
                for (Path run : group) {
                    try {
                        Files.deleteIfExists(run);
                    } catch (IOException ignored) {
                        LOG.log(System.Logger.Level.DEBUG, "could not delete " + run + " yet");
                    }
                }
            }
        }
    }

    private static final class RunReader {

        private final DataInputStream in;
        private Item current;

        RunReader(Path run) {
            try {
                in = new DataInputStream(new BufferedInputStream(Files.newInputStream(run), RUN_BUFFER));
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        Item current() {
            return current;
        }

        boolean advance() {
            try {
                int slot = in.read();
                if (slot < 0) {
                    current = null;
                    return false;
                }
                current = new Item(slot, in.readLong(), in.readLong(), in.readLong(), in.readLong());
                return true;
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        void close() {
            try {
                in.close();
            } catch (IOException ignored) {
                LOG.log(System.Logger.Level.DEBUG, "closing a sorted run failed");
            }
        }
    }

    private static final class Feed implements Consumer<Item> {

        private final PostingIndex<TextIndex.Posting>.Bulk postings;
        private final PostingIndex<Marker>.Bulk revisions;
        private final StatsBuilder stats;
        private Item last;

        Feed(PostingIndex<TextIndex.Posting>.Bulk postings, PostingIndex<Marker>.Bulk revisions, StatsBuilder stats) {
            this.postings = postings;
            this.revisions = revisions;
            this.stats = stats;
        }

        @Override
        public void accept(Item item) {
            if (last != null && last.compareTo(item) == 0) {
                return;
            }
            last = item;
            switch (item.slot()) {
                case POSTINGS -> postings.add(item.key(), item.sub(), new TextIndex.Posting((int) item.count(), (int) item.extra()));
                case STATS -> stats.add(item);
                default -> revisions.add(item.key(), item.sub(), Marker.PRESENT);
            }
        }

        Derived build() {
            return new Derived(postings.build(), stats.build(), revisions.build());
        }
    }

    private static final class StatsBuilder {

        private final BulkBuilder<TextIndex.Stat> builder;
        private boolean open;
        private long key;
        private TextIndex.Stat total;

        StatsBuilder(BulkBuilder<TextIndex.Stat> builder) {
            this.builder = builder;
        }

        void add(Item item) {
            if (open && item.key() != key) {
                builder.add(key, total);
                open = false;
            }
            TextIndex.Stat contribution = new TextIndex.Stat(1, item.count(), item.extra(), item.extra() * item.count());
            total = open ? new TextIndex.Stat(total.sections() + contribution.sections(), total.tokens() + contribution.tokens(),
                    total.currentSections() + contribution.currentSections(), total.currentTokens() + contribution.currentTokens()) : contribution;
            key = item.key();
            open = true;
        }

        Tree<TextIndex.Stat> build() {
            if (open) {
                builder.add(key, total);
            }
            return builder.build();
        }
    }
}

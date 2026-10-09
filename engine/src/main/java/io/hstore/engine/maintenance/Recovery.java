package io.hstore.engine.maintenance;

import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.CatalogImage;
import io.hstore.engine.catalog.CatalogStore;
import io.hstore.engine.catalog.Generation;
import io.hstore.engine.catalog.RootVector;
import io.hstore.engine.feed.ChangeFeed;
import io.hstore.engine.feed.FeedCodec;
import io.hstore.engine.page.Checksums;
import io.hstore.engine.page.PageHeader;
import io.hstore.engine.page.PageStore;
import io.hstore.engine.wal.WalRecord;
import io.hstore.engine.wal.WriteAheadLog;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class Recovery {

    public record Outcome(CatalogImage image, int replayedCommits, int discardedTransactions, int discardedCommits) {
    }

    private static final System.Logger LOG = System.getLogger("hstore.recovery");

    private Recovery() {
    }

    public static CatalogImage discover(CatalogStore catalog) {
        return catalog.loadLatest().orElseGet(CatalogImage::initial);
    }

    public static Outcome recover(CatalogImage checkpoint, WriteAheadLog wal, PageStore pages, ChangeFeed feed, FeedCodec codec) {
        Map<Long, List<WalRecord>> pending = new HashMap<>();
        List<WalRecord.Commit> commits = new ArrayList<>();
        wal.read(checkpoint.checkpointLsn()).forEach(positioned -> {
            switch (positioned.record()) {
                case WalRecord.Commit commit -> commits.add(commit);
                case WalRecord.Abort abort -> pending.remove(abort.txnId());
                case WalRecord.Checkpoint _ -> {
                }
                case WalRecord record -> pending.computeIfAbsent(record.txnId(), _ -> new ArrayList<>()).add(record);
            }
        });
        Generation generation = checkpoint.current();
        List<Generation> history = new ArrayList<>(checkpoint.history());
        int replayed = 0;
        int discarded = 0;
        for (WalRecord.Commit commit : commits) {
            List<WalRecord> records = pending.remove(commit.txnId());
            if (commit.generation() <= generation.id()) {
                continue;
            }
            List<WalRecord> body = records == null ? List.of() : records;
            body.forEach(record -> place(record, pages));
            if (discarded > 0 || !intact(body, pages)) {
                if (discarded == 0) {
                    LOG.log(System.Logger.Level.WARNING, "durable prefix ends before generation {0}: transaction {1} references pages that never reached storage",
                            commit.generation(), commit.txnId());
                }
                discarded++;
                continue;
            }
            generation = redo(generation, commit, body, pages, feed, codec);
            history.add(generation);
            replayed++;
        }
        pages.sync();
        feed.truncateAfter(generation.id());
        feed.sync();
        CatalogImage recovered = new CatalogImage(generation, history, pages.segments(), wal.end(), feed.size());
        LOG.log(System.Logger.Level.INFO, "recovered generation {0}: replayed {1} commits from lsn {2}, discarded {3} unfinished transactions and {4} non-durable commits",
                generation.id(), replayed, checkpoint.checkpointLsn(), pending.size(), discarded);
        return new Outcome(recovered, replayed, pending.size(), discarded);
    }

    private static void place(WalRecord record, PageStore pages) {
        switch (record) {
            case WalRecord.Page page -> pages.place(page.pageId(), page.address());
            case WalRecord.PageRef ref -> pages.place(ref.pageId(), ref.address());
            default -> {
            }
        }
    }

    private static boolean intact(List<WalRecord> records, PageStore pages) {
        for (WalRecord record : records) {
            if (record instanceof WalRecord.PageRef(long _, long pageId, long _, int length, int checksum)) {
                try {
                    MemorySegment page = pages.read(pageId);
                    PageHeader.verify(page, pageId);
                    if (page.byteSize() < length || Checksums.crc32c(page, 0, length) != checksum) {
                        return false;
                    }
                } catch (RuntimeException _) {
                    return false;
                }
            }
        }
        return true;
    }

    private static Generation redo(Generation base, WalRecord.Commit commit, List<WalRecord> records,
                                   PageStore pages, ChangeFeed feed, FeedCodec codec) {
        Map<Integer, Branch> branches = new TreeMap<>(base.branches());
        for (WalRecord record : records) {
            switch (record) {
                case WalRecord.Page(long _, long pageId, long _, byte[] image) -> pages.write(pageId, MemorySegment.ofArray(image));
                case WalRecord.BranchMeta(long _, int id, String name, int parent, long baseGeneration, long created, Branch.State state) -> {
                    RootVector roots = branches.containsKey(id) ? branches.get(id).roots() : RootVector.EMPTY;
                    branches.put(id, new Branch(id, name, parent, baseGeneration, created, state, roots));
                }
                case WalRecord.Root(long _, int branch, int slot, var root) -> {
                    Branch target = branches.get(branch);
                    branches.put(branch, target.withRoots(target.roots().with(slot, root)));
                }
                case WalRecord.Feed(long _, byte[] payload) -> feed.append(codec.decode(payload));
                case WalRecord.Begin _, WalRecord.PageRef _, WalRecord.Commit _, WalRecord.Abort _, WalRecord.Checkpoint _ -> {
                }
            }
        }
        return new Generation(commit.generation(), commit.wallTime(), commit.txnId(),
                Math.max(base.nextAtom(), commit.nextAtom()), Math.max(base.nextTxn(), commit.nextTxn()), branches);
    }
}

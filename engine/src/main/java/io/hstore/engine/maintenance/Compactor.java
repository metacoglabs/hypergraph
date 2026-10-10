package io.hstore.engine.maintenance;

import io.hstore.engine.catalog.Generation;
import io.hstore.engine.catalog.SlotRegistry;
import io.hstore.engine.page.PageAddress;
import io.hstore.engine.page.PageId;
import io.hstore.engine.page.PageStore;
import io.hstore.engine.page.SegmentInfo;
import io.hstore.engine.page.SegmentState;
import io.hstore.engine.tree.TreeWalker;
import io.hstore.engine.txn.CrashPoint;
import io.hstore.engine.txn.TransactionManager;

import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Compactor {

    public record Report(Map<Integer, Long> liveBytes, List<Integer> compacted, long generation) {
    }

    private record Cut(List<Generation> generations, Set<Integer> sealed) {
    }

    private static final long PASS_BYTES = 4L << 20;

    private final TransactionManager transactions;
    private final PageStore pages;
    private final SlotRegistry slots;
    private final Checkpointer checkpointer;
    private final CrashPoint.Injector faults;
    private final Map<Integer, Long> retiredBeforeCheckpoint = new ConcurrentHashMap<>();

    public Compactor(TransactionManager transactions, PageStore pages, SlotRegistry slots, Checkpointer checkpointer,
                     CrashPoint.Injector faults) {
        this.transactions = transactions;
        this.pages = pages;
        this.slots = slots;
        this.checkpointer = checkpointer;
        this.faults = faults;
    }

    public Map<Integer, Long> liveness() {
        return bytesBySegment(reachable(retained()));
    }

    private List<Generation> retained() {
        return Stream.concat(transactions.history().stream(), Stream.of(transactions.current())).toList();
    }

    private Cut cut() {
        return transactions.exclusive(() -> {
            int active = pages.activeSegment();
            Set<Integer> sealed = pages.segments().stream()
                    .filter(segment -> segment.state() == SegmentState.SEALED && segment.id() != active)
                    .map(SegmentInfo::id)
                    .collect(Collectors.toUnmodifiableSet());
            return new Cut(retained(), sealed);
        });
    }

    private BitSet reachable(List<Generation> generations) {
        BitSet live = new BitSet();
        TreeWalker walker = new TreeWalker(transactions.source());
        generations.stream()
                .flatMap(generation -> generation.branches().values().stream())
                .forEach(branch -> branch.roots().roots().forEach((slot, ref) ->
                        walker.visit(ref, slots.slot(slot).schema(), pageId -> mark(live, pageId))));
        return live;
    }

    private static boolean mark(BitSet live, long pageId) {
        int index = Math.toIntExact(PageId.indexOf(pageId));
        if (live.get(index)) {
            return false;
        }
        live.set(index);
        return true;
    }

    private Map<Integer, Long> bytesBySegment(BitSet live) {
        Map<Integer, Long> bytes = new TreeMap<>();
        live.stream().forEach(index -> {
            long address = pages.addressOf(PageId.pack(index, 0));
            bytes.merge(PageAddress.segmentOf(address), (long) PageAddress.unitsOf(address) * PageId.UNIT_BYTES, Long::sum);
        });
        return bytes;
    }

    public Report compact(double liveThreshold) {
        return compact(liveThreshold, Integer.MAX_VALUE);
    }

    public Report compact(double liveThreshold, int maxVictims) {
        Cut cut = cut();
        BitSet live = reachable(cut.generations());
        Map<Integer, Long> liveBytes = bytesBySegment(live);
        List<Integer> victims = pages.segments().stream()
                .filter(segment -> segment.state() == SegmentState.SEALED && cut.sealed().contains(segment.id()))
                .filter(segment -> liveBytes.getOrDefault(segment.id(), 0L) < segment.bytes() * liveThreshold)
                .sorted(Comparator.comparingDouble(segment -> (double) liveBytes.getOrDefault(segment.id(), 0L) / Math.max(1, segment.bytes())))
                .limit(maxVictims)
                .map(SegmentInfo::id)
                .toList();
        if (victims.isEmpty()) {
            return new Report(liveBytes, victims, transactions.current().id());
        }
        victims.forEach(id -> pages.transition(id, SegmentState.COMPACTING, 0));
        move(live, Set.copyOf(victims));
        faults.reach(CrashPoint.COMPACTION_MOVE);
        long retiredAt = transactions.current().id();
        transactions.compacted();
        long checkpoints = checkpointer.completed();
        victims.forEach(id -> {
            pages.transition(id, SegmentState.RETIRED, retiredAt);
            retiredBeforeCheckpoint.put(id, checkpoints);
        });
        faults.reach(CrashPoint.COMPACTION_RETIRE);
        return new Report(liveBytes, victims, retiredAt);
    }

    private void move(BitSet live, Set<Integer> victims) {
        long[] moved = new long[64];
        int count = 0;
        long bytes = 0;
        for (int index = live.nextSetBit(0); index >= 0; index = live.nextSetBit(index + 1)) {
            long pageId = PageId.pack(index, 0);
            long from = pages.addressOf(pageId);
            if (!victims.contains(PageAddress.segmentOf(from))) {
                continue;
            }
            if (count + 2 > moved.length) {
                moved = Arrays.copyOf(moved, moved.length * 2);
            }
            moved[count++] = pageId;
            moved[count++] = pages.copy(pageId);
            bytes += (long) PageAddress.unitsOf(from) * PageId.UNIT_BYTES;
            if (bytes >= PASS_BYTES) {
                repoint(moved, count);
                count = 0;
                bytes = 0;
            }
        }
        repoint(moved, count);
    }

    private void repoint(long[] moved, int count) {
        if (count == 0) {
            return;
        }
        pages.sync();
        for (int i = 0; i < count; i += 2) {
            pages.place(moved[i], moved[i + 1]);
        }
    }

    public List<Integer> reclaim() {
        OptionalLong pinned = transactions.oldestPinned();
        long checkpoints = checkpointer.completed();
        List<Integer> reclaimable = pages.segments().stream()
                .filter(segment -> segment.state() == SegmentState.RETIRED)
                .filter(segment -> pinned.isEmpty() || pinned.getAsLong() > segment.retiredAt())
                .filter(segment -> checkpoints > retiredBeforeCheckpoint.getOrDefault(segment.id(), -1L))
                .map(SegmentInfo::id)
                .toList();
        reclaimable.forEach(id -> {
            pages.delete(id);
            retiredBeforeCheckpoint.remove(id);
        });
        return reclaimable;
    }
}

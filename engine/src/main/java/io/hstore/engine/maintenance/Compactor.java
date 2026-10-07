package io.hstore.engine.maintenance;

import io.hstore.engine.catalog.Generation;
import io.hstore.engine.catalog.SlotRegistry;
import io.hstore.engine.page.PageId;
import io.hstore.engine.page.PageStore;
import io.hstore.engine.page.SegmentInfo;
import io.hstore.engine.page.SegmentState;
import io.hstore.engine.tree.TreeWalker;
import io.hstore.engine.tree.VisitedPages;
import io.hstore.engine.txn.TransactionManager;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongPredicate;
import java.util.stream.Stream;

public final class Compactor {

    public record Report(Map<Integer, Long> liveBytes, List<Integer> compacted, long generation) {
    }

    private final TransactionManager transactions;
    private final PageStore pages;
    private final SlotRegistry slots;

    public Compactor(TransactionManager transactions, PageStore pages, SlotRegistry slots) {
        this.transactions = transactions;
        this.pages = pages;
        this.slots = slots;
    }

    public Map<Integer, Long> liveness() {
        VisitedPages visited = new VisitedPages(pages.segments().stream().mapToLong(SegmentInfo::pages).sum());
        TreeWalker walker = new TreeWalker(transactions.source());
        Stream.concat(transactions.history().stream(), Stream.of(transactions.current()))
                .flatMap(generation -> generation.branches().values().stream())
                .forEach(branch -> branch.roots().roots().forEach((slot, ref) ->
                        walker.visit(ref, slots.slot(slot).schema(), visited)));
        Map<Integer, long[]> bySegment = new TreeMap<>();
        visited.forEach((pageId, units) -> bySegment.computeIfAbsent(PageId.segmentOf(pageId), _ -> new long[1])[0] += (long) units * PageId.UNIT_BYTES);
        Map<Integer, Long> live = new TreeMap<>();
        bySegment.forEach((segment, bytes) -> live.put(segment, bytes[0]));
        return live;
    }

    public Report compact(double liveThreshold) {
        return compact(liveThreshold, Integer.MAX_VALUE);
    }

    public Report compact(double liveThreshold, int maxVictims) {
        Map<Integer, Long> live = liveness();
        int active = pages.activeSegment();
        List<Integer> victims = pages.segments().stream()
                .filter(segment -> segment.state() == SegmentState.SEALED && segment.id() != active)
                .filter(segment -> live.getOrDefault(segment.id(), 0L) < segment.bytes() * liveThreshold)
                .sorted(Comparator.comparingDouble(segment -> (double) live.getOrDefault(segment.id(), 0L) / Math.max(1, segment.bytes())))
                .limit(maxVictims)
                .map(SegmentInfo::id)
                .toList();
        long generation = transactions.current().id();
        if (victims.isEmpty()) {
            return new Report(live, victims, generation);
        }
        victims.forEach(id -> pages.transition(id, SegmentState.COMPACTING, 0));
        Set<Integer> victimSet = Set.copyOf(victims);
        LongPredicate moving = pageId -> victimSet.contains(PageId.segmentOf(pageId));
        long retiredAt = transactions.relocate(moving);
        victims.forEach(id -> pages.transition(id, SegmentState.RETIRED, retiredAt));
        return new Report(live, victims, retiredAt);
    }

    public List<Integer> reclaim() {
        OptionalLong pinned = transactions.oldestPinned();
        long oldestRetained = transactions.history().stream().mapToLong(Generation::id).min().orElse(Long.MAX_VALUE);
        List<Integer> reclaimable = pages.segments().stream()
                .filter(segment -> segment.state() == SegmentState.RETIRED)
                .filter(segment -> pinned.isEmpty() || pinned.getAsLong() >= segment.retiredAt())
                .filter(segment -> oldestRetained >= segment.retiredAt())
                .map(SegmentInfo::id)
                .toList();
        reclaimable.forEach(pages::delete);
        return reclaimable;
    }
}

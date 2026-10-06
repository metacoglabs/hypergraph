package io.hstore.engine;

import io.hstore.engine.catalog.Slot;
import io.hstore.engine.txn.CrashPoint;
import io.hstore.engine.txn.Derivation;
import io.hstore.engine.txn.Durability;
import io.hstore.engine.txn.WalMode;

import java.util.List;

public record EngineOptions(
        int pageSize,
        int pagesPerSegment,
        long walSegmentBytes,
        long cacheBytes,
        Durability durability,
        WalMode walMode,
        int historyLimit,
        long checkpointWalBytes,
        double compactionLiveRatio,
        long feedRetention,
        List<Slot<?>> slots,
        List<Derivation> derivations,
        CrashPoint.Injector faults) {

    public EngineOptions {
        if (Integer.bitCount(pageSize) != 1 || pageSize < 1024 || pageSize > 1 << 20) {
            throw new IllegalArgumentException("page size must be a power of two between 1 KiB and 1 MiB: " + pageSize);
        }
        if (compactionLiveRatio <= 0 || compactionLiveRatio >= 1) {
            throw new IllegalArgumentException("compaction live ratio must be between 0 and 1: " + compactionLiveRatio);
        }
        if (cacheBytes < 1) {
            throw new IllegalArgumentException("node cache must be at least one byte: " + cacheBytes);
        }
        if (feedRetention < 1) {
            throw new IllegalArgumentException("feed retention must keep at least one generation: " + feedRetention);
        }
        slots = List.copyOf(slots);
        derivations = List.copyOf(derivations);
    }

    public static EngineOptions defaults() {
        return new EngineOptions(16 * 1024, 16 * 1024, 64L << 20, 256L << 20, Durability.SYNC, WalMode.PAGE_REFERENCES, 64,
                256L << 20, 0.5, 100_000, List.of(), List.of(), CrashPoint.Injector.NONE);
    }

    public EngineOptions withPageSize(int bytes) {
        return new EngineOptions(bytes, pagesPerSegment, walSegmentBytes, cacheBytes, durability, walMode, historyLimit,
                checkpointWalBytes, compactionLiveRatio, feedRetention, slots, derivations, faults);
    }

    public EngineOptions withWalSegmentBytes(long bytes) {
        return new EngineOptions(pageSize, pagesPerSegment, bytes, cacheBytes, durability, walMode, historyLimit,
                checkpointWalBytes, compactionLiveRatio, feedRetention, slots, derivations, faults);
    }

    public EngineOptions withPagesPerSegment(int pages) {
        return new EngineOptions(pageSize, pages, walSegmentBytes, cacheBytes, durability, walMode, historyLimit,
                checkpointWalBytes, compactionLiveRatio, feedRetention, slots, derivations, faults);
    }

    public EngineOptions withDurability(Durability mode) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, cacheBytes, mode, walMode, historyLimit,
                checkpointWalBytes, compactionLiveRatio, feedRetention, slots, derivations, faults);
    }

    public EngineOptions withWalMode(WalMode mode) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, cacheBytes, durability, mode, historyLimit,
                checkpointWalBytes, compactionLiveRatio, feedRetention, slots, derivations, faults);
    }

    public EngineOptions withHistoryLimit(int generations) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, cacheBytes, durability, walMode, generations,
                checkpointWalBytes, compactionLiveRatio, feedRetention, slots, derivations, faults);
    }

    public EngineOptions withCheckpointWalBytes(long bytes) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, cacheBytes, durability, walMode, historyLimit,
                bytes, compactionLiveRatio, feedRetention, slots, derivations, faults);
    }

    public EngineOptions withCacheBytes(long bytes) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, bytes, durability, walMode, historyLimit,
                checkpointWalBytes, compactionLiveRatio, feedRetention, slots, derivations, faults);
    }

    public EngineOptions withCompactionLiveRatio(double ratio) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, cacheBytes, durability, walMode, historyLimit,
                checkpointWalBytes, ratio, feedRetention, slots, derivations, faults);
    }

    public EngineOptions withFeedRetention(long generations) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, cacheBytes, durability, walMode, historyLimit,
                checkpointWalBytes, compactionLiveRatio, generations, slots, derivations, faults);
    }

    public EngineOptions withExtensions(List<Slot<?>> extensionSlots, List<Derivation> extensionDerivations) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, cacheBytes, durability, walMode, historyLimit,
                checkpointWalBytes, compactionLiveRatio, feedRetention, extensionSlots, extensionDerivations, faults);
    }

    public EngineOptions withFaults(CrashPoint.Injector injector) {
        return new EngineOptions(pageSize, pagesPerSegment, walSegmentBytes, cacheBytes, durability, walMode, historyLimit,
                checkpointWalBytes, compactionLiveRatio, feedRetention, slots, derivations, injector);
    }
}

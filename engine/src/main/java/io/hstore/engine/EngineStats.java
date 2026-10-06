// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine;

import io.hstore.engine.page.SegmentInfo;

import java.util.List;

public record EngineStats(
        long generation,
        long commits,
        long rebases,
        long conflicts,
        long pagesRead,
        long pagesWritten,
        long dataBytesWritten,
        long cacheHits,
        long cacheMisses,
        long walBytes,
        int walSegments,
        long feedBytes,
        List<SegmentInfo> segments) {

    public double cacheHitRate() {
        long total = cacheHits + cacheMisses;
        return total == 0 ? 0 : (double) cacheHits / total;
    }
}

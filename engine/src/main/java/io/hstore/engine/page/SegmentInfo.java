// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.page;

public record SegmentInfo(int id, SegmentState state, long pages, long units, long retiredAt) {

    public SegmentInfo withState(SegmentState next, long at) {
        if (!state.canTransitionTo(next)) {
            throw new IllegalStateException("segment " + id + " cannot move from " + state + " to " + next);
        }
        return new SegmentInfo(id, next, pages, units, next == SegmentState.RETIRED ? at : retiredAt);
    }

    public SegmentInfo withAllocation(long pageCount, long unitCount) {
        return new SegmentInfo(id, state, pageCount, unitCount, retiredAt);
    }

    public long bytes() {
        return units * PageId.UNIT_BYTES;
    }
}

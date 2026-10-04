package io.hstore.engine.page;

public enum SegmentState {
    ACTIVE,
    SEALED,
    COMPACTING,
    RETIRED;

    public boolean canTransitionTo(SegmentState next) {
        return next.ordinal() == ordinal() + 1 || (this == COMPACTING && next == SEALED);
    }
}

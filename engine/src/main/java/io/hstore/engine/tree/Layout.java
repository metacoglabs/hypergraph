package io.hstore.engine.tree;

import io.hstore.engine.page.PageHeader;

public record Layout(int pageSize, int leafBudget, int maxFanout) {

    private static final int RESERVED = 32;

    public static Layout of(int pageSize) {
        int body = pageSize - PageHeader.SIZE - Summary.MAX_ENCODED_BYTES - RESERVED;
        int fanout = body / (TreeSchema.KEY_BYTES + Ref.maxEncodedSize());
        if (fanout < 4) {
            throw new IllegalArgumentException("page size too small: " + pageSize);
        }
        return new Layout(pageSize, body, fanout);
    }

    public int maxValueBytes() {
        return leafBudget / 2 - TreeSchema.KEY_BYTES;
    }

    boolean leafUnderfull(Leaf leaf) {
        return leaf.bytes() < leafBudget / 4;
    }

    boolean branchUnderfull(Branch branch) {
        return branch.size() < Math.max(2, maxFanout / 4);
    }
}

package io.hstore.engine.topology;

public sealed interface MemberChange {

    long edge();

    long member();

    record Added(long edge, Incidence incidence, long locator) implements MemberChange {
        @Override
        public long member() {
            return incidence.member();
        }
    }

    record Removed(long edge, Incidence incidence, long locator) implements MemberChange {
        @Override
        public long member() {
            return incidence.member();
        }
    }

    record Updated(long edge, Incidence before, Incidence after, long locatorBefore, long locatorAfter) implements MemberChange {
        @Override
        public long member() {
            return after.member();
        }
    }
}

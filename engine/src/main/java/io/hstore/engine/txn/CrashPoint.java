package io.hstore.engine.txn;

public enum CrashPoint {
    PAGE,
    WAL_APPEND,
    DATA_WRITE,
    COMMIT_APPEND,
    DATA_SYNC,
    WAL_SYNC,
    CATALOG_PUBLISH,
    COMPACTION_MOVE,
    COMPACTION_RETIRE;

    @FunctionalInterface
    public interface Injector {
        Injector NONE = _ -> {
        };

        void reach(CrashPoint point);
    }

    public static final class SimulatedCrash extends Error {
        public SimulatedCrash(CrashPoint point) {
            super("simulated crash at " + point, null, false, false);
        }
    }
}

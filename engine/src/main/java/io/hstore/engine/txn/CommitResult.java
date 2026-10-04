package io.hstore.engine.txn;

public record CommitResult(long txnId, long generation, Outcome outcome, long pagesWritten, long walBytes, int memberChanges, int slotChanges) {

    public enum Outcome { COMMITTED, REBASED, DUPLICATE, READ_ONLY }

    public boolean duplicate() {
        return outcome == Outcome.DUPLICATE;
    }
}

package io.hstore.engine.wal;

import io.hstore.engine.catalog.Branch;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.Ref;

public sealed interface WalRecord {

    long txnId();

    record Begin(long txnId, int branch, long baseGeneration) implements WalRecord {
    }

    record Page(long txnId, long pageId, long address, byte[] image) implements WalRecord {
    }

    record PageRef(long txnId, long pageId, long address, int length, int checksum) implements WalRecord {
    }

    record Root(long txnId, int branch, int slot, Ref root) implements WalRecord {
    }

    record BranchMeta(long txnId, int id, String name, int parent, long baseGeneration, long createdAt, Branch.State state)
            implements WalRecord {
    }

    record Feed(long txnId, byte[] payload) implements WalRecord {
    }

    record Commit(long txnId, long generation, long wallTime, long nextAtom, long nextTxn) implements WalRecord {
    }

    record Abort(long txnId) implements WalRecord {
    }

    record Checkpoint(long txnId, long generation, long lsn) implements WalRecord {
    }

    static int typeOf(WalRecord record) {
        return switch (record) {
            case Begin _ -> 1;
            case Page _ -> 2;
            case Root _ -> 3;
            case BranchMeta _ -> 4;
            case Feed _ -> 5;
            case Commit _ -> 6;
            case Abort _ -> 7;
            case Checkpoint _ -> 8;
            case PageRef _ -> 9;
        };
    }

    static void writePayload(ByteCursor out, WalRecord record) {
        switch (record) {
            case Begin(long _, int branch, long base) -> out.putVarInt(branch).putVarLong(base);
            case Page(long _, long pageId, long address, byte[] image) -> out.putLong(pageId).putLong(address).putBlob(image);
            case Root(long _, int branch, int slot, Ref root) -> {
                out.putVarInt(branch).putVarInt(slot);
                Ref.write(out, root);
            }
            case BranchMeta(long _, int id, String name, int parent, long base, long created, Branch.State state) ->
                    out.putVarInt(id).putString(name).putVarInt(parent).putVarLong(base).putVarLong(created).putByte(state.ordinal());
            case Feed(long _, byte[] payload) -> out.putBlob(payload);
            case Commit(long _, long generation, long wallTime, long nextAtom, long nextTxn) ->
                    out.putVarLong(generation).putVarLong(wallTime).putVarLong(nextAtom).putVarLong(nextTxn);
            case Abort _ -> {
            }
            case Checkpoint(long _, long generation, long lsn) -> out.putVarLong(generation).putVarLong(lsn);
            case PageRef(long _, long pageId, long address, int length, int checksum) -> out.putLong(pageId).putLong(address).putVarInt(length).putInt(checksum);
        }
    }

    static WalRecord readPayload(int type, long txnId, ByteCursor in) {
        return switch (type) {
            case 1 -> new Begin(txnId, in.getVarInt(), in.getVarLong());
            case 2 -> new Page(txnId, in.getLong(), in.getLong(), in.getBlob());
            case 3 -> new Root(txnId, in.getVarInt(), in.getVarInt(), Ref.read(in));
            case 4 -> new BranchMeta(txnId, in.getVarInt(), in.getString(), in.getVarInt(), in.getVarLong(), in.getVarLong(),
                    Branch.State.values()[in.getUnsignedByte()]);
            case 5 -> new Feed(txnId, in.getBlob());
            case 6 -> new Commit(txnId, in.getVarLong(), in.getVarLong(), in.getVarLong(), in.getVarLong());
            case 7 -> new Abort(txnId);
            case 8 -> new Checkpoint(txnId, in.getVarLong(), in.getVarLong());
            case 9 -> new PageRef(txnId, in.getLong(), in.getLong(), in.getVarInt(), in.getInt());
            default -> throw new IllegalStateException("unknown WAL record type " + type);
        };
    }
}

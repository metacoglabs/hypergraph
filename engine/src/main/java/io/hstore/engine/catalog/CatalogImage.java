// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.catalog;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.page.SegmentInfo;
import io.hstore.engine.page.SegmentState;
import io.hstore.engine.tree.Ref;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public record CatalogImage(Generation current, List<Generation> history, List<SegmentInfo> segments, long checkpointLsn, long feedEnd) {

    public CatalogImage {
        history = List.copyOf(history);
        segments = List.copyOf(segments);
    }

    public static CatalogImage initial() {
        return new CatalogImage(Generation.initial(), List.of(), List.of(), 0, 0);
    }

    void writeTo(ByteCursor out) {
        writeGeneration(out, current);
        out.putVarInt(history.size());
        history.forEach(generation -> writeGeneration(out, generation));
        out.putVarInt(segments.size());
        segments.forEach(segment -> out.putVarInt(segment.id()).putByte(segment.state().ordinal())
                .putVarLong(segment.pages()).putVarLong(segment.units()).putVarLong(segment.retiredAt()));
        out.putVarLong(checkpointLsn).putVarLong(feedEnd);
    }

    static CatalogImage readFrom(ByteCursor in) {
        Generation current = readGeneration(in);
        int historySize = in.getVarInt();
        List<Generation> history = new ArrayList<>(historySize);
        for (int i = 0; i < historySize; i++) {
            history.add(readGeneration(in));
        }
        int segmentCount = in.getVarInt();
        List<SegmentInfo> segments = new ArrayList<>(segmentCount);
        for (int i = 0; i < segmentCount; i++) {
            segments.add(new SegmentInfo(in.getVarInt(), SegmentState.values()[in.getUnsignedByte()], in.getVarLong(), in.getVarLong(),
                    in.getVarLong()));
        }
        return new CatalogImage(current, history, segments, in.getVarLong(), in.getVarLong());
    }

    private static void writeGeneration(ByteCursor out, Generation generation) {
        out.putVarLong(generation.id()).putVarLong(generation.wallTime()).putVarLong(generation.txnId())
                .putVarLong(generation.nextAtom()).putVarLong(generation.nextTxn())
                .putVarInt(generation.branches().size());
        for (Branch branch : generation.branches().values()) {
            out.putVarInt(branch.id()).putString(branch.name()).putVarInt(branch.parent())
                    .putVarLong(branch.baseGeneration()).putVarLong(branch.createdAt()).putByte(branch.state().ordinal())
                    .putVarInt(branch.roots().roots().size());
            branch.roots().roots().forEach((slot, ref) -> {
                out.putVarInt(slot);
                Ref.write(out, ref);
            });
        }
    }

    private static Generation readGeneration(ByteCursor in) {
        long id = in.getVarLong();
        long wallTime = in.getVarLong();
        long txnId = in.getVarLong();
        long nextAtom = in.getVarLong();
        long nextTxn = in.getVarLong();
        int branchCount = in.getVarInt();
        Map<Integer, Branch> branches = new TreeMap<>();
        for (int b = 0; b < branchCount; b++) {
            int branchId = in.getVarInt();
            String name = in.getString();
            int parent = in.getVarInt();
            long base = in.getVarLong();
            long created = in.getVarLong();
            Branch.State state = Branch.State.values()[in.getUnsignedByte()];
            int rootCount = in.getVarInt();
            Map<Integer, Ref> roots = new TreeMap<>();
            for (int r = 0; r < rootCount; r++) {
                roots.put(in.getVarInt(), Ref.read(in));
            }
            branches.put(branchId, new Branch(branchId, name, parent, base, created, state, new RootVector(roots)));
        }
        return new Generation(id, wallTime, txnId, nextAtom, nextTxn, branches);
    }
}

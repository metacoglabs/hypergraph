// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.feed;

import io.hstore.engine.catalog.Slot;
import io.hstore.engine.catalog.SlotChange;
import io.hstore.engine.catalog.SlotRegistry;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.IncidenceCodec;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.ValueCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class FeedCodec {

    private static final long[] NO_KEYS = new long[1];

    private final SlotRegistry slots;

    public FeedCodec(SlotRegistry slots) {
        this.slots = slots;
    }

    public byte[] encode(CommitEvent event) {
        ByteCursor out = ByteCursor.growable(256);
        out.putVarLong(event.generation()).putVarLong(event.txnId()).putVarLong(event.wallTime()).putVarInt(event.branch());
        out.putVarInt(event.members().size());
        for (MemberChange change : event.members()) {
            switch (change) {
                case MemberChange.Added(long edge, Incidence incidence, long locator) -> {
                    out.putByte(0).putVarLong(edge).putSignedVarLong(locator);
                    writeIncidence(out, incidence);
                }
                case MemberChange.Removed(long edge, Incidence incidence, long locator) -> {
                    out.putByte(1).putVarLong(edge).putSignedVarLong(locator);
                    writeIncidence(out, incidence);
                }
                case MemberChange.Updated(long edge, Incidence before, Incidence after, long from, long to) -> {
                    out.putByte(2).putVarLong(edge).putSignedVarLong(from).putSignedVarLong(to);
                    writeIncidence(out, before);
                    writeIncidence(out, after);
                }
            }
        }
        out.putVarInt(event.slots().size());
        event.slots().forEach(change -> writeSlotChange(out, change));
        return out.toByteArray();
    }

    public CommitEvent decode(byte[] bytes) {
        ByteCursor in = ByteCursor.wrap(bytes);
        long generation = in.getVarLong();
        long txn = in.getVarLong();
        long wallTime = in.getVarLong();
        int branch = in.getVarInt();
        int memberCount = in.getVarInt();
        List<MemberChange> members = new ArrayList<>(memberCount);
        for (int i = 0; i < memberCount; i++) {
            int tag = in.getUnsignedByte();
            long edge = in.getVarLong();
            members.add(switch (tag) {
                case 0 -> {
                    long locator = in.getSignedVarLong();
                    yield new MemberChange.Added(edge, readIncidence(in), locator);
                }
                case 1 -> {
                    long locator = in.getSignedVarLong();
                    yield new MemberChange.Removed(edge, readIncidence(in), locator);
                }
                default -> {
                    long from = in.getSignedVarLong();
                    long to = in.getSignedVarLong();
                    yield new MemberChange.Updated(edge, readIncidence(in), readIncidence(in), from, to);
                }
            });
        }
        int slotCount = in.getVarInt();
        List<SlotChange<?>> slotChanges = new ArrayList<>(slotCount);
        for (int i = 0; i < slotCount; i++) {
            slotChanges.add(readSlotChange(in, slots.slot(in.getVarInt())));
        }
        return new CommitEvent(generation, txn, wallTime, branch, members, slotChanges);
    }

    private static void writeIncidence(ByteCursor out, Incidence incidence) {
        IncidenceCodec.SEQUENCED.encode(out, NO_KEYS, List.of(incidence));
    }

    private static Incidence readIncidence(ByteCursor in) {
        Object[] decoded = new Object[1];
        IncidenceCodec.SEQUENCED.decode(in, NO_KEYS, decoded);
        return (Incidence) decoded[0];
    }

    private static <V> void writeSlotChange(ByteCursor out, SlotChange<V> change) {
        out.putVarInt(change.slot().id()).putSignedVarLong(change.key())
                .putByte((change.before().isPresent() ? 1 : 0) | (change.after().isPresent() ? 2 : 0));
        ValueCodec<V> codec = change.slot().schema().codec();
        long[] keys = {change.key()};
        change.before().ifPresent(value -> codec.encode(out, keys, List.of(detached(codec, value))));
        change.after().ifPresent(value -> codec.encode(out, keys, List.of(detached(codec, value))));
    }

    private static <V> V detached(ValueCodec<V> codec, V value) {
        return codec.holdsRefs() ? codec.mapRefs(value, (_, _) -> Ref.EMPTY) : value;
    }

    private static <V> SlotChange<V> readSlotChange(ByteCursor in, Slot<V> slot) {
        long key = in.getSignedVarLong();
        int flags = in.getUnsignedByte();
        long[] keys = {key};
        Optional<V> before = (flags & 1) != 0 ? Optional.of(readValue(in, slot, keys)) : Optional.empty();
        Optional<V> after = (flags & 2) != 0 ? Optional.of(readValue(in, slot, keys)) : Optional.empty();
        return new SlotChange<>(slot, key, before, after);
    }

    private static <V> V readValue(ByteCursor in, Slot<V> slot, long[] keys) {
        Object[] decoded = new Object[1];
        slot.schema().codec().decode(in, keys, decoded);
        return slot.schema().cast(decoded[0]);
    }
}

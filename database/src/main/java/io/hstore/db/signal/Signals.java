package io.hstore.db.signal;

import io.hstore.db.Reader;
import io.hstore.db.Writer;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.catalog.SlotChange;
import io.hstore.engine.index.IndexValues.Marker;
import io.hstore.engine.index.IndexValues;
import io.hstore.engine.index.PostingIndex;
import io.hstore.engine.index.Postings;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.tree.Entry;
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;
import io.hstore.engine.txn.Derivation;
import io.hstore.engine.txn.Workspace;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class Signals {

    public record Signal(long atom, long source, String clock, long from, long to, long resolution, String schema) {
        public boolean overlaps(long start, long end) {
            return from < end && start < to;
        }
    }

    public record Resolved(long signal, Signal descriptor) {
    }

    private static final ValueCodec<Signal> CODEC = ValueCodec.rows(
            signal -> 60 + ByteCursor.stringSize(signal.clock()) + ByteCursor.stringSize(signal.schema()),
            (out, signal) -> out.putVarLong(signal.atom()).putVarLong(signal.source()).putString(signal.clock())
                    .putSignedVarLong(signal.from()).putSignedVarLong(signal.to()).putVarLong(signal.resolution()).putString(signal.schema()),
            in -> new Signal(in.getVarLong(), in.getVarLong(), in.getString(), in.getSignedVarLong(), in.getSignedVarLong(),
                    in.getVarLong(), in.getString()));

    public static final Slot<Signal> SIGNALS = Slot.primary(40, "signals",
            new TreeSchema<>(76, "signals", FingerprintMode.SET, CODEC,
                    EntryMeasure.keyed(signal -> Hashing.of(signal.atom(), signal.source(), Hashing.of(signal.clock()),
                            signal.from(), signal.to(), signal.resolution(), Hashing.of(signal.schema())))));

    public static final PostingIndex<Marker> BY_ATOM = PostingIndex.of(77, 78, "signals-by-atom", IndexValues.MARKER, _ -> 1, 16);

    public static final Slot<Postings<Marker>> SIGNAL_INDEX = Slot.derived(41, "signals-by-atom", BY_ATOM.schema());

    public static final List<Slot<?>> SLOTS = List.of(SIGNALS, SIGNAL_INDEX);

    public static final Derivation INDEXING = new Derivation() {
        @Override
        public void onSlot(SlotChange<?> change, Workspace workspace) {
            if (change.slot() != SIGNALS) {
                return;
            }
            change.before().map(Signal.class::cast).ifPresent(signal -> workspace.replace(SIGNAL_INDEX,
                    BY_ATOM.remove(workspace.tree(SIGNAL_INDEX), workspace.scope(), signal.atom(), change.key())));
            change.after().map(Signal.class::cast).ifPresent(signal -> workspace.replace(SIGNAL_INDEX,
                    BY_ATOM.add(workspace.tree(SIGNAL_INDEX), workspace.scope(), signal.atom(), change.key(), Marker.PRESENT)));
        }
    };

    private Signals() {
    }

    public static long register(Writer writer, Signal signal) {
        writer.principal().requireWrite();
        writer.require(signal.atom());
        long id = writer.database().engine().transactions().allocateAtom();
        writer.transaction().put(SIGNALS, id, signal);
        return id;
    }

    public static Stream<Resolved> of(Reader reader, long atom, long from, long to) {
        if (!reader.visible(atom)) {
            return Stream.empty();
        }
        return BY_ATOM.stream(reader.view().scan(SIGNAL_INDEX), atom)
                .map(Entry::key)
                .flatMap(id -> reader.view().get(SIGNALS, id).filter(signal -> signal.overlaps(from, to))
                        .map(signal -> new Resolved(id, signal)).stream());
    }

    public static Map<Long, List<Resolved>> resolve(Reader reader, Collection<Long> edges, long from, long to) {
        return edges.stream()
                .flatMap(edge -> reader.edge(edge).overlapping(from, to))
                .map(Incidence::member)
                .distinct()
                .collect(Collectors.toMap(atom -> atom, atom -> of(reader, atom, from, to).toList(), (a, _) -> a, TreeMap::new));
    }
}

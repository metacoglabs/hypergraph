package io.hstore.db.temporal;

import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Reader;
import io.hstore.engine.catalog.AtomRecord.EdgeRecord;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.catalog.Generation;
import io.hstore.engine.topology.Hyperedge;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeDiff;
import io.hstore.engine.txn.IncidentEdge;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;
import java.util.stream.Stream;

public final class Temporal {

    public sealed interface Delta {
        long atom();

        record AtomCreated(long atom, AtomRecord record) implements Delta {
        }

        record AtomDeleted(long atom, AtomRecord record) implements Delta {
        }

        record MembersChanged(long atom, List<MemberChange> changes) implements Delta {
        }
    }

    private Temporal() {
    }

    public static Optional<Generation> resolve(HypergraphDatabase database, long wallTime) {
        return database.engine().transactions().generationAsOf(wallTime);
    }

    public static LongStream activeEdges(Reader reader, long atom, long instant) {
        if (!reader.visible(atom)) {
            return LongStream.empty();
        }
        return reader.view().incident(atom)
                .filter(incident -> reader.view().incidence(incident.edge(), atom).map(found -> found.validAt(instant)).orElse(false))
                .mapToLong(IncidentEdge::edge);
    }

    public static Stream<Delta> diff(Reader before, Reader after) {
        Tree<AtomRecord> left = before.view().scan(EngineSlots.CATALOG);
        Tree<AtomRecord> right = after.view().scan(EngineSlots.CATALOG);
        return TreeDiff.diff(left, right).flatMap(delta -> switch (delta) {
            case TreeDiff.Delta.Added<AtomRecord>(long atom, AtomRecord record) -> Stream.concat(
                    Stream.of(new Delta.AtomCreated(atom, record)), membersOf(after, atom, record));
            case TreeDiff.Delta.Removed<AtomRecord>(long atom, AtomRecord record) -> Stream.of(new Delta.AtomDeleted(atom, record));
            case TreeDiff.Delta.Changed<AtomRecord>(long atom, AtomRecord was, AtomRecord now) -> changedMembers(before, after, atom, was, now);
        });
    }

    private static Stream<Delta> membersOf(Reader reader, long atom, AtomRecord record) {
        if (!(record instanceof EdgeRecord edge) || edge.members() instanceof Ref.Empty) {
            return Stream.empty();
        }
        Hyperedge empty = Hyperedge.empty(atom, edge.kind(), reader.view().source());
        return changes(empty, reader.edge(atom), atom);
    }

    private static Stream<Delta> changedMembers(Reader before, Reader after, long atom, AtomRecord was, AtomRecord now) {
        if (was instanceof EdgeRecord && now instanceof EdgeRecord) {
            return changes(before.edge(atom), after.edge(atom), atom);
        }
        return Stream.empty();
    }

    private static Stream<Delta> changes(Hyperedge from, Hyperedge to, long atom) {
        List<MemberChange> changes = new ArrayList<>();
        Hyperedge.diff(from, to, changes::add);
        return changes.isEmpty() ? Stream.empty() : Stream.of(new Delta.MembersChanged(atom, changes));
    }
}

package io.hstore.engine.txn;

import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.AtomRecord.EdgeRecord;
import io.hstore.engine.catalog.AtomRecord.NodeRecord;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.topology.Hyperedge;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.tree.Entry;
import io.hstore.engine.tree.Ref;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

public sealed interface Op {

    void apply(Workspace workspace);

    record CreateAtom(long id, AtomRecord record) implements Op {
        @Override
        public void apply(Workspace workspace) {
            if (workspace.get(EngineSlots.CATALOG, id).isPresent()) {
                throw HStoreException.conflict("atom id " + id + " is already allocated");
            }
            if (record instanceof NodeRecord node && node.hasCanonicalKey()) {
                long holder = Views.resolve(workspace.tree(EngineSlots.CANONICAL), node.tenant(), node.type(), node.canonicalKey()).orElse(id);
                if (holder != id) {
                    String message = "canonical key '" + node.canonicalKey() + "' of type " + node.type() + " already names atom " + holder;
                    throw workspace.replaying() ? HStoreException.conflict(message) : HStoreException.invalid(message);
                }
            }
            if (record instanceof EdgeRecord edge && !(edge.members() instanceof Ref.Empty)) {
                throw HStoreException.invalid("hyperedges are created empty and populated through edge actions");
            }
            workspace.write(EngineSlots.CATALOG, id, _ -> Optional.of(record));
        }
    }

    record UpdateAtom(long id, UnaryOperator<AtomRecord> change) implements Op {
        @Override
        public void apply(Workspace workspace) {
            AtomRecord current = workspace.get(EngineSlots.CATALOG, id).orElseThrow(() -> workspace.missing(id));
            AtomRecord next = change.apply(current);
            if (next.getClass() != current.getClass() || next.type() != current.type() && current instanceof EdgeRecord) {
                throw HStoreException.invalid("an atom update may not change its kind or a hyperedge's type");
            }
            if (next instanceof EdgeRecord edge && current instanceof EdgeRecord was
                    && (!Ref.same(edge.members(), was.members()) || !Ref.same(edge.order(), was.order()))) {
                throw HStoreException.invalid("hyperedge topology changes only through edge actions");
            }
            workspace.write(EngineSlots.CATALOG, id, _ -> Optional.of(next));
        }
    }

    record DeleteAtom(long id) implements Op {
        @Override
        public void apply(Workspace workspace) {
            AtomRecord record = workspace.get(EngineSlots.CATALOG, id).orElseThrow(() -> workspace.missing(id));
            List<Long> incident = Views.incident(workspace.tree(EngineSlots.REVERSE), id).map(IncidentEdge::edge).toList();
            for (long edge : incident) {
                Hyperedge containing = workspace.edge(edge);
                workspace.store(containing.remove(workspace.scope(), id, workspace::emit));
            }
            if (record instanceof EdgeRecord) {
                Hyperedge edge = workspace.edge(id);
                List<Entry<Incidence>> members = edge.members().stream().toList();
                members.forEach(entry -> workspace.emit(new MemberChange.Removed(id, entry.value(), entry.key())));
                workspace.store(edge.withRoots(Ref.EMPTY, Ref.EMPTY));
            }
            workspace.write(EngineSlots.CATALOG, id, _ -> Optional.empty());
        }
    }

    record EdgeOp(long edge, EdgeAction action) implements Op {
        @Override
        public void apply(Workspace workspace) {
            Hyperedge current = workspace.edge(edge);
            workspace.store(action.apply(current, workspace.scope(), workspace::emit));
        }
    }

    record Apply(String description, Consumer<Workspace> action) implements Op {
        @Override
        public void apply(Workspace workspace) {
            action.accept(workspace);
        }
    }

    record SlotWrite<V>(Slot<V> slot, long key, Predicate<Optional<V>> precondition, UnaryOperator<Optional<V>> change) implements Op {
        public SlotWrite {
            if (!slot.isPrimary()) {
                throw new IllegalArgumentException("derived slot " + slot + " cannot be written directly");
            }
        }

        @Override
        public void apply(Workspace workspace) {
            Optional<V> current = workspace.get(slot, key);
            if (!precondition.test(current)) {
                String message = "precondition failed for " + slot + " key " + key;
                throw workspace.replaying() ? HStoreException.conflict(message) : HStoreException.invalid(message);
            }
            workspace.write(slot, key, change);
        }
    }
}

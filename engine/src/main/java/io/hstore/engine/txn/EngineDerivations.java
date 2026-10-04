package io.hstore.engine.txn;

import io.hstore.engine.catalog.AtomRecord.NodeRecord;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.catalog.SlotChange;
import io.hstore.engine.catalog.Symbol;
import io.hstore.engine.index.IndexValues.CanonicalName;
import io.hstore.engine.index.IndexValues.Incident;
import io.hstore.engine.index.IndexValues.Marker;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.MemberChange;

import java.util.List;
import java.util.Optional;

final class EngineDerivations {

    static final Derivation REVERSE_INCIDENCE = new Derivation() {
        @Override
        public void onMember(MemberChange change, Workspace workspace) {
            switch (change) {
                case MemberChange.Added(long edge, Incidence incidence, long locator) -> link(workspace, edge, incidence, locator);
                case MemberChange.Updated(long edge, Incidence before, Incidence after, long from, long to)
                        when before.roleSet() != after.roleSet() || from != to -> link(workspace, edge, after, to);
                case MemberChange.Updated _ -> {
                }
                case MemberChange.Removed(long edge, Incidence incidence, long _) -> workspace.replace(EngineSlots.REVERSE,
                        EngineSlots.REVERSE_INDEX.remove(workspace.tree(EngineSlots.REVERSE), workspace.scope(), incidence.member(), edge));
            }
        }

        private void link(Workspace workspace, long edge, Incidence incidence, long locator) {
            workspace.replace(EngineSlots.REVERSE, EngineSlots.REVERSE_INDEX.add(workspace.tree(EngineSlots.REVERSE),
                    workspace.scope(), incidence.member(), edge, new Incident(incidence.roleSet(), locator)));
        }
    };

    static final Derivation ATOM_INDEXES = new Derivation() {
        @Override
        public void onSlot(SlotChange<?> change, Workspace workspace) {
            if (change.slot() != EngineSlots.CATALOG) {
                return;
            }
            long atom = change.key();
            Optional<AtomRecord> before = change.before().map(AtomRecord.class::cast);
            Optional<AtomRecord> after = change.after().map(AtomRecord.class::cast);
            before.filter(old -> after.map(now -> typeKey(now) != typeKey(old)).orElse(true)).ifPresent(old ->
                    workspace.replace(EngineSlots.TYPES, EngineSlots.TYPE_INDEX.remove(workspace.tree(EngineSlots.TYPES), workspace.scope(), typeKey(old), atom)));
            after.filter(now -> before.map(old -> typeKey(now) != typeKey(old)).orElse(true)).ifPresent(now ->
                    workspace.replace(EngineSlots.TYPES, EngineSlots.TYPE_INDEX.add(workspace.tree(EngineSlots.TYPES), workspace.scope(), typeKey(now), atom, Marker.PRESENT)));
            Optional<CanonicalName> oldName = canonical(before);
            Optional<CanonicalName> newName = canonical(after);
            if (oldName.equals(newName)) {
                return;
            }
            oldName.ifPresent(name -> workspace.replace(EngineSlots.CANONICAL, EngineSlots.CANONICAL_INDEX.remove(
                    workspace.tree(EngineSlots.CANONICAL), workspace.scope(), EngineSlots.canonicalKey(name.tenant(), name.type(), name.key()), atom)));
            newName.ifPresent(name -> workspace.replace(EngineSlots.CANONICAL, EngineSlots.CANONICAL_INDEX.add(
                    workspace.tree(EngineSlots.CANONICAL), workspace.scope(), EngineSlots.canonicalKey(name.tenant(), name.type(), name.key()), atom, name)));
        }

        private long typeKey(AtomRecord record) {
            return EngineSlots.typeKey(record.tenant(), record.type());
        }

        private Optional<CanonicalName> canonical(Optional<AtomRecord> record) {
            return record.flatMap(value -> value instanceof NodeRecord node && node.hasCanonicalKey()
                    ? Optional.of(new CanonicalName(node.tenant(), node.type(), node.canonicalKey()))
                    : Optional.empty());
        }
    };

    static final Derivation SYMBOL_LOOKUP = new Derivation() {
        @Override
        public void onSlot(SlotChange<?> change, Workspace workspace) {
            if (change.slot() != EngineSlots.SYMBOLS) {
                return;
            }
            change.before().map(Symbol.class::cast).ifPresent(symbol -> workspace.replace(EngineSlots.SYMBOL_LOOKUP,
                    EngineSlots.SYMBOL_INDEX.remove(workspace.tree(EngineSlots.SYMBOL_LOOKUP), workspace.scope(), symbol.hash(), change.key())));
            change.after().map(Symbol.class::cast).ifPresent(symbol -> workspace.replace(EngineSlots.SYMBOL_LOOKUP,
                    EngineSlots.SYMBOL_INDEX.add(workspace.tree(EngineSlots.SYMBOL_LOOKUP), workspace.scope(), symbol.hash(), change.key(), Marker.PRESENT)));
        }
    };

    static final List<Derivation> ALL = List.of(REVERSE_INCIDENCE, ATOM_INDEXES, SYMBOL_LOOKUP);

    private EngineDerivations() {
    }
}

package io.hstore.engine.txn;

import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.AtomRecord.EdgeRecord;
import io.hstore.engine.catalog.AtomRecord;
import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.catalog.RootVector;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.catalog.SlotChange;
import io.hstore.engine.topology.Hyperedge;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.tree.NodeSource;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.WriteScope;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

public final class Workspace {

    @FunctionalInterface
    interface EdgeGuard {
        EdgeGuard NONE = (_, _) -> {
        };

        void verify(long edge, Workspace workspace);
    }

    private final NodeSource source;
    private final WriteScope scope;
    private final List<Derivation> derivations;
    private final RootVector base;
    private final EdgeGuard guard;
    private final boolean replaying;
    private final Map<Integer, Tree<?>> trees = new HashMap<>();
    private final List<MemberChange> memberChanges = new ArrayList<>();
    private final List<SlotChange<?>> slotChanges = new ArrayList<>();

    Workspace(NodeSource source, WriteScope scope, List<Derivation> derivations, RootVector base, EdgeGuard guard, boolean replaying) {
        this.source = source;
        this.scope = scope;
        this.derivations = derivations;
        this.base = base;
        this.guard = guard;
        this.replaying = replaying;
    }

    public WriteScope scope() {
        return scope;
    }

    public NodeSource source() {
        return source;
    }

    public boolean replaying() {
        return replaying;
    }

    @SuppressWarnings("unchecked")
    public <V> Tree<V> tree(Slot<V> slot) {
        return (Tree<V>) trees.computeIfAbsent(slot.id(), _ -> new Tree<>(slot.schema(), source, base.get(slot)));
    }

    public <V> Optional<V> get(Slot<V> slot, long key) {
        return tree(slot).get(key);
    }

    public <V> void write(Slot<V> slot, long key, UnaryOperator<Optional<V>> change) {
        Tree<V> tree = tree(slot);
        Optional<V> before = tree.get(key);
        Optional<V> after = change.apply(before);
        if (before.equals(after)) {
            return;
        }
        trees.put(slot.id(), tree.update(scope, key, _ -> after));
        if (slot.isPrimary()) {
            SlotChange<V> recorded = new SlotChange<>(slot, key, before, after);
            slotChanges.add(recorded);
            derivations.forEach(derivation -> derivation.onSlot(recorded, this));
        }
    }

    public <V> void replace(Slot<V> slot, Tree<V> tree) {
        if (slot.isPrimary()) {
            throw new IllegalStateException("primary slot " + slot + " must change through keyed writes");
        }
        trees.put(slot.id(), tree);
    }

    public Hyperedge edge(long id) {
        guard.verify(id, this);
        return switch (get(EngineSlots.CATALOG, id).orElseThrow(() -> missing(id))) {
            case EdgeRecord edge -> Hyperedge.of(id, edge.kind(), source, edge.members(), edge.order());
            case AtomRecord.NodeRecord _ -> throw HStoreException.invalid("atom " + id + " is a node, not a hyperedge");
        };
    }

    public void store(Hyperedge edge) {
        Tree<AtomRecord> catalog = tree(EngineSlots.CATALOG);
        EdgeRecord current = (EdgeRecord) catalog.get(edge.id()).orElseThrow(() -> missing(edge.id()));
        if (current.members() == edge.members().root() && current.order() == edge.order().root()) {
            return;
        }
        trees.put(EngineSlots.CATALOG.id(), catalog.put(scope, edge.id(), current.withRoots(edge.members().root(), edge.order().root())));
    }

    public void emit(MemberChange change) {
        memberChanges.add(change);
        derivations.forEach(derivation -> derivation.onMember(change, this));
    }

    HStoreException missing(long atom) {
        return replaying
                ? HStoreException.conflict("atom " + atom + " was removed by a concurrent transaction")
                : HStoreException.invalid("atom " + atom + " does not exist");
    }

    public RootVector roots() {
        RootVector roots = base;
        for (Map.Entry<Integer, Tree<?>> entry : trees.entrySet()) {
            roots = roots.with(entry.getKey(), entry.getValue().root());
        }
        return roots;
    }

    public RootVector rebasedOnto(RootVector latest) {
        RootVector roots = latest;
        for (Map.Entry<Integer, Tree<?>> entry : trees.entrySet()) {
            if (changed(entry)) {
                roots = roots.with(entry.getKey(), entry.getValue().root());
            }
        }
        return roots;
    }

    boolean untouchedSince(RootVector latest, boolean includingReads) {
        return trees.entrySet().stream()
                .filter(entry -> includingReads || changed(entry))
                .allMatch(entry -> Ref.same(base.get(entry.getKey()), latest.get(entry.getKey())));
    }

    private boolean changed(Map.Entry<Integer, Tree<?>> entry) {
        return !Ref.same(entry.getValue().root(), base.get(entry.getKey()));
    }

    public RootVector base() {
        return base;
    }

    public List<MemberChange> memberChanges() {
        return memberChanges;
    }

    public List<SlotChange<?>> slotChanges() {
        return slotChanges;
    }
}

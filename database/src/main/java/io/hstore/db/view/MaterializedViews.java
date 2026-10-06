// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.view;

import io.hstore.db.HypergraphDatabase;
import io.hstore.db.Reader;
import io.hstore.db.Writer;
import io.hstore.db.security.Principal;
import io.hstore.engine.HStoreException;
import io.hstore.engine.catalog.AtomRecord.EdgeRecord;
import io.hstore.engine.catalog.Branch;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.feed.ChangeFeed;
import io.hstore.engine.feed.CommitEvent;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.tree.Entry;
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.Tree;
import io.hstore.engine.tree.TreeAlgebra;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;
import io.hstore.engine.txn.IncidentEdge;
import io.hstore.engine.txn.TxnOptions;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

public final class MaterializedViews implements AutoCloseable {

    public enum Kind { DEGREE, CARDINALITY, ACTIVITY, OVERLAP_TOP_K }

    public enum Refresh { ON_DEMAND, CONTINUOUS }

    public record Descriptor(int id, String name, Kind kind, Refresh refresh, long parameter, long lastGeneration, int tenant) {
        long definitionHash() {
            return Hashing.of(id, Hashing.of(name), kind.ordinal(), refresh.ordinal(), parameter, tenant);
        }

        Descriptor advancedTo(long generation) {
            return new Descriptor(id, name, kind, refresh, parameter, generation, tenant);
        }
    }

    public record Reading(ViewCell cell, long staleness) {
    }

    private static final int KEY_BITS = 48;

    private static final ValueCodec<Descriptor> DESCRIPTOR_CODEC = ValueCodec.rows(
            descriptor -> 45 + ByteCursor.stringSize(descriptor.name()),
            (out, descriptor) -> out.putVarInt(descriptor.id()).putString(descriptor.name()).putByte(descriptor.kind().ordinal())
                    .putByte(descriptor.refresh().ordinal()).putSignedVarLong(descriptor.parameter()).putVarLong(descriptor.lastGeneration())
                    .putVarInt(descriptor.tenant()),
            in -> new Descriptor(in.getVarInt(), in.getString(), Kind.values()[in.getUnsignedByte()],
                    Refresh.values()[in.getUnsignedByte()], in.getSignedVarLong(), in.getVarLong(), in.getVarInt()));

    public static final Slot<Descriptor> VIEWS = Slot.primary(42, "views",
            new TreeSchema<>(79, "views", FingerprintMode.SET, DESCRIPTOR_CODEC,
                    EntryMeasure.keyed(descriptor -> Hashing.of(descriptor.definitionHash(), descriptor.lastGeneration()))));

    public static final Slot<ViewCell> VIEW_DATA = Slot.primary(43, "view-data",
            new TreeSchema<>(80, "view-data", FingerprintMode.SET, ViewCell.CODEC, EntryMeasure.keyed(ViewCell::hash)));

    public static final List<Slot<?>> SLOTS = List.of(VIEWS, VIEW_DATA);

    private final HypergraphDatabase database;
    private final ChangeFeed.Subscription subscription;
    private final ChangeFeed.Hold hold;

    public MaterializedViews(HypergraphDatabase database) {
        this.database = database;
        this.subscription = database.engine().feed().subscribe(database.engine().transactions().current().id(), this::onCommit);
        this.hold = database.engine().feed().hold(() -> database.read(reader ->
                reader.view().scan(VIEWS).values().mapToLong(Descriptor::lastGeneration).min().orElse(Long.MAX_VALUE)));
    }

    private static long cellKey(int view, long key) {
        if (key < 0 || key >= 1L << KEY_BITS) {
            throw HStoreException.invalid("view key " + key + " exceeds " + KEY_BITS + " bits");
        }
        return ((long) view << KEY_BITS) | key;
    }

    public Descriptor create(Principal principal, String name, Kind kind, Refresh refresh, long parameter) {
        return database.write(TxnOptions.defaults(), principal, writer -> {
            writer.principal().requireAdmin();
            if (find(writer, name).isPresent()) {
                throw HStoreException.invalid("view '" + name + "' already exists");
            }
            int id = writer.view().scan(VIEWS).last().map(entry -> (int) entry.key() + 1).orElse(1);
            Descriptor descriptor = new Descriptor(id, name, kind, refresh, parameter, writer.generation(), writer.tenant());
            writer.transaction().put(VIEWS, id, descriptor);
            build(writer, descriptor);
            return descriptor;
        });
    }

    public Optional<Descriptor> find(Reader reader, String name) {
        return list(reader).stream().filter(descriptor -> descriptor.name().equals(name)).findFirst();
    }

    public List<Descriptor> list(Reader reader) {
        return reader.view().scan(VIEWS).values().filter(descriptor -> descriptor.tenant() == reader.tenant()).toList();
    }

    public Descriptor refresh(Principal principal, String name) {
        return database.write(TxnOptions.defaults(), principal, writer ->
                refresh(writer, find(writer, name).orElseThrow(() -> HStoreException.invalid("unknown view '" + name + "'"))));
    }

    private Descriptor refresh(Writer writer, Descriptor descriptor) {
        long target = Math.min(database.engine().feed().lastGeneration(), writer.generation());
        if (target <= descriptor.lastGeneration()) {
            return descriptor;
        }
        List<CommitEvent> events = database.engine().feed().replay(descriptor.lastGeneration())
                .takeWhile(event -> event.generation() <= target)
                .filter(event -> event.branch() == Branch.MAIN)
                .toList();
        apply(writer, descriptor, events);
        Descriptor advanced = descriptor.advancedTo(target);
        writer.transaction().put(VIEWS, descriptor.id(), advanced);
        return advanced;
    }

    private boolean owns(Writer writer, Descriptor descriptor, long atom) {
        return writer.view().get(VIEW_DATA, cellKey(descriptor.id(), atom)).isPresent() || inTenant(writer, descriptor, atom);
    }

    private static boolean inTenant(Writer writer, Descriptor descriptor, long atom) {
        return writer.view().atom(atom).map(record -> record.tenant() == descriptor.tenant()).orElse(false);
    }

    public Optional<Reading> read(Reader reader, String name, long key) {
        reader.principal().requireAdmin();
        Descriptor descriptor = find(reader, name).orElseThrow(() -> HStoreException.invalid("unknown view '" + name + "'"));
        return reader.view().get(VIEW_DATA, cellKey(descriptor.id(), key))
                .map(cell -> new Reading(cell, staleness(descriptor, reader.generation())));
    }

    public long staleness(Descriptor descriptor, long generation) {
        return database.engine().feed().replay(descriptor.lastGeneration())
                .takeWhile(event -> event.generation() <= generation)
                .filter(event -> event.branch() == Branch.MAIN && !event.members().isEmpty())
                .count();
    }

    public Stream<Entry<ViewCell>> scan(Reader reader, String name) {
        reader.principal().requireAdmin();
        Descriptor descriptor = find(reader, name).orElseThrow(() -> HStoreException.invalid("unknown view '" + name + "'"));
        long low = cellKey(descriptor.id(), 0);
        long high = cellKey(descriptor.id(), (1L << KEY_BITS) - 1);
        return reader.view().scan(VIEW_DATA).range(low, high)
                .map(entry -> new Entry<>(entry.key() & ((1L << KEY_BITS) - 1), entry.value()));
    }

    private void build(Writer writer, Descriptor descriptor) {
        switch (descriptor.kind()) {
            case DEGREE -> writer.view().atoms().filter(entry -> entry.value().tenant() == descriptor.tenant()).map(Entry::key).forEach(atom -> {
                long degree = writer.degree(atom);
                if (degree > 0) {
                    put(writer, descriptor, atom, new ViewCell.Count(degree));
                }
            });
            case CARDINALITY -> writer.view().atoms()
                    .filter(entry -> entry.value() instanceof EdgeRecord && entry.value().tenant() == descriptor.tenant())
                    .forEach(entry -> put(writer, descriptor, entry.key(), new ViewCell.Count(((EdgeRecord) entry.value()).cardinality())));
            case ACTIVITY -> apply(writer, descriptor, database.engine().feed().replay(0)
                    .filter(event -> event.branch() == Branch.MAIN && event.generation() <= descriptor.lastGeneration())
                    .toList());
            case OVERLAP_TOP_K -> writer.view().atoms()
                    .filter(entry -> entry.value() instanceof EdgeRecord && entry.value().tenant() == descriptor.tenant())
                    .map(Entry::key)
                    .toList()
                    .forEach(edge -> put(writer, descriptor, edge, topK(writer, edge, (int) descriptor.parameter())));
        }
    }

    private void apply(Writer writer, Descriptor descriptor, List<CommitEvent> events) {
        switch (descriptor.kind()) {
            case DEGREE -> deltas(events, MemberChange::member).forEach((atom, delta) -> {
                if (owns(writer, descriptor, atom)) {
                    adjust(writer, descriptor, atom, delta);
                }
            });
            case CARDINALITY -> deltas(events, MemberChange::edge).forEach((edge, delta) -> {
                if (owns(writer, descriptor, edge)) {
                    adjust(writer, descriptor, edge, delta);
                }
            });
            case ACTIVITY -> events.forEach(event -> {
                long bucket = event.wallTime() / Math.max(1, descriptor.parameter());
                List<MemberChange> changes = event.members().stream()
                        .filter(change -> inTenant(writer, descriptor, change.member()) || inTenant(writer, descriptor, change.edge()))
                        .toList();
                long added = changes.stream().filter(change -> change instanceof MemberChange.Added).count();
                long removed = changes.stream().filter(change -> change instanceof MemberChange.Removed).count();
                if (added + removed > 0) {
                    ViewCell.Activity current = writer.view().get(VIEW_DATA, cellKey(descriptor.id(), bucket))
                            .map(ViewCell.Activity.class::cast).orElse(new ViewCell.Activity(0, 0));
                    put(writer, descriptor, bucket, new ViewCell.Activity(current.added() + added, current.removed() + removed));
                }
            });
            case OVERLAP_TOP_K -> {
                Set<Long> affected = new HashSet<>();
                events.forEach(event -> event.members().forEach(change -> {
                    affected.add(change.edge());
                    writer.view().incident(change.member()).map(IncidentEdge::edge).forEach(affected::add);
                }));
                affected.forEach(edge -> {
                    if (writer.view().edge(edge).isPresent() && inTenant(writer, descriptor, edge)) {
                        put(writer, descriptor, edge, topK(writer, edge, (int) descriptor.parameter()));
                    } else {
                        writer.transaction().delete(VIEW_DATA, cellKey(descriptor.id(), edge));
                    }
                });
            }
        }
    }

    private static Map<Long, Long> deltas(List<CommitEvent> events, ToLongFunction<MemberChange> key) {
        Map<Long, Long> deltas = new HashMap<>();
        events.forEach(event -> event.members().forEach(change -> {
            long delta = switch (change) {
                case MemberChange.Added _ -> 1;
                case MemberChange.Removed _ -> -1;
                case MemberChange.Updated _ -> 0;
            };
            if (delta != 0) {
                deltas.merge(key.applyAsLong(change), delta, Long::sum);
            }
        }));
        return deltas;
    }

    private void adjust(Writer writer, Descriptor descriptor, long key, long delta) {
        long current = writer.view().get(VIEW_DATA, cellKey(descriptor.id(), key))
                .map(cell -> ((ViewCell.Count) cell).value()).orElse(0L);
        long next = current + delta;
        if (next == 0) {
            writer.transaction().delete(VIEW_DATA, cellKey(descriptor.id(), key));
        } else {
            put(writer, descriptor, key, new ViewCell.Count(next));
        }
    }

    private void put(Writer writer, Descriptor descriptor, long key, ViewCell cell) {
        writer.transaction().put(VIEW_DATA, cellKey(descriptor.id(), key), cell);
    }

    private static ViewCell.Ranked topK(Reader reader, long edge, int k) {
        Tree<?> membership = reader.edge(edge).membership();
        Set<Long> candidates = new HashSet<>();
        reader.edge(edge).stream().map(Incidence::member)
                .forEach(member -> reader.view().incident(member).map(IncidentEdge::edge).filter(other -> other != edge).forEach(candidates::add));
        return new ViewCell.Ranked(candidates.stream()
                .map(other -> new ViewCell.Neighbor(other, TreeAlgebra.countIntersect(membership, reader.edge(other).membership())))
                .filter(neighbor -> neighbor.overlap() > 0)
                .sorted(Comparator.comparingLong(ViewCell.Neighbor::overlap).reversed().thenComparingLong(ViewCell.Neighbor::edge))
                .limit(k)
                .toList());
    }

    private void onCommit(CommitEvent event) {
        if (event.branch() != Branch.MAIN || event.members().isEmpty()) {
            return;
        }
        try {
            List<Descriptor> continuous = database.read(reader -> reader.view().scan(VIEWS).values()
                    .filter(descriptor -> descriptor.refresh() == Refresh.CONTINUOUS)
                    .toList());
            continuous.forEach(descriptor -> database.write(TxnOptions.defaults(), Principal.SYSTEM.inTenant(descriptor.tenant()), writer ->
                    refresh(writer, writer.view().get(VIEWS, descriptor.id()).orElse(descriptor))));
        } catch (RuntimeException e) {
            System.getLogger("hstore.views").log(System.Logger.Level.WARNING, "continuous view refresh failed", e);
        }
    }

    @Override
    public void close() {
        subscription.close();
        hold.close();
    }
}

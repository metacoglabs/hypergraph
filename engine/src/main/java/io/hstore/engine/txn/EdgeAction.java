package io.hstore.engine.txn;

import io.hstore.engine.topology.Hyperedge;
import io.hstore.engine.topology.Incidence;
import io.hstore.engine.topology.MemberChange;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.WriteScope;

import java.util.OptionalLong;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

public sealed interface EdgeAction {

    Hyperedge apply(Hyperedge edge, WriteScope scope, Consumer<MemberChange> sink);

    OptionalLong member();

    record Insert(Incidence incidence) implements EdgeAction {
        @Override
        public Hyperedge apply(Hyperedge edge, WriteScope scope, Consumer<MemberChange> sink) {
            return edge.insert(scope, incidence, sink);
        }

        @Override
        public OptionalLong member() {
            return OptionalLong.of(incidence.member());
        }
    }

    record Upsert(long atom, UnaryOperator<Incidence> patch) implements EdgeAction {
        @Override
        public Hyperedge apply(Hyperedge edge, WriteScope scope, Consumer<MemberChange> sink) {
            return edge.upsert(scope, atom, patch, sink);
        }

        @Override
        public OptionalLong member() {
            return OptionalLong.of(atom);
        }
    }

    record Replace(Incidence incidence) implements EdgeAction {
        @Override
        public Hyperedge apply(Hyperedge edge, WriteScope scope, Consumer<MemberChange> sink) {
            return edge.replace(scope, incidence, sink);
        }

        @Override
        public OptionalLong member() {
            return OptionalLong.of(incidence.member());
        }
    }

    record Remove(long atom) implements EdgeAction {
        @Override
        public Hyperedge apply(Hyperedge edge, WriteScope scope, Consumer<MemberChange> sink) {
            return edge.remove(scope, atom, sink);
        }

        @Override
        public OptionalLong member() {
            return OptionalLong.of(atom);
        }
    }

    record InsertAt(long index, Incidence incidence) implements EdgeAction {
        @Override
        public Hyperedge apply(Hyperedge edge, WriteScope scope, Consumer<MemberChange> sink) {
            return edge.insertAt(scope, index, incidence, sink);
        }

        @Override
        public OptionalLong member() {
            return OptionalLong.empty();
        }
    }

    record RemoveAt(long index) implements EdgeAction {
        @Override
        public Hyperedge apply(Hyperedge edge, WriteScope scope, Consumer<MemberChange> sink) {
            return edge.removeAt(scope, index, sink);
        }

        @Override
        public OptionalLong member() {
            return OptionalLong.empty();
        }
    }

    record UpdateAt(long index, UnaryOperator<Incidence> patch) implements EdgeAction {
        @Override
        public Hyperedge apply(Hyperedge edge, WriteScope scope, Consumer<MemberChange> sink) {
            return edge.updateAt(scope, index, patch, sink);
        }

        @Override
        public OptionalLong member() {
            return OptionalLong.empty();
        }
    }

    record Load(Ref members, Ref order) implements EdgeAction {
        @Override
        public Hyperedge apply(Hyperedge edge, WriteScope scope, Consumer<MemberChange> sink) {
            Hyperedge loaded = edge.withRoots(members, order);
            Hyperedge.diff(edge, loaded, sink);
            return loaded;
        }

        @Override
        public OptionalLong member() {
            return OptionalLong.empty();
        }
    }
}

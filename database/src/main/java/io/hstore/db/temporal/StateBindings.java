// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.temporal;

import io.hstore.db.Reader;
import io.hstore.db.Writer;
import io.hstore.db.property.PropertySlots;
import io.hstore.db.value.Value;
import io.hstore.db.value.Values;
import io.hstore.engine.catalog.Slot;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.topology.Validity;
import io.hstore.engine.tree.EntryMeasure;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public final class StateBindings {

    public static final int STATE_SCHEMA_NAMESPACE = 12;

    public record Binding(int schema, Value value, long validFrom, long validTo, long version) {
        public Validity validity() {
            return new Validity(validFrom, validTo);
        }
    }

    private static final ValueCodec<Binding> CODEC = ValueCodec.rows(
            binding -> 5 + PropertySlots.VALUE_CODEC.maxSize(binding.value()) + 30,
            (out, binding) -> {
                out.putVarInt(binding.schema());
                Values.write(out, binding.value());
                out.putSignedVarLong(binding.validFrom()).putSignedVarLong(binding.validTo()).putVarLong(binding.version());
            },
            StateBindings::read);

    public static final Slot<Binding> STATE = Slot.primary(39, "state",
            new TreeSchema<>(75, "state", FingerprintMode.SET, CODEC,
                    EntryMeasure.keyed(binding -> Hashing.of(binding.schema(), Values.hash(binding.value()), binding.validFrom(), binding.validTo(), binding.version()))));

    public static final List<Slot<?>> SLOTS = List.of(STATE);

    private StateBindings() {
    }

    private static Binding read(ByteCursor in) {
        return new Binding(in.getVarInt(), Values.read(in), in.getSignedVarLong(), in.getSignedVarLong(), in.getVarLong());
    }

    public static void bind(Writer writer, long atom, String schema, Value value, Validity validity) {
        writer.principal().requireWrite();
        writer.require(atom);
        int schemaId = writer.database().engine().dictionary().name(STATE_SCHEMA_NAMESPACE, schema);
        writer.transaction().update(STATE, atom, current -> Optional.of(new Binding(schemaId, value, validity.from(), validity.to(),
                current.map(Binding::version).orElse(0L) + 1)));
    }

    public static Optional<Binding> state(Reader reader, long atom) {
        return reader.visible(atom) ? reader.view().get(STATE, atom) : Optional.empty();
    }

    public static Optional<Value> stateAt(Reader reader, long atom, long instant) {
        return state(reader, atom)
                .filter(binding -> binding.validFrom() <= instant && instant < binding.validTo())
                .map(Binding::value);
    }

    public static void transition(Writer writer, Consumer<Writer> topology, Map<Long, Value> states, String schema, Validity validity) {
        topology.accept(writer);
        states.forEach((atom, value) -> bind(writer, atom, schema, value, validity));
    }
}

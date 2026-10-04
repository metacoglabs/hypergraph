package io.hstore.db.evidence;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.ValueCodec;

import java.util.Map;
import java.util.TreeMap;

public record Evidence(long source, long method, long observedAt, String creator, long inputHash, Map<String, String> attributes, int tenant) {

    public Evidence {
        attributes = Map.copyOf(attributes);
    }

    public Evidence withTenant(int owner) {
        return new Evidence(source, method, observedAt, creator, inputHash, attributes, owner);
    }

    long hash() {
        long attributeHash = new TreeMap<>(attributes).entrySet().stream()
                .mapToLong(entry -> Hashing.of(Hashing.of(entry.getKey()), Hashing.of(entry.getValue())))
                .reduce(0, Hashing::combine);
        return Hashing.of(source, method, observedAt, Hashing.of(creator), inputHash, attributeHash, tenant);
    }

    static final ValueCodec<Evidence> CODEC = ValueCodec.rows(
            evidence -> 55 + ByteCursor.stringSize(evidence.creator()) + evidence.attributes().entrySet().stream()
                    .mapToInt(entry -> ByteCursor.stringSize(entry.getKey()) + ByteCursor.stringSize(entry.getValue()))
                    .sum(),
            (out, evidence) -> {
                out.putVarLong(evidence.source()).putVarLong(evidence.method()).putSignedVarLong(evidence.observedAt())
                        .putString(evidence.creator()).putLong(evidence.inputHash()).putVarInt(evidence.attributes().size());
                new TreeMap<>(evidence.attributes()).forEach((key, value) -> out.putString(key).putString(value));
                out.putVarInt(evidence.tenant());
            },
            in -> {
                long source = in.getVarLong();
                long method = in.getVarLong();
                long observedAt = in.getSignedVarLong();
                String creator = in.getString();
                long inputHash = in.getLong();
                Map<String, String> attributes = new TreeMap<>();
                for (int count = in.getVarInt(); count > 0; count--) {
                    attributes.put(in.getString(), in.getString());
                }
                return new Evidence(source, method, observedAt, creator, inputHash, attributes, in.getVarInt());
            });
}

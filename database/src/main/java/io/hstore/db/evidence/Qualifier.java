package io.hstore.db.evidence;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.ValueCodec;

import java.util.ArrayList;
import java.util.List;

public record Qualifier(long source, AssertionType type, double confidence, long method, long observedAt, List<Long> evidence, int tenant) {

    public static final Qualifier OBSERVED = new Qualifier(0, AssertionType.OBSERVED, 1.0, 0, 0, List.of(), 0);

    public Qualifier {
        if (confidence < 0 || confidence > 1 || Double.isNaN(confidence)) {
            throw new IllegalArgumentException("confidence must lie in [0, 1]: " + confidence);
        }
        evidence = List.copyOf(evidence);
    }

    public static Qualifier of(AssertionType type, double confidence, List<Long> evidence) {
        return new Qualifier(0, type, confidence, 0, System.currentTimeMillis(), evidence, 0);
    }

    public Qualifier withTenant(int owner) {
        return new Qualifier(source, type, confidence, method, observedAt, evidence, owner);
    }

    long hash() {
        return Hashing.of(source, type.ordinal(), Double.doubleToLongBits(confidence), method, observedAt, tenant,
                evidence.stream().mapToLong(Long::longValue).reduce(0, Hashing::combine));
    }

    static final ValueCodec<Qualifier> CODEC = ValueCodec.rows(
            qualifier -> 45 + 10 * qualifier.evidence().size(),
            (out, qualifier) -> {
                out.putVarLong(qualifier.source()).putByte(qualifier.type().ordinal()).putDouble(qualifier.confidence())
                        .putVarLong(qualifier.method()).putSignedVarLong(qualifier.observedAt()).putVarInt(qualifier.evidence().size());
                qualifier.evidence().forEach(out::putVarLong);
                out.putVarInt(qualifier.tenant());
            },
            Qualifier::read);

    private static Qualifier read(ByteCursor in) {
        long source = in.getVarLong();
        AssertionType type = AssertionType.values()[in.getUnsignedByte()];
        double confidence = in.getDouble();
        long method = in.getVarLong();
        long observedAt = in.getSignedVarLong();
        int count = in.getVarInt();
        List<Long> evidence = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            evidence.add(in.getVarLong());
        }
        return new Qualifier(source, type, confidence, method, observedAt, evidence, in.getVarInt());
    }
}

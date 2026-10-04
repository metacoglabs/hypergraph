package io.hstore.engine.topology;

import io.hstore.engine.tree.Hashing;

public record Incidence(long member, int roleSet, long weight, long validFrom, long validTo, long dataRef, long qualifier) {

    public static final int NO_ROLES = 0;

    public Incidence {
        if (validFrom >= validTo) {
            throw new IllegalArgumentException("empty validity interval for member " + member);
        }
    }

    public static Incidence of(long member) {
        return new Incidence(member, NO_ROLES, Weight.ONE, Long.MIN_VALUE, Long.MAX_VALUE, 0, 0);
    }

    public Incidence withWeight(long fixedWeight) {
        return new Incidence(member, roleSet, fixedWeight, validFrom, validTo, dataRef, qualifier);
    }

    public Incidence withWeight(double value) {
        return withWeight(Weight.of(value));
    }

    public Incidence withValidity(Validity validity) {
        return new Incidence(member, roleSet, weight, validity.from(), validity.to(), dataRef, qualifier);
    }

    public Incidence withDataRef(long reference) {
        return new Incidence(member, roleSet, weight, validFrom, validTo, reference, qualifier);
    }

    public Incidence withQualifier(long reference) {
        return new Incidence(member, roleSet, weight, validFrom, validTo, dataRef, reference);
    }

    public Validity validity() {
        return new Validity(validFrom, validTo);
    }

    public double weightValue() {
        return Weight.toDouble(weight);
    }

    public boolean validAt(long instant) {
        return validFrom <= instant && instant < validTo;
    }

    public long contentHash() {
        return Hashing.of(member, roleSet, weight, validFrom, validTo, dataRef, qualifier);
    }

    public static long roleBit(int roleSet) {
        return 1L << (roleSet & 63);
    }
}

package io.hstore.db;

import io.hstore.engine.topology.Validity;

import java.util.List;
import java.util.OptionalDouble;

public record MemberSpec(List<String> roles, OptionalDouble weight, Validity validity, long dataRef, long qualifier) {

    public static final MemberSpec PLAIN = new MemberSpec(List.of(), OptionalDouble.empty(), Validity.ALWAYS, 0, 0);

    public MemberSpec {
        roles = List.copyOf(roles);
    }

    public static MemberSpec roles(String... roles) {
        return PLAIN.withRoles(List.of(roles));
    }

    public MemberSpec withRoles(List<String> names) {
        return new MemberSpec(names, weight, validity, dataRef, qualifier);
    }

    public MemberSpec withWeight(double value) {
        return new MemberSpec(roles, OptionalDouble.of(value), validity, dataRef, qualifier);
    }

    public MemberSpec withValidity(Validity interval) {
        return new MemberSpec(roles, weight, interval, dataRef, qualifier);
    }

    public MemberSpec withDataRef(long reference) {
        return new MemberSpec(roles, weight, validity, reference, qualifier);
    }

    public MemberSpec withQualifier(long reference) {
        return new MemberSpec(roles, weight, validity, dataRef, reference);
    }
}

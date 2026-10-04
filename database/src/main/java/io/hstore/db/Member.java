package io.hstore.db;

import io.hstore.engine.topology.Validity;

import java.util.List;

public record Member(long atom, List<String> roles, double weight, Validity validity, long position, long dataRef, long qualifier) {
}

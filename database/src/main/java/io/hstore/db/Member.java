// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db;

import io.hstore.engine.topology.Validity;

import java.util.List;

public record Member(long atom, List<String> roles, double weight, Validity validity, long position, long dataRef, long qualifier) {
}

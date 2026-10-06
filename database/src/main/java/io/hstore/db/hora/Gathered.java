// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import io.hstore.db.value.Value;

import java.util.List;

public record Gathered(long edge, long member, Value value, double weight, List<String> roles) {

    public double numeric() {
        return value.number().orElse(Double.NaN);
    }
}

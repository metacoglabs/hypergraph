// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

import java.util.OptionalDouble;
import java.util.stream.DoubleStream;

public enum Reducer {
    SUM,
    MIN,
    MAX,
    MEAN,
    COUNT;

    public OptionalDouble reduce(DoubleStream values) {
        return switch (this) {
            case SUM -> OptionalDouble.of(values.sum());
            case MIN -> values.min();
            case MAX -> values.max();
            case MEAN -> values.average();
            case COUNT -> OptionalDouble.of(values.count());
        };
    }

    public static Reducer parse(String name) {
        return switch (name.toUpperCase()) {
            case "AVG", "MEAN" -> MEAN;
            default -> valueOf(name.toUpperCase());
        };
    }
}

// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.bench;

import io.hstore.db.value.Json;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

record Latency(long[] sorted) {

    record Percentile(String name, double quantile) {
    }

    static final List<Percentile> PERCENTILES = List.of(new Percentile("p50", 0.5), new Percentile("p99", 0.99),
            new Percentile("p99.9", 0.999), new Percentile("max", 1.0));

    static Latency of(long[] nanos) {
        long[] sorted = nanos.clone();
        Arrays.sort(sorted);
        return new Latency(sorted);
    }

    long nanos(double quantile) {
        return sorted[Math.clamp((long) Math.ceil(quantile * sorted.length) - 1, 0, sorted.length - 1)];
    }

    Json json() {
        Map<String, Json> fields = new LinkedHashMap<>();
        PERCENTILES.forEach(percentile -> fields.put(percentile.name(), new Json.Number(BigDecimal.valueOf(nanos(percentile.quantile()) / 1e3))));
        return new Json.Obj(fields);
    }
}

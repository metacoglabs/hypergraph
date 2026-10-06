// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.server.studio;

import io.hstore.db.value.Json;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

final class JsonFields {

    private final Map<String, Json> fields = new LinkedHashMap<>();

    static JsonFields object() {
        return new JsonFields();
    }

    static Json.Array array(Stream<? extends Json> items) {
        return new Json.Array(items.map(Json.class::cast).toList());
    }

    static Json text(String value) {
        return new Json.Str(value);
    }

    static Json number(long value) {
        return new Json.Number(BigDecimal.valueOf(value));
    }

    static Json number(double value) {
        return Double.isFinite(value) ? new Json.Number(BigDecimal.valueOf(value)) : Json.NULL;
    }

    JsonFields put(String name, Json value) {
        fields.put(name, value);
        return this;
    }

    JsonFields put(String name, String value) {
        return put(name, text(value));
    }

    JsonFields put(String name, long value) {
        return put(name, number(value));
    }

    JsonFields put(String name, double value) {
        return put(name, number(value));
    }

    JsonFields put(String name, boolean value) {
        return put(name, new Json.Bool(value));
    }

    JsonFields putAll(Json.Obj other) {
        fields.putAll(other.fields());
        return this;
    }

    Json.Obj build() {
        return new Json.Obj(fields);
    }
}

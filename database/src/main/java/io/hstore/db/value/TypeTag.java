// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.value;

public enum TypeTag {
    NULL,
    BOOL,
    INT,
    FLOAT,
    STRING,
    TIMESTAMP,
    DECIMAL,
    PAYLOAD;

    public boolean numeric() {
        return this == INT || this == FLOAT || this == DECIMAL || this == TIMESTAMP;
    }

    public static TypeTag parse(String name) {
        return switch (name.toUpperCase()) {
            case "BOOL", "BOOLEAN" -> BOOL;
            case "INT", "INTEGER", "LONG" -> INT;
            case "FLOAT", "DOUBLE", "REAL" -> FLOAT;
            case "STRING", "TEXT" -> STRING;
            case "TIMESTAMP", "TIME" -> TIMESTAMP;
            case "DECIMAL" -> DECIMAL;
            case "PAYLOAD", "BLOB", "JSON" -> PAYLOAD;
            case "NULL" -> NULL;
            default -> throw new IllegalArgumentException("unknown value type " + name);
        };
    }
}

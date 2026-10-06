// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.hora;

public sealed interface Field {

    record Property(String name) implements Field {
    }

    record State() implements Field {
    }

    record Weight() implements Field {
    }

    record Degree() implements Field {
    }

    static Field parse(String text) {
        return switch (text.toLowerCase()) {
            case "weight" -> new Weight();
            case "state" -> new State();
            case "degree" -> new Degree();
            default -> new Property(text);
        };
    }
}

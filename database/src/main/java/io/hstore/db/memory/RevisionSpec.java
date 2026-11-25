// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import java.util.List;
import java.util.OptionalLong;

public record RevisionSpec(OptionalLong validFrom, long validTo, List<Long> sources, String note) {

    public static final RevisionSpec DEFAULT = new RevisionSpec(OptionalLong.empty(), Long.MAX_VALUE, List.of(), "");

    public RevisionSpec {
        sources = List.copyOf(sources);
    }

    public RevisionSpec validFrom(long instant) {
        return new RevisionSpec(OptionalLong.of(instant), validTo, sources, note);
    }

    public RevisionSpec validTo(long instant) {
        return new RevisionSpec(validFrom, instant, sources, note);
    }

    public RevisionSpec sources(List<Long> ids) {
        return new RevisionSpec(validFrom, validTo, ids, note);
    }

    public RevisionSpec note(String text) {
        return new RevisionSpec(validFrom, validTo, sources, text);
    }
}

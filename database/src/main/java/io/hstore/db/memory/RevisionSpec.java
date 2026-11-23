// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import java.util.List;
import java.util.OptionalLong;

public record RevisionSpec(OptionalLong validFrom, long validTo, List<Long> sources, String note, boolean embed) {

    public static final RevisionSpec DEFAULT = new RevisionSpec(OptionalLong.empty(), Long.MAX_VALUE, List.of(), "", false);

    public RevisionSpec {
        sources = List.copyOf(sources);
    }

    public RevisionSpec validFrom(long instant) {
        return new RevisionSpec(OptionalLong.of(instant), validTo, sources, note, embed);
    }

    public RevisionSpec validTo(long instant) {
        return new RevisionSpec(validFrom, instant, sources, note, embed);
    }

    public RevisionSpec sources(List<Long> ids) {
        return new RevisionSpec(validFrom, validTo, ids, note, embed);
    }

    public RevisionSpec note(String text) {
        return new RevisionSpec(validFrom, validTo, sources, text, embed);
    }

    public RevisionSpec embedded() {
        return new RevisionSpec(validFrom, validTo, sources, note, true);
    }
}

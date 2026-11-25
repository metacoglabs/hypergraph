// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.ValueCodec;

public record Section(long id, long document, long revision, int ordinal, int parent, int level, String title, String anchor,
                      int start, int end, long contentHash, int tenant) {

    long hash() {
        return Hashing.of(Hashing.of(id, document, revision, ordinal, parent, level), Hashing.of(Hashing.of(title), Hashing.of(anchor),
                start, end, contentHash, tenant));
    }

    static int encodedSize(Section section) {
        ByteCursor out = ByteCursor.growable(256);
        write(out, section);
        return (int) out.position();
    }

    private static void write(ByteCursor out, Section section) {
        out.putVarLong(section.id()).putVarLong(section.document()).putVarLong(section.revision())
                .putVarInt(section.ordinal()).putSignedVarLong(section.parent()).putVarInt(section.level()).putString(section.title())
                .putString(section.anchor()).putVarInt(section.start()).putVarInt(section.end()).putLong(section.contentHash())
                .putVarInt(section.tenant());
    }

    static final ValueCodec<Section> CODEC = ValueCodec.rows(
            section -> 70 + ByteCursor.stringSize(section.title()) + ByteCursor.stringSize(section.anchor()),
            Section::write,
            in -> new Section(in.getVarLong(), in.getVarLong(), in.getVarLong(), in.getVarInt(), (int) in.getSignedVarLong(), in.getVarInt(),
                    in.getString(), in.getString(), in.getVarInt(), in.getVarInt(), in.getLong(), in.getVarInt()));
}

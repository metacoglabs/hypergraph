// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.memory;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.ValueCodec;

import java.util.ArrayList;
import java.util.List;

public record Revision(long id, long document, int number, long parent, long payload, long length, long contentHash,
                       long validFrom, long validTo, long recordedAt, String author, String note, List<Long> sources,
                       int sections, int tenant) {

    public Revision {
        sources = List.copyOf(sources);
    }

    public boolean validAt(long instant) {
        return validFrom <= instant && instant < validTo;
    }

    long hash() {
        long listed = sources.stream().mapToLong(Long::longValue).reduce(0, Hashing::combine);
        return Hashing.of(Hashing.of(id, document, number, parent, payload, length, contentHash),
                Hashing.of(validFrom, validTo, recordedAt, Hashing.of(author), Hashing.of(note), listed, sections, tenant));
    }

    static int encodedSize(Revision revision) {
        ByteCursor out = ByteCursor.growable(256);
        write(out, revision);
        return (int) out.position();
    }

    private static void write(ByteCursor out, Revision revision) {
                out.putVarLong(revision.id()).putVarLong(revision.document()).putVarInt(revision.number()).putVarLong(revision.parent())
                        .putVarLong(revision.payload()).putVarLong(revision.length()).putLong(revision.contentHash())
                        .putSignedVarLong(revision.validFrom()).putSignedVarLong(revision.validTo()).putSignedVarLong(revision.recordedAt())
                        .putString(revision.author()).putString(revision.note()).putVarInt(revision.sources().size());
                revision.sources().forEach(out::putVarLong);
                out.putVarInt(revision.sections()).putVarInt(revision.tenant());
    }

    static final ValueCodec<Revision> CODEC = ValueCodec.rows(
            revision -> 130 + ByteCursor.stringSize(revision.author()) + ByteCursor.stringSize(revision.note()) + 10 * revision.sources().size(),
            Revision::write,
            in -> {
                long id = in.getVarLong();
                long document = in.getVarLong();
                int number = in.getVarInt();
                long parent = in.getVarLong();
                long payload = in.getVarLong();
                long length = in.getVarLong();
                long contentHash = in.getLong();
                long validFrom = in.getSignedVarLong();
                long validTo = in.getSignedVarLong();
                long recordedAt = in.getSignedVarLong();
                String author = in.getString();
                String note = in.getString();
                List<Long> sources = new ArrayList<>();
                for (int count = in.getVarInt(); count > 0; count--) {
                    sources.add(in.getVarLong());
                }
                return new Revision(id, document, number, parent, payload, length, contentHash, validFrom, validTo, recordedAt,
                        author, note, sources, in.getVarInt(), in.getVarInt());
            });
}

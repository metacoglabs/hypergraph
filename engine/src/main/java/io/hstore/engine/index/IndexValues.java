// SPDX-FileCopyrightText: MetaCognition India Pvt Limited
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.engine.index;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.ValueCodec;

public final class IndexValues {

    public enum Marker { PRESENT }

    public record Incident(int roleSet, long locator, long validFrom, long validTo) {
        public long hash() {
            return Hashing.of(roleSet, locator, validFrom, validTo);
        }

        public boolean validAt(long instant) {
            return validFrom <= instant && instant < validTo;
        }
    }

    private static final int OPEN_FROM = 1;
    private static final int OPEN_TO = 2;

    public record CanonicalName(int tenant, int type, String key) {
        public long hash() {
            return Hashing.of(tenant, type, Hashing.of(key));
        }
    }

    public static final ValueCodec<Marker> MARKER = ValueCodec.rows(_ -> 0, (_, _) -> {
    }, _ -> Marker.PRESENT);

    public static final ValueCodec<Incident> INCIDENT = ValueCodec.rows(
            incident -> ByteCursor.varLongSize(incident.roleSet()) + ByteCursor.signedVarLongSize(incident.locator()) + 1
                    + (incident.validFrom() == Long.MIN_VALUE ? 0 : ByteCursor.signedVarLongSize(incident.validFrom()))
                    + (incident.validTo() == Long.MAX_VALUE ? 0 : ByteCursor.signedVarLongSize(incident.validTo())),
            IndexValues::writeIncident,
            IndexValues::readIncident);

    private static void writeIncident(ByteCursor out, Incident incident) {
        int flags = (incident.validFrom() == Long.MIN_VALUE ? 0 : OPEN_FROM) | (incident.validTo() == Long.MAX_VALUE ? 0 : OPEN_TO);
        out.putVarInt(incident.roleSet()).putSignedVarLong(incident.locator()).putByte((byte) flags);
        if ((flags & OPEN_FROM) != 0) {
            out.putSignedVarLong(incident.validFrom());
        }
        if ((flags & OPEN_TO) != 0) {
            out.putSignedVarLong(incident.validTo());
        }
    }

    private static Incident readIncident(ByteCursor in) {
        int roleSet = in.getVarInt();
        long locator = in.getSignedVarLong();
        int flags = in.getByte();
        long from = (flags & OPEN_FROM) != 0 ? in.getSignedVarLong() : Long.MIN_VALUE;
        long to = (flags & OPEN_TO) != 0 ? in.getSignedVarLong() : Long.MAX_VALUE;
        return new Incident(roleSet, locator, from, to);
    }

    public static final ValueCodec<CanonicalName> CANONICAL_NAME = ValueCodec.rows(
            name -> 10 + ByteCursor.stringSize(name.key()),
            (out, name) -> out.putVarInt(name.tenant()).putVarInt(name.type()).putString(name.key()),
            in -> new CanonicalName(in.getVarInt(), in.getVarInt(), in.getString()));

    private IndexValues() {
    }
}

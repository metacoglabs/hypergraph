package io.hstore.engine.index;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.ValueCodec;

public final class IndexValues {

    public enum Marker { PRESENT }

    public record Incident(int roleSet, long locator) {
        public long hash() {
            return Hashing.of(roleSet, locator);
        }
    }

    public record CanonicalName(int tenant, int type, String key) {
        public long hash() {
            return Hashing.of(tenant, type, Hashing.of(key));
        }
    }

    public static final ValueCodec<Marker> MARKER = ValueCodec.rows(_ -> 0, (_, _) -> {
    }, _ -> Marker.PRESENT);

    public static final ValueCodec<Incident> INCIDENT = ValueCodec.rows(
            incident -> ByteCursor.varLongSize(incident.roleSet()) + ByteCursor.signedVarLongSize(incident.locator()),
            (out, incident) -> out.putVarInt(incident.roleSet()).putSignedVarLong(incident.locator()),
            in -> new Incident(in.getVarInt(), in.getSignedVarLong()));

    public static final ValueCodec<CanonicalName> CANONICAL_NAME = ValueCodec.rows(
            name -> 10 + ByteCursor.stringSize(name.key()),
            (out, name) -> out.putVarInt(name.tenant()).putVarInt(name.type()).putString(name.key()),
            in -> new CanonicalName(in.getVarInt(), in.getVarInt(), in.getString()));

    private IndexValues() {
    }
}

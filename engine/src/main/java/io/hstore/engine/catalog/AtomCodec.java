package io.hstore.engine.catalog;

import io.hstore.engine.catalog.AtomRecord.EdgeRecord;
import io.hstore.engine.catalog.AtomRecord.NodeRecord;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.topology.TopologySchemas;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.ValueCodec;

import java.util.List;

final class AtomCodec implements ValueCodec<AtomRecord> {

    static final AtomCodec INSTANCE = new AtomCodec();

    private static final int NODE = 0;
    private static final int EDGE = 1;

    @Override
    public int maxSize(AtomRecord record) {
        return switch (record) {
            case NodeRecord node -> 1 + 5 + 1 + (node.hasCanonicalKey() ? ByteCursor.stringSize(node.canonicalKey()) : 0)
                    + ByteCursor.varLongSize(node.dataRef()) + ByteCursor.varLongSize(node.embeddingRef()) + 15;
            case EdgeRecord edge -> 1 + 5 + 1 + Ref.encodedSize(edge.members()) + Ref.encodedSize(edge.order())
                    + ByteCursor.varLongSize(edge.version()) + ByteCursor.varLongSize(edge.dataRef()) + 10;
        };
    }

    @Override
    public void encode(ByteCursor out, long[] keys, List<AtomRecord> values) {
        values.forEach(record -> write(out, record));
    }

    private static void write(ByteCursor out, AtomRecord record) {
        switch (record) {
            case NodeRecord node -> {
                out.putByte(NODE).putVarInt(node.type()).putByte(node.hasCanonicalKey() ? 1 : 0);
                if (node.hasCanonicalKey()) {
                    out.putString(node.canonicalKey());
                }
                out.putVarLong(node.dataRef()).putVarLong(node.embeddingRef())
                        .putVarInt(node.flags()).putVarInt(node.tenant()).putVarInt(node.isolation());
            }
            case EdgeRecord edge -> {
                out.putByte(EDGE).putVarInt(edge.type()).putByte(edge.kind().ordinal());
                Ref.write(out, edge.members());
                Ref.write(out, edge.order());
                out.putVarLong(edge.version()).putVarLong(edge.dataRef()).putVarInt(edge.tenant()).putVarInt(edge.isolation());
            }
        }
    }

    @Override
    public void decode(ByteCursor in, long[] keys, Object[] into) {
        for (int i = 0; i < into.length; i++) {
            into[i] = read(in);
        }
    }

    private static AtomRecord read(ByteCursor in) {
        int tag = in.getUnsignedByte();
        int type = in.getVarInt();
        if (tag == NODE) {
            String key = in.getUnsignedByte() == 1 ? in.getString() : null;
            return new NodeRecord(type, key, in.getVarLong(), in.getVarLong(), in.getVarInt(), in.getVarInt(), in.getVarInt());
        }
        EdgeKind kind = EdgeKind.values()[in.getUnsignedByte()];
        Ref members = Ref.read(in);
        Ref order = Ref.read(in);
        return new EdgeRecord(type, kind, members, order, in.getVarLong(), in.getVarLong(), in.getVarInt(), in.getVarInt());
    }

    @Override
    public boolean holdsRefs() {
        return true;
    }

    @Override
    public AtomRecord mapRefs(AtomRecord record, RefMapper mapper) {
        return switch (record) {
            case NodeRecord node -> node;
            case EdgeRecord edge -> {
                Ref members = mapper.map(edge.members(), TopologySchemas.members(edge.kind()));
                Ref order = mapper.map(edge.order(), TopologySchemas.ORDER_INDEX);
                yield members == edge.members() && order == edge.order() ? edge
                        : new EdgeRecord(edge.type(), edge.kind(), members, order, edge.version(), edge.dataRef(), edge.tenant(), edge.isolation());
            }
        };
    }

    @Override
    public void forEachRef(AtomRecord record, RefVisitor visitor) {
        if (record instanceof EdgeRecord edge) {
            visitor.visit(edge.members(), TopologySchemas.members(edge.kind()));
            visitor.visit(edge.order(), TopologySchemas.ORDER_INDEX);
        }
    }
}

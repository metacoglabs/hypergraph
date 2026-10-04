package io.hstore.engine.topology;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.ValueCodec;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

public final class IncidenceCodec implements ValueCodec<Incidence> {

    public static final IncidenceCodec KEYED = new IncidenceCodec(false);
    public static final IncidenceCodec SEQUENCED = new IncidenceCodec(true);

    private enum Column {
        ROLES(i -> i.roleSet() != Incidence.NO_ROLES, Incidence::roleSet, false),
        WEIGHT(i -> i.weight() != Weight.ONE, Incidence::weight, true),
        VALID_FROM(i -> i.validFrom() != Long.MIN_VALUE, Incidence::validFrom, true),
        VALID_TO(i -> i.validTo() != Long.MAX_VALUE, Incidence::validTo, true),
        DATA_REF(i -> i.dataRef() != 0, Incidence::dataRef, false),
        QUALIFIER(i -> i.qualifier() != 0, Incidence::qualifier, false);

        final Predicate<Incidence> present;
        final ToLongFunction<Incidence> field;
        final boolean signed;

        Column(Predicate<Incidence> present, ToLongFunction<Incidence> field, boolean signed) {
            this.present = present;
            this.field = field;
            this.signed = signed;
        }

        int size(Incidence incidence) {
            long value = field.applyAsLong(incidence);
            return signed ? ByteCursor.signedVarLongSize(value) : ByteCursor.varLongSize(value);
        }

        void write(ByteCursor out, long value) {
            if (signed) {
                out.putSignedVarLong(value);
            } else {
                out.putVarLong(value);
            }
        }

        long read(ByteCursor in) {
            return signed ? in.getSignedVarLong() : in.getVarLong();
        }
    }

    private static final Column[] COLUMNS = Column.values();

    private final boolean sequenced;

    private IncidenceCodec(boolean sequenced) {
        this.sequenced = sequenced;
    }

    @Override
    public int leafOverhead(int entries) {
        return entries == 0 ? 0 : 1 + COLUMNS.length * ((entries + 7) >>> 3);
    }

    @Override
    public int maxSize(Incidence incidence) {
        int size = sequenced ? ByteCursor.signedVarLongSize(incidence.member()) : 0;
        for (Column column : COLUMNS) {
            if (column.present.test(incidence)) {
                size += column.size(incidence);
            }
        }
        return size;
    }

    @Override
    public void encode(ByteCursor out, long[] keys, List<Incidence> values) {
        int flags = 0;
        for (Column column : COLUMNS) {
            if (values.stream().anyMatch(column.present)) {
                flags |= 1 << column.ordinal();
            }
        }
        out.putByte(flags);
        if (sequenced) {
            values.forEach(incidence -> out.putSignedVarLong(incidence.member()));
        }
        for (Column column : COLUMNS) {
            if ((flags & (1 << column.ordinal())) == 0) {
                continue;
            }
            byte[] bitmap = new byte[(values.size() + 7) >>> 3];
            for (int i = 0; i < values.size(); i++) {
                if (column.present.test(values.get(i))) {
                    bitmap[i >>> 3] |= (byte) (1 << (i & 7));
                }
            }
            out.putBytes(bitmap);
            for (Incidence incidence : values) {
                if (column.present.test(incidence)) {
                    column.write(out, column.field.applyAsLong(incidence));
                }
            }
        }
    }

    @Override
    public void decode(ByteCursor in, long[] keys, Object[] into) {
        int count = into.length;
        int flags = in.getUnsignedByte();
        long[] members = keys;
        if (sequenced) {
            members = new long[count];
            for (int i = 0; i < count; i++) {
                members[i] = in.getSignedVarLong();
            }
        }
        long[][] columns = new long[COLUMNS.length][];
        for (Column column : COLUMNS) {
            if ((flags & (1 << column.ordinal())) == 0) {
                continue;
            }
            byte[] bitmap = in.getBytes((count + 7) >>> 3);
            long[] decoded = new long[count];
            Incidence defaults = Incidence.of(0);
            for (int i = 0; i < count; i++) {
                decoded[i] = (bitmap[i >>> 3] & (1 << (i & 7))) != 0 ? column.read(in) : column.field.applyAsLong(defaults);
            }
            columns[column.ordinal()] = decoded;
        }
        for (int i = 0; i < count; i++) {
            into[i] = new Incidence(members[i],
                    (int) value(columns, Column.ROLES, i, Incidence.NO_ROLES),
                    value(columns, Column.WEIGHT, i, Weight.ONE),
                    value(columns, Column.VALID_FROM, i, Long.MIN_VALUE),
                    value(columns, Column.VALID_TO, i, Long.MAX_VALUE),
                    value(columns, Column.DATA_REF, i, 0),
                    value(columns, Column.QUALIFIER, i, 0));
        }
    }

    private static long value(long[][] columns, Column column, int index, long fallback) {
        long[] decoded = columns[column.ordinal()];
        return decoded == null ? fallback : decoded[index];
    }
}

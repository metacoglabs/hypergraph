// SPDX-FileCopyrightText: Metacog Labs
// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

package io.hstore.db.value;

import io.hstore.engine.HStoreException;
import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.Hashing;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;

public final class Values {

    private static final BigDecimal BEYOND_DOUBLES = new BigDecimal("1e400");

    private Values() {
    }

    static BigDecimal decimal(double value) {
        if (Double.isFinite(value)) {
            return BigDecimal.valueOf(value);
        }
        return value < 0 ? BEYOND_DOUBLES.negate() : BEYOND_DOUBLES;
    }

    public static int maxSize(Value value) {
        return 1 + switch (value) {
            case Value.Null _ -> 0;
            case Value.Bool _ -> 1;
            case Value.Int(long v) -> ByteCursor.signedVarLongSize(v);
            case Value.Real _ -> 8;
            case Value.Text(String v) -> ByteCursor.stringSize(v);
            case Value.Time(long v) -> ByteCursor.signedVarLongSize(v);
            case Value.Decimal(BigDecimal v) -> 5 + v.unscaledValue().bitLength() / 8 + 6;
            case Value.Payload(long ref, long length, String media) ->
                    ByteCursor.varLongSize(ref) + ByteCursor.varLongSize(length) + ByteCursor.stringSize(media);
        };
    }

    public static void write(ByteCursor out, Value value) {
        out.putByte(value.tag().ordinal());
        switch (value) {
            case Value.Null _ -> {
            }
            case Value.Bool(boolean v) -> out.putByte(v ? 1 : 0);
            case Value.Int(long v) -> out.putSignedVarLong(v);
            case Value.Real(double v) -> out.putDouble(v);
            case Value.Text(String v) -> out.putString(v);
            case Value.Time(long v) -> out.putSignedVarLong(v);
            case Value.Decimal(BigDecimal v) -> out.putSignedVarLong(v.scale()).putBlob(v.unscaledValue().toByteArray());
            case Value.Payload(long ref, long length, String media) -> out.putVarLong(ref).putVarLong(length).putString(media);
        }
    }

    public static Value read(ByteCursor in) {
        return switch (TypeTag.values()[in.getUnsignedByte()]) {
            case NULL -> Value.NULL;
            case BOOL -> new Value.Bool(in.getUnsignedByte() == 1);
            case INT -> new Value.Int(in.getSignedVarLong());
            case FLOAT -> new Value.Real(in.getDouble());
            case STRING -> new Value.Text(in.getString());
            case TIMESTAMP -> new Value.Time(in.getSignedVarLong());
            case DECIMAL -> {
                int scale = Math.toIntExact(in.getSignedVarLong());
                yield new Value.Decimal(new BigDecimal(new BigInteger(in.getBlob()), scale));
            }
            case PAYLOAD -> new Value.Payload(in.getVarLong(), in.getVarLong(), in.getString());
        };
    }

    public static long hash(Value value) {
        return switch (value) {
            case Value.Null _ -> 0;
            case Value.Bool(boolean v) -> Hashing.mix(v ? 1 : 2);
            case Value.Int(long v) -> Hashing.of(TypeTag.INT.ordinal(), v);
            case Value.Real(double v) -> Hashing.of(TypeTag.FLOAT.ordinal(), Double.doubleToLongBits(v));
            case Value.Text(String v) -> Hashing.of(v);
            case Value.Time(long v) -> Hashing.of(TypeTag.TIMESTAMP.ordinal(), v);
            case Value.Decimal(BigDecimal v) -> Hashing.of(v.toPlainString());
            case Value.Payload(long ref, long length, String _) -> Hashing.of(TypeTag.PAYLOAD.ordinal(), ref, length);
        };
    }

    public static long orderKey(Value value) {
        return switch (value) {
            case Value.Null _ -> Long.MIN_VALUE;
            case Value.Bool(boolean v) -> v ? 1 : 0;
            case Value.Int(long v) -> v;
            case Value.Time(long v) -> v;
            case Value.Real(double v) -> sortableDouble(v);
            case Value.Decimal(BigDecimal v) -> sortableDouble(v.doubleValue());
            case Value.Text(String v) -> textPrefix(v);
            case Value.Payload(long ref, long _, String _) -> ref;
        };
    }

    private static long sortableDouble(double value) {
        long bits = Double.doubleToLongBits(value == 0.0 ? 0.0 : value);
        return bits ^ ((bits >> 63) & Long.MAX_VALUE);
    }

    private static long textPrefix(String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        long prefix = 0;
        for (int i = 0; i < 8; i++) {
            prefix = (prefix << 8) | (i < bytes.length ? bytes[i] & 0xFF : 0);
        }
        return prefix ^ Long.MIN_VALUE;
    }

    public static Value coerce(Value value, TypeTag target) {
        if (value.tag() == target || value instanceof Value.Null) {
            return value;
        }
        try {
            return switch (target) {
                case INT -> new Value.Int(switch (value) {
                    case Value.Real(double v) when v == Math.rint(v) -> (long) v;
                    case Value.Decimal(BigDecimal v) -> v.longValueExact();
                    case Value.Text(String v) -> Long.parseLong(v.trim());
                    case Value.Time(long v) -> v;
                    default -> throw mismatch(value, target);
                });
                case FLOAT -> new Value.Real(value.number().orElseThrow(() -> mismatch(value, target)));
                case DECIMAL -> new Value.Decimal(switch (value) {
                    case Value.Int(long v) -> BigDecimal.valueOf(v);
                    case Value.Real(double v) -> BigDecimal.valueOf(v);
                    case Value.Text(String v) -> new BigDecimal(v.trim());
                    default -> throw mismatch(value, target);
                });
                case TIMESTAMP -> new Value.Time(switch (value) {
                    case Value.Int(long v) -> v;
                    case Value.Text(String v) -> Instant.parse(v.trim()).toEpochMilli();
                    default -> throw mismatch(value, target);
                });
                case STRING -> new Value.Text(value instanceof Value.Time(long v) ? Instant.ofEpochMilli(v).toString() : plain(value));
                case BOOL -> new Value.Bool(switch (value) {
                    case Value.Text(String v) when v.equalsIgnoreCase("true") || v.equalsIgnoreCase("false") -> Boolean.parseBoolean(v);
                    case Value.Int(long v) -> v != 0;
                    default -> throw mismatch(value, target);
                });
                case NULL, PAYLOAD -> throw mismatch(value, target);
            };
        } catch (NumberFormatException | ArithmeticException | DateTimeParseException e) {
            throw mismatch(value, target);
        }
    }

    public static String plain(Value value) {
        return switch (value) {
            case Value.Text(String v) -> v;
            default -> value.render();
        };
    }

    private static HStoreException mismatch(Value value, TypeTag target) {
        return HStoreException.invalid("cannot use " + value.render() + " as " + target);
    }
}

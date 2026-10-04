package io.hstore.db.value;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.OptionalDouble;

public sealed interface Value extends Comparable<Value> {

    Null NULL = new Null();

    TypeTag tag();

    String render();

    record Null() implements Value {
        @Override
        public TypeTag tag() {
            return TypeTag.NULL;
        }

        @Override
        public String render() {
            return "null";
        }
    }

    record Bool(boolean value) implements Value {
        @Override
        public TypeTag tag() {
            return TypeTag.BOOL;
        }

        @Override
        public String render() {
            return Boolean.toString(value);
        }
    }

    record Int(long value) implements Value {
        @Override
        public TypeTag tag() {
            return TypeTag.INT;
        }

        @Override
        public String render() {
            return Long.toString(value);
        }
    }

    record Real(double value) implements Value {
        @Override
        public TypeTag tag() {
            return TypeTag.FLOAT;
        }

        @Override
        public String render() {
            return Double.toString(value);
        }
    }

    record Text(String value) implements Value {
        @Override
        public TypeTag tag() {
            return TypeTag.STRING;
        }

        @Override
        public String render() {
            return "'" + value.replace("'", "''") + "'";
        }
    }

    record Time(long epochMillis) implements Value {
        @Override
        public TypeTag tag() {
            return TypeTag.TIMESTAMP;
        }

        @Override
        public String render() {
            return Instant.ofEpochMilli(epochMillis).toString();
        }
    }

    record Decimal(BigDecimal value) implements Value {
        public Decimal {
            value = value.signum() == 0 ? BigDecimal.ZERO : value.stripTrailingZeros();
        }

        @Override
        public TypeTag tag() {
            return TypeTag.DECIMAL;
        }

        @Override
        public String render() {
            return value.toPlainString();
        }
    }

    record Payload(long ref, long length, String mediaType) implements Value {
        @Override
        public TypeTag tag() {
            return TypeTag.PAYLOAD;
        }

        @Override
        public String render() {
            return "<" + mediaType + ", " + length + " bytes @" + Long.toHexString(ref) + ">";
        }
    }

    static Value of(Object raw) {
        return switch (raw) {
            case null -> NULL;
            case Value value -> value;
            case Boolean b -> new Bool(b);
            case Integer i -> new Int(i);
            case Long l -> new Int(l);
            case Double d -> new Real(d);
            case Float f -> new Real(f);
            case BigDecimal d -> new Decimal(d);
            case Instant instant -> new Time(instant.toEpochMilli());
            case CharSequence text -> new Text(text.toString());
            default -> throw new IllegalArgumentException("unsupported value " + raw.getClass().getSimpleName());
        };
    }

    default OptionalDouble number() {
        return switch (this) {
            case Int(long v) -> OptionalDouble.of(v);
            case Real(double v) -> OptionalDouble.of(v);
            case Decimal(BigDecimal v) -> OptionalDouble.of(v.doubleValue());
            case Time(long v) -> OptionalDouble.of(v);
            case Bool(boolean v) -> OptionalDouble.of(v ? 1 : 0);
            default -> OptionalDouble.empty();
        };
    }

    default boolean truthy() {
        return switch (this) {
            case Null _ -> false;
            case Bool(boolean v) -> v;
            default -> number().orElse(1) != 0;
        };
    }

    @Override
    default int compareTo(Value other) {
        if (tag().numeric() && other.tag().numeric()) {
            if (this instanceof Int(long a) && other instanceof Int(long b)) {
                return Long.compare(a, b);
            }
            return asDecimal().compareTo(other.asDecimal());
        }
        if (tag() != other.tag()) {
            return Integer.compare(tag().ordinal(), other.tag().ordinal());
        }
        return switch (this) {
            case Null _ -> 0;
            case Bool(boolean a) -> Boolean.compare(a, ((Bool) other).value());
            case Text(String a) -> a.compareTo(((Text) other).value());
            case Payload(long a, long _, String _) -> Long.compare(a, ((Payload) other).ref());
            default -> throw new IllegalStateException("unreachable comparison of " + tag());
        };
    }

    private BigDecimal asDecimal() {
        return switch (this) {
            case Int(long v) -> BigDecimal.valueOf(v);
            case Real(double v) -> Values.decimal(v);
            case Decimal(BigDecimal v) -> v;
            case Time(long v) -> BigDecimal.valueOf(v);
            default -> throw new IllegalStateException(tag() + " is not numeric");
        };
    }
}

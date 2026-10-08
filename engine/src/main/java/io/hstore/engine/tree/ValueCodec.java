package io.hstore.engine.tree;

import io.hstore.engine.page.ByteCursor;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;

public interface ValueCodec<V> {

    int maxSize(V value);

    void encode(ByteCursor out, long[] keys, List<V> values);

    void decode(ByteCursor in, long[] keys, Object[] into);

    default int leafOverhead(int entries) {
        return 0;
    }

    default boolean holdsRefs() {
        return false;
    }

    default V mapRefs(V value, RefMapper mapper) {
        return value;
    }

    default void forEachRef(V value, RefVisitor visitor) {
        mapRefs(value, (ref, schema) -> {
            visitor.visit(ref, schema);
            return ref;
        });
    }

    static <V> ValueCodec<V> rows(ToIntFunction<V> size, BiConsumer<ByteCursor, V> writer, Function<ByteCursor, V> reader) {
        return new ValueCodec<>() {
            @Override
            public int maxSize(V value) {
                return size.applyAsInt(value);
            }

            @Override
            public void encode(ByteCursor out, long[] keys, List<V> values) {
                values.forEach(value -> writer.accept(out, value));
            }

            @Override
            public void decode(ByteCursor in, long[] keys, Object[] into) {
                for (int i = 0; i < into.length; i++) {
                    into[i] = reader.apply(in);
                }
            }
        };
    }

    @FunctionalInterface
    interface RefMapper {
        Ref map(Ref ref, TreeSchema<?> schema);
    }

    interface RefVisitor {
        void visit(Ref ref, TreeSchema<?> schema);
    }
}

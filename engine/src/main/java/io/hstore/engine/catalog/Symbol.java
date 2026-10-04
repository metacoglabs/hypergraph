package io.hstore.engine.catalog;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.ValueCodec;

import java.util.ArrayList;
import java.util.List;

public sealed interface Symbol {

    int namespace();

    long hash();

    record Name(int namespace, String text) implements Symbol {
        @Override
        public long hash() {
            return Hashing.of(namespace, 0, Hashing.of(text));
        }
    }

    record Group(int namespace, List<Integer> members) implements Symbol {
        public Group {
            members = members.stream().distinct().sorted().toList();
        }

        @Override
        public long hash() {
            return Hashing.of(namespace, 1, members.stream().mapToLong(Integer::longValue).reduce(0, Hashing::combine));
        }
    }

    ValueCodec<Symbol> CODEC = ValueCodec.rows(
            symbol -> switch (symbol) {
                case Name name -> 1 + 5 + ByteCursor.stringSize(name.text());
                case Group group -> 1 + 5 + 5 + 5 * group.members().size();
            },
            (out, symbol) -> {
                switch (symbol) {
                    case Name name -> out.putByte(0).putVarInt(name.namespace()).putString(name.text());
                    case Group group -> {
                        out.putByte(1).putVarInt(group.namespace()).putVarInt(group.members().size());
                        group.members().forEach(out::putVarInt);
                    }
                }
            },
            in -> {
                int tag = in.getUnsignedByte();
                int namespace = in.getVarInt();
                if (tag == 0) {
                    return new Name(namespace, in.getString());
                }
                int count = in.getVarInt();
                List<Integer> members = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    members.add(in.getVarInt());
                }
                return new Group(namespace, members);
            });
}

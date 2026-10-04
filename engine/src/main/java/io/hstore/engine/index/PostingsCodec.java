package io.hstore.engine.index;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;

import java.util.Arrays;
import java.util.List;
import java.util.function.ToLongFunction;

final class PostingsCodec<P> implements ValueCodec<Postings<P>> {

    private static final int INLINE = 0;
    private static final int PROMOTED = 1;

    private final TreeSchema<P> postings;
    private final ToLongFunction<P> hash;

    PostingsCodec(TreeSchema<P> postings, ToLongFunction<P> hash) {
        this.postings = postings;
        this.hash = hash;
    }

    Postings.Inline<P> inline(long[] keys, List<P> values) {
        long fingerprint = 0;
        int size = 1 + ByteCursor.varLongSize(keys.length) + 1;
        for (int i = 0; i < keys.length; i++) {
            P value = values.get(i);
            fingerprint += Hashing.combine(Hashing.mix(keys[i]), hash.applyAsLong(value));
            size += (i == 0 ? ByteCursor.signedVarLongSize(keys[0]) : ByteCursor.varLongSize(keys[i] - keys[i - 1]))
                    + postings.codec().maxSize(value);
        }
        return new Postings.Inline<>(keys, values, fingerprint, size);
    }

    @Override
    public int maxSize(Postings<P> value) {
        return switch (value) {
            case Postings.Inline<P> inline -> inline.bytes();
            case Postings.Promoted<P>(Ref root) -> 1 + Ref.encodedSize(root);
        };
    }

    @Override
    public void encode(ByteCursor out, long[] keys, List<Postings<P>> values) {
        for (Postings<P> value : values) {
            switch (value) {
                case Postings.Inline<P>(long[] postingKeys, List<P> postingValues, long _, int _) -> {
                    out.putByte(INLINE).putVarInt(postingKeys.length);
                    out.putSignedVarLong(postingKeys[0]);
                    for (int i = 1; i < postingKeys.length; i++) {
                        out.putVarLong(postingKeys[i] - postingKeys[i - 1]);
                    }
                    postings.codec().encode(out, postingKeys, postingValues);
                }
                case Postings.Promoted<P>(Ref root) -> {
                    out.putByte(PROMOTED);
                    Ref.write(out, root);
                }
            }
        }
    }

    @Override
    public void decode(ByteCursor in, long[] keys, Object[] into) {
        for (int i = 0; i < into.length; i++) {
            if (in.getUnsignedByte() == PROMOTED) {
                into[i] = new Postings.Promoted<P>(Ref.read(in));
                continue;
            }
            int count = in.getVarInt();
            long[] postingKeys = new long[count];
            postingKeys[0] = in.getSignedVarLong();
            for (int k = 1; k < count; k++) {
                postingKeys[k] = postingKeys[k - 1] + in.getVarLong();
            }
            Object[] decoded = new Object[count];
            postings.codec().decode(in, postingKeys, decoded);
            into[i] = inline(postingKeys, Arrays.stream(decoded).map(postings::cast).toList());
        }
    }

    @Override
    public boolean holdsRefs() {
        return true;
    }

    @Override
    public Postings<P> mapRefs(Postings<P> value, RefMapper mapper) {
        return value instanceof Postings.Promoted<P>(Ref root) ? new Postings.Promoted<>(mapper.map(root, postings)) : value;
    }
}

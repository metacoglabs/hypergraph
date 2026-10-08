package io.hstore.engine.index;

import io.hstore.engine.page.ByteCursor;
import io.hstore.engine.page.PageId;
import io.hstore.engine.tree.FingerprintMode;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.Summary;
import io.hstore.engine.tree.TreeSchema;
import io.hstore.engine.tree.ValueCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class PostingsCodecTest {

    private static final TreeSchema<Long> LONGS = new TreeSchema<>(210, "postings-test", FingerprintMode.SET,
            ValueCodec.rows(ByteCursor::signedVarLongSize, ByteCursor::putSignedVarLong, ByteCursor::getSignedVarLong),
            (key, value, into) -> into.entry(key, Hashing.of(key, value)));
    private static final PostingsCodec<Long> CODEC = new PostingsCodec<>(LONGS, value -> value);
    private static final Ref ROOT = new Ref.Stored(PageId.pack(4, 8, 1), 3, Summary.EMPTY);

    @Test
    void promotedPostingsKeepTheirIdentityWhenNothingMoves() {
        Postings<Long> promoted = new Postings.Promoted<>(ROOT);
        assertSame(promoted, CODEC.mapRefs(promoted, (ref, _) -> ref));
        Ref moved = new Ref.Stored(PageId.pack(5, 0, 2), 3, Summary.EMPTY);
        Postings<Long> changed = CODEC.mapRefs(promoted, (ref, _) -> moved);
        assertNotSame(promoted, changed);
        assertEquals(new Postings.Promoted<>(moved), changed);
        List<Ref> visited = new ArrayList<>();
        CODEC.forEachRef(promoted, (ref, schema) -> {
            visited.add(ref);
            assertSame(LONGS, schema);
        });
        assertEquals(List.of(ROOT), visited);
    }
}

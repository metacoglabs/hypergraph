package io.hstore.engine.catalog;

import io.hstore.engine.page.PageId;
import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.tree.Ref;
import io.hstore.engine.tree.Summary;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class AtomCodecTest {

    private static final Ref MEMBERS = new Ref.Stored(PageId.pack(3, 10, 1), 4, Summary.EMPTY);
    private static final Ref ORDER = new Ref.Stored(PageId.pack(3, 20, 1), 2, Summary.EMPTY);
    private static final AtomRecord.EdgeRecord EDGE = new AtomRecord.EdgeRecord(7, EdgeKind.ORDERED, MEMBERS, ORDER, 1, 0, 0, 0);

    @Test
    void mappingRefsToThemselvesKeepsTheSameRecord() {
        assertSame(EDGE, AtomCodec.INSTANCE.mapRefs(EDGE, (ref, _) -> ref));
        Ref moved = new Ref.Stored(PageId.pack(9, 0, 2), 4, Summary.EMPTY);
        AtomRecord.EdgeRecord changed = (AtomRecord.EdgeRecord) AtomCodec.INSTANCE.mapRefs(EDGE, (ref, _) -> ref == MEMBERS ? moved : ref);
        assertNotSame(EDGE, changed);
        assertSame(moved, changed.members());
        assertSame(ORDER, changed.order());
    }

    @Test
    void forEachRefVisitsWhatMapRefsSees() {
        List<Ref> mapped = new ArrayList<>();
        AtomCodec.INSTANCE.mapRefs(EDGE, (ref, _) -> {
            mapped.add(ref);
            return ref;
        });
        List<Ref> visited = new ArrayList<>();
        AtomCodec.INSTANCE.forEachRef(EDGE, (ref, _) -> visited.add(ref));
        assertEquals(mapped, visited);
        List<Ref> none = new ArrayList<>();
        AtomCodec.INSTANCE.forEachRef(new AtomRecord.NodeRecord(1, "k", 0, 0, 0, 0, 0), (ref, _) -> none.add(ref));
        assertEquals(List.of(), none);
    }
}

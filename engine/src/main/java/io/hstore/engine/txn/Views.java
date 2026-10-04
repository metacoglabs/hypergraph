package io.hstore.engine.txn;

import io.hstore.engine.catalog.EngineSlots;
import io.hstore.engine.index.IndexValues.CanonicalName;
import io.hstore.engine.index.IndexValues.Incident;
import io.hstore.engine.index.Postings;
import io.hstore.engine.tree.Entry;
import io.hstore.engine.tree.Tree;

import java.util.OptionalLong;
import java.util.stream.Stream;

final class Views {

    private Views() {
    }

    static OptionalLong resolve(Tree<Postings<CanonicalName>> canonical, int tenant, int type, String key) {
        CanonicalName wanted = new CanonicalName(tenant, type, key);
        return EngineSlots.CANONICAL_INDEX.stream(canonical, EngineSlots.canonicalKey(tenant, type, key))
                .filter(entry -> entry.value().equals(wanted))
                .mapToLong(Entry::key)
                .findFirst();
    }

    static Stream<IncidentEdge> incident(Tree<Postings<Incident>> reverse, long atom) {
        return EngineSlots.REVERSE_INDEX.stream(reverse, atom)
                .map(entry -> new IncidentEdge(entry.key(), entry.value().roleSet(), entry.value().locator()));
    }
}

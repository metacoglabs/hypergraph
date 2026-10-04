package io.hstore.engine.catalog;

import io.hstore.engine.topology.EdgeKind;
import io.hstore.engine.tree.Hashing;
import io.hstore.engine.tree.Ref;

public sealed interface AtomRecord {

    int type();

    int tenant();

    int isolation();

    long dataRef();

    long contentHash();

    record NodeRecord(int type, String canonicalKey, long dataRef, long embeddingRef, int flags, int tenant, int isolation)
            implements AtomRecord {

        public static final int MAX_KEY_LENGTH = 512;

        public NodeRecord {
            if (canonicalKey != null && canonicalKey.length() > MAX_KEY_LENGTH) {
                throw new IllegalArgumentException("canonical key longer than " + MAX_KEY_LENGTH + " characters");
            }
        }

        public static NodeRecord of(int type, String canonicalKey) {
            return new NodeRecord(type, canonicalKey, 0, 0, 0, 0, 0);
        }

        public boolean hasCanonicalKey() {
            return canonicalKey != null;
        }

        public NodeRecord withDataRef(long reference) {
            return new NodeRecord(type, canonicalKey, reference, embeddingRef, flags, tenant, isolation);
        }

        public NodeRecord withEmbeddingRef(long reference) {
            return new NodeRecord(type, canonicalKey, dataRef, reference, flags, tenant, isolation);
        }

        @Override
        public long contentHash() {
            return Hashing.of(0, type, canonicalKey == null ? 0 : Hashing.of(canonicalKey), dataRef, embeddingRef, flags, tenant, isolation);
        }
    }

    record EdgeRecord(int type, EdgeKind kind, Ref members, Ref order, long version, long dataRef, int tenant, int isolation)
            implements AtomRecord {

        public static EdgeRecord of(int type, EdgeKind kind) {
            return new EdgeRecord(type, kind, Ref.EMPTY, Ref.EMPTY, 0, 0, 0, 0);
        }

        public long cardinality() {
            return members.count();
        }

        public long fingerprint() {
            return members.summary().fingerprint();
        }

        public EdgeRecord withRoots(Ref newMembers, Ref newOrder) {
            return new EdgeRecord(type, kind, newMembers, newOrder, version + 1, dataRef, tenant, isolation);
        }

        public EdgeRecord withDataRef(long reference) {
            return new EdgeRecord(type, kind, members, order, version, reference, tenant, isolation);
        }

        @Override
        public long contentHash() {
            return Hashing.of(1, type, kind.ordinal(), members.count(), members.summary().fingerprint(), version, dataRef, tenant, isolation);
        }
    }
}

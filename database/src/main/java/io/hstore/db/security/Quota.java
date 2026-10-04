package io.hstore.db.security;

import io.hstore.engine.HStoreException;

import java.util.Map;

public record Quota(long maxAtoms, long maxEdges, long maxPayloadBytes, long maxTransactionPages, long maxQueryPages) {

    public static final Quota UNLIMITED = new Quota(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);

    public Quota with(Map<String, Long> limits) {
        Quota quota = this;
        for (Map.Entry<String, Long> limit : limits.entrySet()) {
            long value = limit.getValue();
            quota = switch (limit.getKey().toLowerCase()) {
                case "atoms" -> new Quota(value, quota.maxEdges, quota.maxPayloadBytes, quota.maxTransactionPages, quota.maxQueryPages);
                case "edges" -> new Quota(quota.maxAtoms, value, quota.maxPayloadBytes, quota.maxTransactionPages, quota.maxQueryPages);
                case "payload_bytes" -> new Quota(quota.maxAtoms, quota.maxEdges, value, quota.maxTransactionPages, quota.maxQueryPages);
                case "transaction_pages" -> new Quota(quota.maxAtoms, quota.maxEdges, quota.maxPayloadBytes, value, quota.maxQueryPages);
                case "query_pages" -> new Quota(quota.maxAtoms, quota.maxEdges, quota.maxPayloadBytes, quota.maxTransactionPages, value);
                default -> throw HStoreException.invalid("unknown quota '" + limit.getKey()
                        + "', expected atoms, edges, payload_bytes, transaction_pages or query_pages");
            };
        }
        return quota;
    }

    public boolean admits(Usage usage) {
        return usage.atoms() <= maxAtoms && usage.edges() <= maxEdges && usage.payloadBytes() <= maxPayloadBytes;
    }

    public record Usage(long atoms, long edges, long payloadBytes) {
        public static final Usage NONE = new Usage(0, 0, 0);

        public Usage plus(Usage delta) {
            return new Usage(atoms + delta.atoms, edges + delta.edges, payloadBytes + delta.payloadBytes);
        }
    }
}

# Tenancy, users and quotas

Sources: `database/src/main/java/io/hstore/db/security/` (`Principal`, `Role`, `Security`, `Quota`),
`Reader`/`Writer`, `HypergraphDatabase`, and the engine key layouts in `engine/.../catalog/EngineSlots.java` and
`database/.../property/PropertySlots.java`. Network authentication (wire protocol, Studio sessions) is described
in [wire protocol](../operations/wire-protocol.md) and [Studio](../operations/studio.md).

## Principals and roles

```java
record Principal(String user, int tenant, Role role)
enum Role { ADMIN, WRITER, READER }
```

| Capability | ADMIN | WRITER | READER |
|---|:---:|:---:|:---:|
| read atoms of its tenant | yes | yes | yes |
| insert, update, delete atoms, members, properties, embeddings, evidence, state, signals | yes | yes | no |
| DDL (`CREATE … TYPE`, `CREATE INDEX`, `CREATE JSON INDEX`) | yes | no | no |
| branches (`CREATE/DROP/MERGE BRANCH`, `SAMPLE`, `DIFF`), `HISTORY`, `STATS`, `CHECKPOINT`, `COMPACT` | yes | no | no |
| tenants and users | yes | no | no |
| create, refresh and read materialized views (`SHOW VIEWS` lists names for every role) | yes | no | no |
| switch tenant (`USE TENANT`, `Principal.inTenant`) | yes | no | no |

Checks are enforced at two levels: `Executor.administrative` rejects ADMIN statements before execution, and the
Java API enforces roles itself (`Principal.requireWrite` in every `Writer` mutation and in
`HypergraphDatabase.write`, `requireAdmin` in `defineNode/defineEdge/createIndex/createJsonIndex` and
`MaterializedViews`), so embedding applications cannot bypass them by skipping HQL.

`Principal.SYSTEM` = `("system", tenant 0, ADMIN)` is used by embedded API calls that do not pass a principal, by
`hstore exec`/`hstore shell` on a local directory, and by network sessions when authentication is not required.

## Tenants

```java
record Tenant(int id, String name, Quota quota)
```

- Slot `TENANTS` = 44 (schema 81), keyed by tenant id. Tenant 0, `default`, always exists implicitly with
  unlimited quota (`Security.DEFAULT`); it is materialised only if its quota is altered.
- `CREATE TENANT` assigns `max(1, last id + 1)` with an `Optional::isEmpty` merge precondition, so concurrent
  creations of the same id conflict instead of overwriting.
- Names are compared case-insensitively.

### Isolation model

Every atom record carries its owning tenant, stamped from the writer's principal when the atom is created
(`Writer.node/edge`). Isolation is **structural**: the tenant is part of the key of every index that resolves or
enumerates atoms, so one tenant's lookups never touch another tenant's postings, and **checked**: every read path
re-verifies the record's tenant.

| Structure | Key layout | Effect |
|---|---|---|
| canonical keys (slot 4) | `Hashing.of(tenant, type, Hashing.of(key))` | the same key can exist in every tenant; `@Type:'key'` resolves only within the session's tenant |
| type index (slot 3) | `(tenant << 32) \| type` | `atomsOfType`, `countOfType`, `TypeScan` enumerate only the tenant's atoms |
| property index (slot 35) | `(tenant << 40) \| (propertyKey << 8) \| tag` | index scans and range estimates are per tenant |
| catalog records | `AtomRecord.tenant()` | `Reader.visible(atom)` ⇔ the record exists and `record.tenant() == principal.tenant()` |
| assertions, evidence | `Qualifier.tenant`, `Evidence.tenant` stamped on write | `Provenance.qualifier/evidence` filter by tenant |
| usage counters (slot 46) | tenant id | per-tenant accounting |

Every `Reader` method that accepts an atom id calls `visible` (or filters by tenant): `atom`, `require`,
`property`, `properties`, `document`, `edge`, `members`, `cardinality`, `degree`, `incident`, `embedding`,
`StateBindings.state`, `Signals.of`, `Temporal.activeEdges`. Writes call `require`, so a principal cannot modify
an atom it cannot see. A hyperedge may only gain members that are visible to the writer (`Writer.incidence`), so a
tenant cannot reference another tenant's atoms.

Shared, non-tenant-scoped structures:

| Structure | Why shared | Protection |
|---|---|---|
| schema (types, roles, property keys) | one catalog of type definitions | DDL requires ADMIN; type names are visible to all tenants |
| dictionary | interned strings | ids only |
| HNSW indexes | one per model | results filtered by `Reader.visible` ([semantic](semantic.md#tenant-visibility)) |
| materialized views | computed over the whole database | ADMIN only |
| payload store | append-only byte store | payloads are reachable only through tenant-checked properties and embeddings |
| branches, history, statistics | engine-level | ADMIN only (`STATS`, `HISTORY`, `DIFF`) |

```sql
CREATE TENANT clinic_b;
USE TENANT clinic_b;
INSERT NODE Lab 'b1' {name: 'B1'};
MATCH NODE l:Lab RETURN l;           -- only clinic_b's labs
USE TENANT default;
MATCH NODE l:Lab RETURN count(*);    -- clinic_b's labs are not counted
```

## Users

```java
record User(String name, String salt, String hash, int iterations, int tenant, Role role)
```

- Slot `USERS` = 45 (schema 82), keyed by `Hashing.of(name)`; lookups also compare the stored name, so a hash
  collision cannot authenticate the wrong user.
- Names match `[A-Za-z_][A-Za-z0-9_.@-]*`.
- Password hashing: **PBKDF2WithHmacSHA256**, 120,000 iterations, 16-byte salt from `SecureRandom`, 256-bit
  derived key, stored hex-encoded together with the iteration count (so the iteration count can be raised later
  without invalidating existing hashes).
- `Security.authenticate` re-derives with the stored salt and iterations and compares with
  `MessageDigest.isEqual` (constant time with respect to content).
- `ALTER USER … PASSWORD` re-salts; `DROP USER` deletes the record. Existing authenticated sessions are not
  revoked.
- `CREATE USER` uses an `Optional::isEmpty` precondition, so two concurrent creations of the same name conflict.

`HypergraphDatabase.requiresAuthentication()` is true as soon as any user exists; the server's
`authentication = auto` setting then requires `AUTHENTICATE` before any other statement. Authentication produces
`Principal(user, user.tenant, user.role)`.

The derivation cost is deliberate (≈0.2–0.5 s per attempt on current CPUs) to slow offline guessing if the data
directory leaks; failed attempts are logged by the server with the peer address.

## Quotas

```java
record Quota(long maxAtoms, long maxEdges, long maxPayloadBytes, long maxTransactionPages, long maxQueryPages)
record Usage(long atoms, long edges, long payloadBytes)
```

| Key (`QUOTA (key n, …)`) | Limit | Enforcement point |
|---|---|---|
| `atoms` | live atoms (nodes + edges) | `Security.charge` on `Writer.node/edge/delete` |
| `edges` | live hyperedges | `Security.charge` on `Writer.edge/delete` |
| `payload_bytes` | bytes appended to the payload store | `Security.charge` in `Writer.writePayload` (long texts, documents, embeddings) |
| `transaction_pages` | pages one transaction may write | `HypergraphDatabase.transactionOptions` → `TxnOptions.withMaxPages(min(db, tenant))` |
| `query_pages` | page visits of one query | `HypergraphDatabase.queryPageBudget` → `IoTrace` budget ([planner](planner.md#execution)) |

Usage is stored in slot `USAGE` = 46 (schema 83) per tenant. `Security.charge(txn, tenant, delta)` enforces the
limits in two stages:

```mermaid
sequenceDiagram
  participant W as Writer (txn T)
  participant S as Security.charge
  participant TM as TransactionManager (commit)
  W->>S: delta = Usage(+1, 0, 0)
  S->>S: eager check: usage(T's view) + delta admitted by quota?
  alt exceeds
    S-->>W: ABORTED_RESOURCE_LIMIT (fail fast, no work wasted)
  else admitted
    S->>W: txn.merge(USAGE, tenant, precondition, current + delta)
  end
  W->>TM: commit
  TM->>TM: rebase onto the latest generation: re-evaluate the precondition<br/>"quota admits latest usage + delta" (or delta ≤ 0), then apply
  alt precondition fails
    TM-->>W: RETRYABLE_CONFLICT → retried; the retry's eager check fails with ABORTED_RESOURCE_LIMIT
  end
```

Because the USAGE update is a merge with a precondition and a commutative change function, concurrent writers of
the same tenant do not abort each other while the quota is respected: the commit replays `current + delta` on the
newest counter. Only when the combined usage would exceed the quota does the precondition fail at commit, so the
limit holds exactly under concurrency. Deletions charge negative deltas and are always admitted.

`SHOW TENANTS` reports usage and the atom/edge limits. Evidence, assertion and signal records draw ids from the atom
allocator but are not charged as atoms.

## Network surface

- Wire protocol: `authentication = auto | on | off`; `AUTHENTICATE` is the only accepted statement before
  authentication; failed attempts are logged; passwords are masked in statement logs
  (`StatementLogger.abbreviate`).
- Studio: cookie sessions (`HttpOnly`, `SameSite=Strict`, 256-bit random tokens), a required custom header on
  every POST (CSRF), a strict Content-Security-Policy and an idle timeout.
- Neither protocol implements TLS; terminate TLS in front of the server (reverse proxy, service mesh) when traffic
  leaves a trusted network. Bind `listen_address` to `127.0.0.1` when the server should be local only.

## Threat model

| Threat | Mitigation | Residual risk |
|---|---|---|
| cross-tenant read through ids or keys | tenant in index keys plus `visible` checks on every path | type names and the existence of types are global |
| cross-tenant write or reference | `require` before mutations; members must be visible | none known |
| resource exhaustion by a tenant | atom/edge/payload quotas, per-query page budget, per-transaction page budget, HORA budgets | CPU in planning and projection is not budgeted; the HNSW index is shared |
| password database theft | PBKDF2-SHA256, 120k iterations, per-user salt | offline guessing of weak passwords remains possible |
| credential sniffing | none in-process | requires external TLS |
| privilege escalation | role checks in both HQL and the Java API | an embedded application holding `Principal.SYSTEM` is fully trusted |
| tampering with data files | CRC32C on pages, WAL records and payloads detects corruption | no cryptographic integrity or encryption at rest; protect the data directory with file-system permissions (the container runs as a non-root user with mode 0700) |

## Non-goals

### Distributed transactions (no two-phase commit)

HStore commits on a single node: one process owns the data directory (`LOCK` file), all transactions are ordered by
one generation counter and made durable by one group-commit barrier over one write-ahead log
([transactions](../transactions/transactions.md), [WAL and recovery](../transactions/wal-and-recovery.md)).
There is no prepare phase, no coordinator and no in-doubt state, and the engine does not participate in XA or other
two-phase-commit protocols.

This is deliberate:

- A prepared-but-undecided transaction would have to pin its base generation, hold its write set and survive
  restarts in the WAL; recovery would have to block on an external coordinator, which conflicts with the recovery
  rule that a restart exposes exactly the durable prefix of acknowledged commits.
- Optimistic validation at commit (rebase onto the newest generation) cannot promise in a prepare vote that a later
  commit will succeed without converting to locking for the prepared interval.
- Classic 2PC blocks all participants when the coordinator fails after prepare.

Cross-system consistency is instead achieved with idempotent, replayable integration:

- **Idempotent commits**: `TxnOptions.withRequestId(id)` stores `RequestRecord(id, txn, generation)` in the engine
  slot `REQUESTS`; re-committing a transaction with the same request id on the same branch returns the original
  commit (`CommitResult.Outcome.DUPLICATE`) instead of applying it twice. External systems can therefore retry a
  write until they observe success (outbox / at-least-once delivery with exactly-once effect).
- **Change feed**: every commit is published to an ordered, replayable feed (`ChangeFeed.subscribe(afterGeneration,
  consumer)`), from which downstream systems can be updated idempotently by generation number.

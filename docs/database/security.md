# Tenancy, users and quotas

Sources: `database/src/main/java/io/hstore/db/security/` (`Principal`, `Role`, `Security`, `Quota`),
`Reader`/`Writer`, `HypergraphDatabase`, and the engine key layouts in `engine/.../catalog/EngineSlots.java` and
`database/.../property/PropertySlots.java`. Network authentication (wire protocol, Studio sessions) is described
in [wire protocol](../operations/wire-protocol.md) and [Studio](../operations/studio.md).

## Principals and roles

```java
record Principal(String user, int tenant, Role role, int home)
enum Role { ADMIN, WRITER, READER }
```

`tenant` is the tenant a session currently works in and `home` is the tenant the user belongs to. They only
differ after an admin of the default tenant runs `USE TENANT`. An ADMIN whose home is the default tenant is a
**global admin** (`Principal.global()`); an ADMIN of any other tenant is a **tenant admin** and never leaves its
tenant.

| Capability | global admin | tenant admin | WRITER | READER |
|---|:---:|:---:|:---:|:---:|
| read atoms of its tenant | yes | yes | yes | yes |
| insert, update, delete atoms, members, properties, embeddings, evidence, state, signals | yes | yes | yes | no |
| create, alter and drop users of its own tenant; `SHOW USERS`, `SHOW TENANTS` (own tenant only) | yes | yes | no | no |
| create, refresh, drop and read materialized views (`SHOW VIEWS` lists names for every role) | yes | yes | no | no |
| users of other tenants, `CREATE/ALTER TENANT` | yes | no | no | no |
| DDL (`CREATE … TYPE`, `CREATE INDEX`, `CREATE JSON INDEX`); the schema is shared by every tenant | yes | no | no | no |
| branches (`USE BRANCH`, `SHOW BRANCHES`, `CREATE/DROP/MERGE BRANCH`, `SAMPLE`, `DIFF`), `HISTORY`, `STATS`, `CHECKPOINT`, `CHECKPOINT AT`, `COMPACT` | yes | no | no | no |
| switch tenant (`USE TENANT`, `Principal.inTenant`) | yes | no | no | no |

`USE MAIN` is open to everyone. Without `TENANT`, `CREATE USER` puts the new user in the session's current
tenant. A tenant admin who names another tenant, or another tenant's user, gets the same error as for a name that
does not exist, and `USE TENANT` answers the same way for an unknown tenant and a forbidden one, so none of these
statements reveal what exists elsewhere. User names are global, so `CREATE USER` with a name taken in another
tenant still fails.

Checks are enforced at two levels: `Executor.engineWide` and `Executor.administrative` reject statements before
execution, and the Java API enforces roles itself (`Principal.requireWrite` in every `Writer` mutation and in
`HypergraphDatabase.write`, `requireGlobal` in `defineNode/defineEdge/createIndex/createJsonIndex` and
`SwapSampler.run`, `requireAdmin` in `MaterializedViews`), so embedding applications cannot bypass them by
skipping HQL.

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
| materialized views (slot 42) | `Descriptor.tenant`, set from the creating admin | builds, deltas and staleness cover only that tenant; names, listings and `DROP VIEW` are per tenant; at most 32 views per tenant |

Every `Reader` method that accepts an atom id calls `visible` (or filters by tenant): `atom`, `require`,
`property`, `properties`, `document`, `edge`, `members`, `cardinality`, `degree`, `incident`, `embedding`,
`StateBindings.state`, `Signals.of`, `Temporal.activeEdges`. The HQL functions `weight` and `overlap` go through
`Statistics.weight/overlap`, which take a `Reader`. The one shortcut is `incident`: when the reader's principal is
in the default tenant and the type index holds no atom of another tenant, every atom is visible and a missing atom
has no incidence entries anyway, so the extra catalog lookup is skipped. The reader checks this once per snapshot.

Code that needs the raw engine `View` (`reader.view()`) may only pass it ids that are already known to belong to the
reader's tenant, such as bound query variables or atoms from `atomsOfType`. Ids that come from a statement go
through `Reader` first. The `MemberScan` and `IncidentIntersection` access paths return nothing for an id the
reader cannot see.

Writes call `require`, so a principal cannot modify an atom it cannot see. A hyperedge may only gain members that
are visible to the writer (`Writer.incidence`, also used by `Writer.qualify`), so a tenant cannot reference another
tenant's atoms. Evidence sources, assertion sources and the evidence list of `QUALIFY` must be atoms, evidence or
assertions of the writer's tenant (`Provenance.record/assertion`). `hstore check` lists any membership on main
whose member belongs to a different tenant than its edge, with the `REMOVE` statement that undoes it. Databases
written before these checks existed could have such memberships from `QUALIFY`.

The planner only uses numbers from the reader's tenant: untyped matches are estimated from `Reader.population`
(the sum of the tenant's per-type counts), and the feedback that corrects estimates after each query is kept per
tenant. `EXPLAIN` output therefore does not change when another tenant writes or queries.

Shared, non-tenant-scoped structures:

| Structure | Why shared | Protection |
|---|---|---|
| schema (types, roles, property keys) | one catalog of type definitions | DDL requires ADMIN; type names are visible to all tenants |
| dictionary | interned strings | ids only; property, state schema and model names are limited to 128 characters, and only a global admin can create an embedding model |
| HNSW indexes | one per model | results filtered by `Reader.visible` ([semantic](semantic.md#tenant-visibility)); vectors must match the model's length, and a row the index cannot take is logged and skipped ([semantic](semantic.md#embedding-records)) |
| payload store | append-only byte store | payloads are reachable only through tenant-checked properties and embeddings |
| branches, history, statistics | engine-level | global admins only (`USE BRANCH`, `SHOW BRANCHES`, `MERGE`, `STATS`, `HISTORY`, `DIFF`; Studio `/api/stats` and `/api/history`) |

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
- Passwords set by `CREATE USER`, `ALTER USER … PASSWORD` and `hstore init --superuser` must be 8 to 1,024
  characters long (`Security.requirePassword`). Passwords stored before this rule keep working until they are
  changed.
- Passwords are stored as SCRAM-SHA-256 verifiers (`Scram.Verifier`, [RFC 5802](https://www.rfc-editor.org/rfc/rfc5802)),
  written in the PostgreSQL format `SCRAM-SHA-256$<iterations>:<salt>$<StoredKey>:<ServerKey>`. `SaltedPassword`
  is PBKDF2-HMAC-SHA256 of the UTF-8 password with a 16-byte `SecureRandom` salt and 600,000 iterations;
  `StoredKey` is `SHA-256(HMAC(SaltedPassword, "Client Key"))` and `ServerKey` is
  `HMAC(SaltedPassword, "Server Key")`. Neither the password nor `SaltedPassword` is stored, so a copy of the
  users slot does not let anyone sign in without guessing the password first.
- Passwords go through SASLprep ([RFC 4013](https://www.rfc-editor.org/rfc/rfc4013)) before PBKDF2, as in
  PostgreSQL: a password made only of ASCII characters is used as it is; otherwise non-ASCII spaces become
  spaces, characters such as the soft hyphen are dropped and the result is NFKC-normalized. When the result would
  contain a character that SASLprep prohibits, or one unassigned in Unicode 3.2, the original password is used
  unchanged. The client applies the same rule, so `p\u00e4ssword` typed precomposed or decomposed signs in either
  way, and passwords set through other SCRAM clients and libraries match.
- Older versions stored the hex `SaltedPassword` itself. That value is exactly what a SCRAM client derives from
  the password, so a copy of it from an old backup would be as good as the password. This version therefore never
  uses an old record for SCRAM: such a user cannot sign in over the wire protocol, the server answers it as it
  answers an unknown name, and logs a WARNING naming the user. The user's next sign-in through Studio, or an
  `AUTHENTICATE` statement in an embedded or Studio session, checks the password against the old record and then
  replaces the record with a verifier under a new random salt and 600,000 iterations
  (`Security.replaceLegacy`), so old backups give nothing beyond an offline guess. An administrator can instead
  set a new password with `ALTER USER`. `hstore serve` logs a WARNING at startup listing the users that still
  have an old record. A version from before this change cannot read the converted records.
- `CREATE USER` and `ALTER USER … PASSWORD` accept a verifier in place of a password (made by `hstore verifier`
  or any SCRAM library), with 4,096 to 10,000,000 iterations and a salt of at least 8 bytes; the length rule cannot
  apply to it. A password that starts with `SCRAM-SHA-256$` but is not such a verifier is refused rather than
  stored as a password. The upper bound keeps one Studio or embedded sign-in, where the server runs PBKDF2, to a
  few seconds of CPU; clients refuse a server that asks for more.
- The wire protocol signs in with SCRAM ([wire protocol](../operations/wire-protocol.md#authentication)): the
  client proves knowledge of the password against `StoredKey` and the server proves knowledge of `ServerKey`.
  For a name that does not exist the server answers with a salt derived from the name and a 256-bit secret
  created when the database is first opened (stored under `EngineSlots.SETTINGS`), so the exchange looks the
  same for every name and only fails at its last step. The iteration count offered for an unknown name is
  600,000, so a user whose verifier was made with another count (for example one copied from PostgreSQL, which
  defaults to 4,096) can be told apart from an unknown name; PostgreSQL behaves the same way. Give such users a
  new password if their names must not be guessable.
- `Security.authenticate`, used by Studio's login, `AUTHENTICATE` statements in embedded and Studio sessions and
  the Java API, derives `StoredKey` from the given password and compares with `MessageDigest.isEqual`. For a name
  that does not exist it still runs PBKDF2 against a fixed salt, so the response time does not tell which names
  exist.
- Every sign-in, from the wire protocol, Studio or an `AUTHENTICATE` statement, goes through one throttle per
  database (`SignIns`): at most 10 failed attempts for one user name from one client address, and 20 from one
  address over all user names, in 15 minutes. Sign-ins without an address (the embedded API, `hstore exec` and
  `hstore shell` on a local directory) count 10 per user name. The slot is taken before the password is hashed,
  so parallel guesses cannot slip past the limit. A successful sign-in clears that user's count for that address
  and gives back the address's slot. A throttled attempt fails with `ABORTED_RESOURCE_LIMIT` without checking the
  password. Failures from other addresses never block a user, so nobody can lock an account out by failing on
  purpose. When one user name collects 50 failures in 15 minutes over all addresses, the server logs a WARNING
  that someone may be guessing its password from several addresses.
- The wire protocol and Studio wait `authentication_failure_delay_ms` (500 ms by default) before they answer a
  failed sign-in, which slows each guessing connection without delaying successful sign-ins.
- `ALTER USER … PASSWORD` re-salts; `DROP USER` deletes the record. A session checks its user's stored record
  before every statement, so once a user is dropped or its password changes, its open sessions fail with "was
  dropped or its password changed; authenticate again" until they authenticate again. A session that changes its
  own password keeps working.
- A tenant admin cannot drop the last user of the database, since with `authentication = auto` that would turn
  authentication off and give the next connection `Principal.SYSTEM`.
- `CREATE USER` uses an `Optional::isEmpty` precondition, so two concurrent creations of the same name conflict.

`HypergraphDatabase.requiresAuthentication()` is true as soon as any user exists; the server's
`authentication = auto` setting then requires `AUTHENTICATE` before any other statement. Authentication produces
`Principal(user, user.tenant, user.role)`.

The derivation cost is deliberate (≈0.2–0.5 s per attempt on current CPUs) to slow offline guessing if the data
directory leaks; over the wire protocol the client pays it, not the server. Failed attempts are logged by the
server with the peer address.

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

- While no user exists and `authentication = auto`, the wire protocol and Studio accept clients on the server's
  own host only; other hosts get `AUTHENTICATION_REQUIRED` (Studio: `403`) and are disconnected. The check runs
  for every request, so dropping the last user also cuts off sessions from other hosts. A Studio request counts as
  remote when the peer is not a loopback address or when `X-Forwarded-For` names a non-loopback client. Only
  `authentication = off` lets other hosts in without authentication, and the server logs a WARNING at startup
  when it listens beyond loopback in that mode, or with no users in `auto` mode.
- Wire protocol: `authentication = auto | on | off`; `AUTHENTICATE` is the only accepted statement before
  authentication; a connection has `authentication_timeout_seconds` (30) to authenticate and may send at most
  64 KiB per request until it has (`max_message_mb` after); a connection whose first request looks like HTTP is
  closed, so a web page cannot post HQL to the port; failed attempts are logged; `CREATE USER`, `ALTER USER` and
  `AUTHENTICATE` are logged from the parsed statement with the password replaced by `'***'`
  (`StatementLogger.describe`); control characters in log messages are escaped.
- Only `\n` ends a line of the wire protocol, so a `\r` inside a string literal cannot end a request early.
  Responses also break lines at `\r`, so stored text cannot fake the end of a response.
- Unexpected errors reach the client as `error [INTERNAL]` without the exception text; the server log has the
  details.
- Studio: cookie sessions (`HttpOnly`, `SameSite=Strict`, 256-bit random tokens, 12-hour lifetime, at most 4,096
  open), a required custom header on every POST (CSRF), a strict Content-Security-Policy and an idle timeout.
  While authentication is off, `/api/` answers only requests whose `Host` is an IP address, `localhost` or the
  listen address, which defeats DNS rebinding.
- Both protocols support TLS (`tls = on`); without it, put a TLS-terminating proxy in front when traffic leaves a
  trusted network. Bind `listen_address` to `127.0.0.1` when the server should be local only.

## Threat model

| Threat | Mitigation | Residual risk |
|---|---|---|
| cross-tenant read through ids or keys | tenant in index keys plus `visible` checks on every path, incident edges of other tenants skipped (also for memberships an older version let through), untyped matches scan only the tenant's own types, per-tenant planner estimates and view staleness | type names, user names, generation numbers and the global atom id sequence are visible to every tenant; cache hits and latency depend on what other tenants do, and semantic search walks one shared HNSW graph |
| cross-tenant write or reference | `require` before mutations; members must be visible | none known |
| resource exhaustion by a tenant | atom/edge/payload quotas (payload bytes are charged in their own transaction, so a rollback still counts them, as the payload store never shrinks), the tenant's page budget on every read statement, per-transaction page budget, HORA budgets that `LIMIT` cannot raise past 1,000,000 rows, `DEPTH` 1-64, `SIMILAR TO … TOP` 1-1000, expression and JSON nesting up to 256 levels, number literals up to 400 characters with exponents up to 1000, 32 views per tenant, 128-character names, new embedding models only by a global admin | CPU in planning and projection is not budgeted; the HNSW index is shared; a WRITER can still add new property names, role names (on edge types that declare no roles) and state schemas, which stay in the shared dictionary |
| password database theft | SCRAM verifiers: PBKDF2-SHA256, 600k iterations, per-user salt, no stored password or salted password | offline guessing of weak passwords remains possible |
| online password guessing | one throttle for every sign-in path: 10 failures per user and address, 20 per address in 15 minutes; a delay before every failed answer; a warning when one user collects 50 failures; passwords of at least 8 characters | guessing spread over many addresses is slowed and logged, not stopped |
| credential sniffing | SCRAM-SHA-256 on the wire protocol, with channel binding over TLS; clear-text `AUTHENTICATE` refused there; `tls = on` for Studio | Studio's login form and `CREATE USER`/`ALTER USER` passwords are clear text without TLS (pass a verifier instead) |
| privilege escalation | role checks in both HQL and the Java API; tenant admins cannot leave their tenant; sessions that never authenticated must authenticate once the first user exists; sessions end when their user is dropped or its password changes; tenant admins cannot drop the last user | an embedded application holding `Principal.SYSTEM` is fully trusted |
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

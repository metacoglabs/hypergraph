# Tenancy, users and quotas

Sources: `database/src/main/java/io/hstore/db/security/` (`Principal`, `Role`, `Security`, `Quota`),
`Reader`/`Writer`, `HypergraphDatabase`, and the engine key layouts in `engine/.../catalog/EngineSlots.java` and
`database/.../property/PropertySlots.java`. Network authentication (wire protocol, Studio sessions) is described
in [wire protocol](../operations/wire-protocol.md) and [Studio](../operations/studio.md).

## Principals and roles

```java
record Principal(String user, int tenant, Role role, int home)
enum Role { ADMIN, WRITER, READER, NONE }
```

`tenant` is the tenant a session currently works in and `home` is the tenant the user belongs to. They only
differ after an admin of the default tenant runs `USE TENANT`. An ADMIN whose home is the default tenant is a
**global admin** (`Principal.global()`); an ADMIN of any other tenant is a **tenant admin** and never leaves its
tenant.

| Capability | global admin | tenant admin | WRITER | READER | NONE |
|---|:---:|:---:|:---:|:---:|:---:|
| read atoms of its tenant | yes | yes | yes | yes | types with a READ grant |
| insert, update, delete atoms, members, properties, embeddings, state, signals | yes | yes | yes | no | types with a WRITE grant |
| record evidence and assertions (`EVIDENCE`, `QUALIFY`) | yes | yes | yes | no | no |
| create, alter and drop users of its own tenant, grant and revoke type privileges to them; `SHOW USERS`, `SHOW TENANTS` (own tenant only), `SHOW GRANTS FOR` | yes | yes | no | no | no |
| create, refresh, drop and read materialized views (`SHOW VIEWS` lists names for every role) | yes | yes | no | no | no |
| users of other tenants, `CREATE/ALTER TENANT`, `GRANT MONITOR`, `GRANT MAINTAIN` | yes | no | no | no | no |
| DDL (`CREATE … TYPE`, `CREATE INDEX`, `CREATE JSON INDEX`); the schema is shared by every tenant | yes | no | no | no | no |
| branches (`USE BRANCH`, `SHOW BRANCHES`, `CREATE/DROP/MERGE BRANCH`, `SAMPLE`, `DIFF`), `HISTORY` | yes | no | no | no | no |
| `STATS` and Studio's dashboard | yes | no | with `MONITOR` (default tenant) | with `MONITOR` (default tenant) | with `MONITOR` (default tenant) |
| `CHECKPOINT`, `CHECKPOINT AT`, `COMPACT` | yes | no | with `MAINTAIN` (default tenant) | with `MAINTAIN` (default tenant) | with `MAINTAIN` (default tenant) |
| switch tenant (`USE TENANT`, `Principal.inTenant`) | yes | no | no | no | no |

### Privileges on types

READER, WRITER and ADMIN cover every type of their tenant, like PostgreSQL's `pg_read_all_data` and
`pg_write_all_data`. A user with `ROLE none` starts with no access to data and receives it per type:

- `GRANT READ ON TYPE T TO u` lets `u` see atoms of `T`; `GRANT WRITE ON TYPE T TO u` also lets it create them and
  change their properties, members, documents, embeddings, state and signals. `WRITE` includes `READ`.
  `REVOKE WRITE` leaves `READ`; `REVOKE READ` removes both. Grants on types are refused for users whose role
  already covers every type.
- An atom of a type the user cannot read is invisible exactly like an atom of another tenant: `Reader.visible`,
  `atom`, `require` and every lookup built on them (see the isolation model below) check the type as well as the
  tenant, so `DESCRIBE`, properties, incidences, degrees, matches and semantic hits leave it out. Naming the type
  in `MATCH`, `RANK`, `PATTERN` or a HORA statement fails with `no READ privilege on type T`, as `SELECT` on a table
  without the privilege fails in PostgreSQL.
- A hyperedge the user can read shows the ids of all its members, including members of types it cannot read,
  the way a foreign-key column shows the key of a row in a table you cannot select from. Those members have no
  label, properties or incidences for the user, and adding one to an edge fails as if it did not exist.
- Writing to a hyperedge needs `WRITE` on the edge's type; the members added must be readable, also for set
  algebra `INTO`. Deleting an atom also removes it from every hyperedge that contains it, so a user with
  `ROLE none` may only delete an atom when it can write every hyperedge of its tenant that holds it.
- Referring to an atom by key (`@T:'key'`) needs `READ` on `T`; without it the reference fails with the
  privilege error whether or not the key exists, so keys of unreadable types cannot be probed.
- Evidence and assertions (`TRACE`, `EVIDENCE`, `QUALIFY` policies) belong to the tenant, not to a type, so a user
  with `ROLE none` neither sees nor records them.
- `EXPLAIN` for a user with `ROLE none` estimates property-index ranges as a tenth of the type's readable atoms
  instead of counting the index, whose entries span every type, and neither uses nor feeds the tenant's
  cardinality feedback.
- Grants are kept in slot `GRANTS` = 75 (schema 110), keyed by user and type. `Reader` reads them from the latest
  committed generation of main, not from the statement's snapshot, so a grant or revoke applies to the next
  statement of every open session, inside an open transaction too, and `AT GENERATION` or `AS OF` cannot bring
  back a revoked privilege. As in PostgreSQL, statements that already ran keep their effect. `DROP USER`
  removes the user's grants, so a user later created with the same name starts without them.

### Capabilities

`GRANT MONITOR TO u` allows `STATS` and Studio's dashboard; `GRANT MAINTAIN TO u` allows `CHECKPOINT`,
`CHECKPOINT AT` and `COMPACT`. They resemble PostgreSQL's `pg_monitor` and `pg_checkpoint`/`pg_maintain`: an
operator can watch and maintain the engine without being a global admin. Only global admins grant them, and only
to users of the default tenant, since the statements report on or act on every tenant. `HISTORY`, branches and
DDL stay with global admins.

### Password expiry and connection limits

- `VALID UNTIL '<timestamp>'` on `CREATE USER` or `ALTER USER` makes the password stop working at that instant;
  `VALID UNTIL 'infinity'` removes the limit. As in PostgreSQL it expires the password, not the account: open
  sessions continue. A sign-in with an expired password fails with the usual `authentication failed`, over SCRAM
  only at the last step, and the server logs `user "u" tried to sign in after its password expired`.
- `CONNECTION LIMIT n` caps the user's signed-in sessions (wire connections, Studio sessions and embedded sessions
  that authenticated) at `n`; `-1` means no limit. The next sign-in fails with `ABORTED_RESOURCE_LIMIT: too many
  connections for user u`. Global admins are exempt, as PostgreSQL exempts superusers.
- Both live in slot `USER_OPTIONS` = 76 (schema 111); a user without an entry has no expiry and no limit.

### Changing roles

`ALTER USER u ROLE r` changes the role; `u`'s open sessions then fail with "authenticate again" until they sign in
again, so the new role applies at once. Only a global admin may change the role, password expiry or connection
limit of an ADMIN, so a tenant admin cannot lift limits put on itself or on another admin of its tenant.

A change that would leave the default tenant without an ADMIN whose password still works (dropping one while other
users exist, giving it another role, or expiring its password now) is refused. The statement also places a
commit-time precondition on every other such admin, so two concurrent transactions that each remove a different
admin cannot both commit. Should every admin still be lost (for example to a forgotten password), `hstore shell` on
the data directory runs as the built-in `system` admin, like PostgreSQL's single-user mode.

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

Query code reads through `Reader` only. Besides the methods above, `Reader` offers the tenant-checked lookups that
query paths need: `incidence(edge, member)` and `contains(edge, member)` (the edge must be the reader's and the
member owned, checked inside the same catalog lookup), `incidentEdges(atom)` (edges the reader owns),
`hyperedge(id)`, `isEdge(id)`, and `incidentTree(atom)` / `incidentCursor(atom)` (empty for an atom the reader
cannot see; the edge ids they yield still go through `Executor.admits` or `owns` before use). `MATCH`, `RANK`,
`PATTERN`, HORA operators and temporal queries use nothing else.

The raw engine `View` is available as `reader.engineView()` for code that works across tenants by design: user,
tenant and quota records, the SCRAM secret, the shared schema listing, branch merges and `DIFF` (global admins
only), materialized view maintenance (filtered by the view's tenant), and records that carry their own tenant
stamp (evidence, qualifiers, state, signals). `TenantBoundaryTest` in the database and server modules lists the
files allowed to call it, or to reach data around `Reader` through `writer.transaction()` or engine snapshots, each
with its reason, and fails when any other file does, or when a listed file no longer needs it. Adding a raw read therefore means stating why it is safe in that list.

Writes call `require`, so a principal cannot modify an atom it cannot see. A hyperedge may only gain members that
are visible to the writer (`Writer.incidence`, also used by `Writer.qualify`), so a tenant cannot reference another
tenant's atoms. Evidence sources, assertion sources and the evidence list of `QUALIFY` must be atoms, evidence or
assertions of the writer's tenant (`Provenance.record/assertion`). `hstore check` lists any membership on an active branch
whose member belongs to a different tenant than its edge, with the statement that undoes it, and
`hstore check <dir> --repair-tenants` removes them in one transaction per branch. Databases written before these checks
existed could have such memberships from `QUALIFY`. A new database, and one that `hstore check` found clean or
repaired, records that in `EngineSlots.SETTINGS`; `hstore serve` logs a WARNING at startup for a database without
that record.

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

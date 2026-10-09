# Logging

HStore logs through `System.Logger`. The server binds it to `java.util.logging` and installs a
PostgreSQL-style formatter
([`Logging.java`](../../server/src/main/java/io/hstore/server/Logging.java)). Logs always go to **stderr**, so
`docker logs` and `journalctl` capture them with no further configuration. Optionally they are also written to
size-rotated files.

## Line format

```
2026-10-04 06:33:30.334 IST [28997] LOG:  [server] connection 2 authorized: user=admin tenant=0 role=ADMIN
└──────────── timestamp ───────────┘ └pid┘ └sev┘  └component┘ └──────────────── message ────────────────┘
```

| Field | Content |
|---|---|
| timestamp | `yyyy-MM-dd HH:mm:ss.SSS zzz` in the JVM default zone (`UTC` in the container) |
| pid | operating-system process id (`1` for the server inside Docker) |
| severity | `ERROR` (SEVERE), `WARNING`, `LOG` (INFO) or `DEBUG` (FINE and below) |
| component | last segment of the logger name, for example `hstore.server` → `[server]` |
| message | `MessageFormat`-formatted text (numbers use locale grouping: `lsn 158,332`) |

If a record carries an exception, a `DETAIL:  <exception>` line follows, then one `\tat <frame>` line per stack
frame.

Lines written by the Docker entrypoint before the server starts use the shorter form
`2026-10-04 01:04:01 UTC [entrypoint] message`.

## Severity threshold

`log_level` sets the root level and the level of every handler:

| `log_level` | JUL level | Emitted severities |
|---|---|---|
| `debug` | `FINE` | DEBUG, LOG, WARNING, ERROR |
| `info` (default) | `INFO` | LOG, WARNING, ERROR |
| `warning` | `WARNING` | WARNING, ERROR |
| `error` | `SEVERE` | ERROR |

The embedded commands (`shell`, `exec`, `check`, `bench`) force the level to `warning`, so their stdout stays
clean. `init` uses the configured level.

## Components

| Component | Logger | Source |
|---|---|---|
| `main` | `hstore.main` | process start and shutdown (`Main`) |
| `server` | `hstore.server` | wire-protocol connections (`Server`) |
| `statement` | `hstore.statement` | statement logging from the wire protocol and Studio (`StatementLogger`) |
| `studio` | `hstore.studio` | Studio HTTP server (`Studio`) |
| `engine` | `hstore.engine` | open, checkpoint, compaction, reclamation, shutdown (`StorageEngine`) |
| `recovery` | `hstore.recovery` | crash recovery (`Recovery`) |
| `txn` | `hstore.txn` | branch lifecycle (`TransactionManager`) |
| `commit` | `hstore.commit` | group-commit pipeline (`GroupCommitter`) |
| `feed` | `hstore.feed` | change-feed retention and subscribers (`ChangeFeed`) |
| `wal` | `hstore.wal` | write-ahead log (`WriteAheadLog`; DEBUG only) |
| `semantic` | `hstore.semantic` | HNSW index persistence (`SemanticPlane`) |
| `views` | `hstore.views` | continuous materialised views (`MaterializedViews`) |
| `hstore` | `hstore` | background maintenance thread failures |

## Message catalogue

This is every message the server emits. Placeholders are in `{braces}`.

### Lifecycle

| Severity | Component | Message | Meaning |
|---|---|---|---|
| LOG | main | `starting hstore {version} on Java {runtime}` | First line of `serve`. |
| LOG | main | `configuration: {key}={value} ({source}), …` | Settings that differ from their default, with source `file`, `environment` or `command line`. |
| LOG | recovery | `recovered generation {g}: replayed {n} commits from lsn {lsn}, discarded {t} unfinished transactions and {c} non-durable commits` | Always logged at open. `{lsn}` is the checkpoint LSN replay started from. |
| WARNING | recovery | `durable prefix ends before generation {g}: transaction {txn} references pages that never reached storage` | In `references` WAL mode, a committed transaction whose `PageRef` pages fail verification ends the recovered prefix. Later commits are discarded. Seen only after a crash with `durability = async` or a storage fault. |
| LOG | engine | `database at {dir} is ready: generation {g}, {n} data segments, page size {bytes}, {durability} durability, {walMode} wal` | Engine open complete. |
| LOG | semantic | `loaded semantic index at generation {g} ({n} models)` | HNSW indexes restored from `<data>/semantic`. |
| WARNING | semantic | `semantic index at generation {g} is ahead of the database; rebuilding` | The index state file is newer than the recovered generation, for example after an `async` crash. |
| WARNING | semantic | `semantic index files are unreadable; rebuilding from embeddings` | Corrupt or partial index files. Rebuilt from stored embeddings. |
| WARNING | semantic | `change feed no longer covers semantic index generation {g}; rebuilding` | The saved index is older than the oldest retained feed generation, so it cannot be caught up. |
| WARNING | semantic | `could not persist the semantic index` | I/O error while writing index files at a checkpoint or at close. Retried at the next checkpoint. |
| LOG | studio | `studio listening on {http\|https}://{host}:{port}` | Studio started; `https` when `tls = on`. |
| LOG | server | `listening on {host}:{port}` (suffix ` with TLS` when `tls = on`) | Wire protocol accepting connections. |
| LOG | server | `connection {n} from {peer} failed the TLS handshake: {reason}` | A client could not complete the handshake, for example an untrusted certificate. |
| LOG | server | `connection {n} from {peer} did not complete the TLS handshake in time` | No handshake within 10 seconds, typically a plaintext client on a TLS port. |
| LOG | main | `received shutdown request; closing connections and checkpointing` | SIGTERM or SIGINT received. |
| LOG | engine | `checkpoint complete: generation {g}, lsn {lsn}, {n} wal segments retained, {ms} ms` | Background, explicit (`CHECKPOINT;`), compaction and shutdown checkpoints. |
| WARNING | engine | `checkpoint listener failed` | A post-checkpoint hook (semantic index persistence) threw. The checkpoint itself succeeded. |
| LOG | engine | `database at {dir} shut down cleanly` | Final line of a clean shutdown. Its absence from the last run's log means the next start performed crash recovery. |

### Maintenance and transactions

| Severity | Component | Message |
|---|---|---|
| LOG | engine | `background compaction relocated segments {list}` (write-volume-triggered pass) |
| LOG | engine | `compaction relocated segments {list} and reclaimed {list}` |
| LOG | engine | `reclaimed retired segments {list}` |
| WARNING | hstore | `background maintenance failed` (with DETAIL) |
| LOG | txn | `created branch {name} (#{id}) from generation {g}` |
| LOG | txn | `branch #{id} is now {MERGED or DROPPED}` |
| ERROR | commit | `commit pipeline failed; the engine must be restarted` (with DETAIL). The flusher thread could not make a batch durable. All waiting and future committers fail. |
| LOG | feed | `change feed released {bytes} bytes; generations after {g} are retained`: retention after a checkpoint deleted whole feed segments (`feed_retention_generations`, lowered by holds). |
| WARNING | feed | `change feed subscriber stopped at generation {g}` (with DETAIL) |
| DEBUG | feed, wal | `directory sync is not supported for {dir}`: the file system rejected an `fsync` of a directory after a segment was created or deleted. |
| WARNING | views | `continuous view refresh failed` (with DETAIL) |

### Startup failures

A failure while opening the database is not logged. `hstore` prints it as `hstore: <message>` and exits `2`,
and in Docker it is the last line of `docker logs`. The storage-integrity failures are:

| Message | Meaning |
|---|---|
| `database <dir> uses storage format N; this build reads format 4 (page directory); export and reload it` | `FORMAT` was written by an incompatible build. |
| `database uses N byte pages, options request M` | `page_size` differs from the value fixed at `init`. |
| `write-ahead log segment <file> is damaged (<reason>) but later segments exist; replay would silently drop committed transactions` | `CORRUPT_LOG`: damage in a non-tail WAL segment. A torn record at the end of the *last* segment is normal after a crash. Replay simply ends there. Damage before later segments means storage corruption, so recovery refuses to guess. Restore from backup. |

### Connections (`log_connections = on`)

| Severity | Message |
|---|---|
| LOG | `connection {n} received from /{ip}:{port}` |
| LOG | `connection {n} authorized: user={user} tenant={tenantId} role={ROLE}` |
| WARNING | `password authentication failed for user "{user}" from /{ip}:{port}` (logged whatever `log_connections` says) |
| WARNING | `connection {n} from /{ip}:{port} closed after 5 failed authentication attempts`: any five rejected requests (wrong password or not `AUTHENTICATE`) before login close the connection. |
| LOG | `disconnection: connection {n} from /{ip}:{port} session time {ms} ms` |
| LOG | `connection {n} closed after {s}s idle` (`idle_timeout_seconds` > 0) |
| WARNING | `connection from /{ip}:{port} refused: max_connections={n} reached` |
| LOG | `studio session opened for user={user} role={ROLE} from /{ip}:{port}` |
| WARNING | `studio password authentication failed for user "{user}" from /{ip}:{port}` |

The connection log is written lazily: `connection {n} received` appears when the first request arrives. A
connection that closes without sending anything, such as a `hstore ping` health probe, logs its disconnection
only at DEBUG. A 10-second Docker health check therefore does not flood the log.

### Statement errors

| Severity | Message |
|---|---|
| LOG | `connection {n}: {parse error}`: the script failed to parse, so nothing ran. |
| LOG | `connection {n} user {user}: {message} [{CODE}]`: a retryable failure (a serialization conflict). |
| WARNING | `connection {n} user {user}: {message} [{CODE}]`: a non-retryable failure. |
| ERROR | `connection {n} statement failed` (with DETAIL): an unexpected exception. Please report it. |
| ERROR | `studio request {path} failed` (with DETAIL): an unexpected exception in a Studio handler. |

## Statement logging

[`StatementLogger`](../../server/src/main/java/io/hstore/server/StatementLogger.java) is shared by the wire
protocol (origin `connection {n}`) and Studio (origin `studio`). A statement is logged when either condition
holds:

1. it matches `log_statement`:
   * `ddl`: `CREATE … TYPE/INDEX/JSON INDEX/VIEW/BRANCH`, `DROP BRANCH`, `CREATE/ALTER TENANT`, `CREATE/ALTER/DROP USER`;
   * `mod`: everything that is not a read. Reads are queries, `EXPLAIN`, `MEMBERS`, `INCIDENT TO`, `DESCRIBE`,
     HORA operators, `HISTORY`, `STATS`, `SHOW …`, `WHOAMI`, `DIFF …`, signal resolution and `TRACE`;
   * `all`: every statement;
2. its execution took at least `log_min_duration_ms` (when that setting is ≥ 0).

```
LOG:  [statement] connection 2 user admin duration: 212 ms  statement: CreateUser CREATE USER bob PASSWORD '***' ROLE reader
```

The record contains the statement's AST node name (`CreateUser`) followed by that statement's own source text,
as recovered by `Parser.sourced`. The text is whitespace-collapsed, truncated to 200 characters, and has every
`PASSWORD '…'` literal replaced with `PASSWORD '***'`. A request with several statements produces one line
per logged statement, each with only its own text (without the terminating `;`).

## Files and rotation

With `log_directory` set, a `FileHandler` writes the same formatted lines to `<dir>/hstore-0.log`,
`hstore-1.log`, …. When the active file reaches `log_rotation_mb` MiB, files rotate (generation `%g`), and at
most `log_file_count` files are kept. Files are opened in append mode, so restarts continue the current file.

```
hstore serve /srv/hstore --log_directory /var/log/hstore --log_rotation_mb 128 --log_file_count 4
```

In Docker, prefer stderr plus the container runtime's log driver. If you use `log_directory`, mount a volume
for it.

## Embedded use

Applications embedding `io.hstore.db` get the same records through `System.Logger`. Without further setup
the JDK's default `java.util.logging` console format applies. To capture them, configure JUL or bridge
`System.LoggerFinder` to your logging framework.

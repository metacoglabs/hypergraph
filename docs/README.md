# HStore documentation

Start here

## Architecture

* [architecture/overview.md](architecture/overview.md): map of the code base. A read and a write traced from the API down to disk; generations as immutable root vectors.

## Storage engine

* [storage/pages.md](storage/pages.md): byte-exact node-image format (80-byte header, CRC32C), packed segment files, `PageId` addressing.
* [storage/persistent-tree.md](storage/persistent-tree.md): the copy-on-write counted B+tree: node layout, write scopes, monoid summaries, leapfrog intersection, diff.
* [storage/topology.md](storage/topology.md): how atoms, hyperedge memberships and reverse incidence are laid out in trees and kept consistent.
* [storage/catalog-and-generations.md](storage/catalog-and-generations.md): slots, root vectors, generations, branches, history retention and checkpoint images.
* [storage/change-feed.md](storage/change-feed.md): the logical, generation-ordered commit log: segment format, subscriptions, holds and retention.
* [storage/maintenance.md](storage/maintenance.md): checkpoints, segment compaction and reclamation, and tree verification (`hstore check`).

## Transactions and durability

* [transactions/transactions.md](transactions/transactions.md): workspaces, the operation log, fast and slow commit paths, rebase, conflict detection, isolation levels, group commit.
* [transactions/wal-and-recovery.md](transactions/wal-and-recovery.md): WAL byte format, page-reference and page-image modes, write and sync ordering, crash points, recovery and the durable-prefix rule.

## Database layer

* [database/data-model.md](database/data-model.md): logical data model (types, atoms, properties, roles, documents, payloads) and its mapping onto engine slots.
* [database/hql.md](database/hql.md): HQL reference: every statement, its grammar and examples.
* [database/planner.md](database/planner.md): how `MATCH` is planned (access paths, cost estimates, `EXPLAIN`, `TRACE`) and executed.
* [database/hora.md](database/hora.md): higher-order relational algebra: gather, scatter, propagate, overlap join, closure, pattern matching.
* [database/semantic.md](database/semantic.md): the semantic plane: embeddings, encoders, the persistent HNSW index, freshness.
* [database/evidence-and-temporal.md](database/evidence-and-temporal.md): assertions, evidence and provenance; valid time and transaction time.
* [database/views-signals-stats.md](database/views-signals-stats.md): materialised views, signals and statistics.
* [database/security.md](database/security.md): tenants, users, roles, quotas and how isolation is enforced in key layouts.

## Operations

* [operations/docker.md](operations/docker.md): running the image as you would PostgreSQL: environment, init scripts, health check, logs, shutdown, backups.
* [operations/configuration.md](operations/configuration.md): every setting, precedence of `hstore.conf`, `HSTORE_*` and flags, and tuning.
* [operations/logging.md](operations/logging.md): log line format, severities, components and the complete message catalogue; statement logging.
* [operations/cli.md](operations/cli.md): every `hstore` command with examples and exit codes.
* [operations/wire-protocol.md](operations/wire-protocol.md): framing, authentication handshake, error codes, session state, writing a client.
* [operations/studio.md](operations/studio.md): HStore Studio: pages, hypergraph visualisation, shortcuts, HTTP API and security.

## Evaluation

* [benchmarks.md](benchmarks.md): methodology and results of the comparison against HyperGraphDB.

## Contributing

* [development.md](development.md): repository layout, toolchain, build and tests, conventions, recipes for new statements and settings, Studio development, running the benchmarks.

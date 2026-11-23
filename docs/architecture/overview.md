# Architecture overview

HStore is a native, persistent hypergraph store. Every structure it keeps on disk (atoms, hyperedge memberships, reverse incidence, secondary indexes, schema, users) is a copy-on-write counted B+tree stored in fixed-size pages. A commit publishes a new **generation**: an immutable vector of tree roots, one per *slot*. Readers pin a generation and never block writers. Writers build new roots in private and publish them through a write-ahead log and a group-commit barrier. A commit logs the keys it changed and keeps its new tree nodes in memory; a checkpoint later writes them to pages, once for all the commits since the previous one.

This document maps the code base and follows a read and a write from the API down to the bytes on disk. The other documents cover each layer in depth:

| Topic | Document |
|---|---|
| Page and segment byte layout | [storage/pages.md](../storage/pages.md) |
| Copy-on-write counted B+tree, summaries, algebra | [storage/persistent-tree.md](../storage/persistent-tree.md) |
| Hyperedge representation, incidence, reverse index | [storage/topology.md](../storage/topology.md) |
| Root vectors, slots, generations, branches, change feed | [storage/catalog-and-generations.md](../storage/catalog-and-generations.md) |
| Checkpoints, compaction, verification | [storage/maintenance.md](../storage/maintenance.md) |
| Transactions, isolation, rebase, group commit | [transactions/transactions.md](../transactions/transactions.md) |
| WAL format, recovery and crash points | [transactions/wal-and-recovery.md](../transactions/wal-and-recovery.md) |
| Data model, HQL, planner, semantic plane, security | [database/](../database/) |
| Docker, configuration, logging, CLI, wire protocol, Studio | [operations/](../operations/) |
| HyperGraphDB comparison | [benchmarks.md](../benchmarks.md) |
| Building and testing | [development.md](../development.md) |

## Layers

```mermaid
flowchart TB
    subgraph server["io.hstore.server (CLI, TCP server, Studio)"]
        CLI["Main: init / serve / shell / exec / connect / ping / bench / check / config"]
        TCP["Server + WireProtocol (virtual thread per connection)"]
        STUDIO["studio.Studio (HTTP + static console)"]
    end
    subgraph db["io.hstore.db (database)"]
        HQL["query: Lexer, Parser, Planner, Executor, Session"]
        RW["Reader / Writer"]
        EXT["schema, property, security, evidence, temporal, semantic, hora, view, signal, stats, payload"]
    end
    subgraph engine["io.hstore.engine (storage engine)"]
        TXN["txn: TransactionManager, Transaction, Snapshot, Workspace, GroupCommitter"]
        TOPO["topology: Hyperedge, Incidence"]
        TREE["tree: Tree, TreeWriter, TreeCursor, TreeAlgebra, TreeDiff"]
        CAT["catalog: Generation, Branch, RootVector, Slot, CatalogStore"]
        WAL["wal: WriteAheadLog"]
        FEED["feed: ChangeFeed"]
        PAGE["page: PageStore, PageHeader, ByteCursor"]
        MAINT["maintenance: Checkpointer, Compactor, Recovery"]
    end
    CLI --> HQL
    TCP --> HQL
    STUDIO --> HQL
    STUDIO --> RW
    HQL --> RW
    RW --> TXN
    EXT --> TXN
    TXN --> TOPO --> TREE
    TXN --> CAT
    TXN --> WAL
    TXN --> FEED
    TREE --> PAGE
    MAINT --> CAT
    MAINT --> PAGE
    MAINT --> WAL
```

* **Engine** (`engine/`, JPMS module `io.hstore.engine`). It knows nothing about types, properties or HQL. It stores *atoms* (records in the `catalog` slot) and *hyperedge memberships* (nested trees referenced from edge records). It also keeps a small set of engine-derived indexes, and any number of extension slots registered by the layer above.
* **Database** (`database/`, module `io.hstore.db`, `requires transitive io.hstore.engine`). This layer adds schema and typed properties, payloads, tenants and security, evidence and provenance, temporal bindings, embeddings and HNSW search, and the higher-order operators (HORA). It also provides materialized views and signals, plus the HQL language. It plugs into the engine through *extension slots* and *derivations* (`EngineOptions.withExtensions`); see [catalog-and-generations.md](../storage/catalog-and-generations.md#extension-slots).
* **Server** (`server/`, module `io.hstore.server`, requires `io.hstore.db`, `java.logging` and `jdk.httpserver`). This layer holds the `hstore` command-line tool, the line-oriented TCP protocol (`Server`, `WireProtocol`), and Postgres-style logging (`Logging`). It also contains the configuration layering (`ServerConfig`, `Setting`) and the Studio web console (`server/studio/Studio.java` plus static assets under `server/src/main/resources/studio`).

### JPMS module graph

```mermaid
flowchart LR
    server["io.hstore.server"] --> db["io.hstore.db"]
    server --> logging["java.logging"]
    server --> http["jdk.httpserver"]
    db -- "requires transitive" --> engine["io.hstore.engine"]
    db --> nethttp["java.net.http"]
```

The engine has no dependencies outside `java.base`. All three modules build into one GraalVM native image (`server/target/hstore`) with the `native` Maven profile.

## Package map

### `io.hstore.engine`

| Package | Purpose |
|---|---|
| `io.hstore.engine` | `StorageEngine` (open/close, maintenance thread, checkpoint/compact entry points), `EngineOptions`, `EngineStats`, `Dictionary` (interned role names and role sets), `HStoreException` (error codes, retryable conflicts). |
| `engine.page` | Byte-level storage. `PageStore` and `SegmentFile` hold segment files and allocation, and `FilePool` bounds how many segment files are open at once. `PageHeader` is the 80-byte header and crc32c seal. `PageId` is a page's permanent number, `PageAddress` its place in a segment, and `PageDirectory` the memory-mapped `pages.dir` that maps one to the other. `ReferenceCounts` is `pages.ref`, the count of references to each page, with its checkpoint journal. `ByteCursor` provides little-endian and varint codecs over `MemorySegment`. `IoTrace` counts page visits per query through a `ScopedValue`. |
| `engine.tree` | The copy-on-write counted B+tree. `Tree` is the immutable handle and `TreeWriter` does path-copy updates. Nodes are `Leaf` and `Branch`, referenced by `Ref` (`Empty`, `Pending`, `Stored`, or `Inline` for a small leaf carried inside an edge record). Also here: `Summary` (monoid annotations), `TreeCursor`, `TreeAlgebra` (leapfrog, set algebra), `TreeDiff`, `BulkBuilder`, `NodeCodec`, `NodeCache`, `Materializer` (writes pending nodes to pages at checkpoints), `Epoch` (one checkpoint attempt, for the stamps on written nodes), `TreeWalker` and `TreeVerifier`. |
| `engine.topology` | Hyperedge representation: `Hyperedge` (members tree plus order index), `Incidence` (one member's role set, weight, validity, payload and qualifier), `IncidenceCodec` (columnar leaf encoding), `MemberChange`, `Weight` (fixed point) and `Validity`. |
| `engine.index` | `PostingIndex`, a two-level index from a key to a postings set. Small sets are inlined and large ones are promoted to their own tree. Used by the reverse incidence, type, canonical-key and symbol indexes. |
| `engine.catalog` | `AtomRecord` (`NodeRecord`/`EdgeRecord`) and its codec, `EngineSlots` (built-in slots, schemas and key functions), `Slot`/`SlotRegistry`, `RootVector`, `Generation`, `Branch`, `Symbol`, and `CatalogImage`/`CatalogStore` (checkpoint files). |
| `engine.txn` | `TransactionManager` (commit lock, append, publication, history and its pin counts, write buffer), `ChangeLog` (the key changes a commit logs, and their replay), `Transaction` (op log, read sets, rebase), `Snapshot`, `View` (read API shared by both), `Workspace` (mutable root vector plus derivations), `GroupCommitter`, `Op`/`EdgeAction`, `Derivation`/`EngineDerivations`, `CrashPoint`, `Durability`, `WalMode`, `Isolation` and `TxnOptions`. |
| `engine.wal` | `WriteAheadLog` (segmented, checksummed records) and `WalRecord` (`Begin`, `Page`, `PageRef`, `Changes`, `BranchMeta`, `Feed`, `Commit`, `Abort`, `Checkpoint`, and `Root`, which is still read but no longer written). |
| `engine.feed` | `ChangeFeed` (segmented append-only `feed/*.feed` files, sparse generation index, retention, tailing subscriptions), `CommitEvent` and `FeedCodec`; see [change-feed.md](../storage/change-feed.md). |
| `engine.maintenance` | `Checkpointer` (writes a published generation's trees to pages and publishes the catalog image), `ReferenceCounter` (counts references at each checkpoint, releases pages no reader can reach), `Compactor` (moving live pages out of victim segments, reclamation), `Recovery` (catalog discovery, replay of logged key changes, durable-prefix rule) and `ChangeLogCheck` (replays the log against the generations in memory; the tests use it). |

### `io.hstore.db`

| Package | Purpose |
|---|---|
| `io.hstore.db` | `HypergraphDatabase` (opens the engine with extension slots, retries conflicts), `Reader`/`Writer` (tenant-aware typed API), `Atom`, `Member`, `MemberSpec`, `Incident` and `DatabaseOptions`. |
| `db.schema` | `Schema`, `TypeDef`, `AtomKind` (`NODE`, `SET_EDGE`, `ORDERED_EDGE`) and `SchemaSlots`. |
| `db.value` | `Value` (typed scalar values), `TypeTag`, `Json` (parser, printer, path selection) and `Values`. |
| `db.property` | `PropertyBag`, `PropertyIndex` (tenant-scoped value index), `PropertyIndexing` (a derivation that keeps the index in step with property writes) and `PropertySlots`. |
| `db.payload` | `PayloadStore`: append-only `payload/*.pay` files for large values and documents, synced before every commit. |
| `db.security` | `Principal`, `Role`, `Quota` and `Security` (tenants, users with PBKDF2 hashes, usage accounting). |
| `db.evidence` | `Evidence`, `Qualifier`, `Provenance`, `AssertionType` and `EvidencePolicy`: per-incidence qualifiers and provenance records. |
| `db.temporal` | `Temporal` (valid-time queries), `StateBindings` and `BranchMerge` (three-way branch merge with conflict policies). |
| `db.semantic` | `SemanticPlane` (feed-driven embedding index, persisted at checkpoints), `HnswIndex`, `Encoder`, `HttpEncoder` and `Embedding`. |
| `db.hora` | Higher-order relational algebra: `Hora` (gather/reduce/scatter/propagate, overlap joins, closure), `PatternMatcher` (hypergraph patterns over incidence intersections), `SwapSampler`, `GroupKernel`, `Reducer` and `Budget`. |
| `db.query` | HQL: `Lexer`, `Parser`, `Ast`, `Planner`/`AccessPath`, `Evaluator`, `Executor`, `Session` and `QueryResult`. |
| `db.view` | `MaterializedViews`, kept fresh from the change feed. |
| `db.signal` | `Signals`, a derivation plus resolution of signal atoms. |
| `db.stats` | `Statistics` (feed-maintained cardinality and degree histograms for the planner) and `Histogram`. |

## Life of a read

`HypergraphDatabase.read` opens a `Snapshot` (`engine/src/main/java/io/hstore/engine/txn/Snapshot.java`). Opening it pins the current generation (`TransactionManager.pinCurrent` increments the reader count of the current generation's `Retained` entry in `history`) and wraps the branch's `RootVector`. The snapshot never changes, and no lock is held while it is read. `Snapshot.close` unpins.

```mermaid
sequenceDiagram
    autonumber
    participant C as Caller (Reader / Executor)
    participant HD as HypergraphDatabase
    participant TM as TransactionManager
    participant S as Snapshot (View)
    participant T as Tree
    participant NS as PagedNodeSource
    participant NC as NodeCache
    participant PS as PageStore
    C->>HD: read(branch, principal, work)
    HD->>TM: snapshot(branch)
    TM->>TM: current.readers++
    TM-->>HD: Snapshot(generation, branch.roots)
    HD->>C: work.apply(Reader)
    C->>S: atom(id) / incident(atom) / edge(id)
    S->>T: new Tree(slot.schema, source, roots.get(slot)).get(key)
    loop each level, root to leaf
        T->>T: Branch.route(key) and summary pruning
        T->>NS: load(pageId, schema)
        NS->>NC: get(pageId)
        alt cached
            NC-->>NS: decoded Node (IoTrace.recordHit)
        else miss
            NS->>PS: read(pageId)
            PS-->>NS: page image (MemorySegment)
            NS->>NS: NodeCodec.decode: PageHeader.verify (magic, format, id, crc32c)
            NS->>NC: put(pageId, node) (IoTrace.recordRead, budget check)
        end
    end
    T-->>S: Optional value
    C-->>HD: result
    HD->>TM: Snapshot.close(): readers--
```

Notes:

* Nodes are decoded once and then shared by every reader through `NodeCache`. Catalog and posting-list leaves are kept packed and decode each record the first time it is read. It has 16 shards, each an open-addressing table keyed by page number plus a CLOCK (second-chance) ring, with a total byte budget of `node_cache_mb`, each node counted at an estimate of its heap size (`Node.heapBytes`). Lookups are lock-free; only admission takes a per-shard lock. Decoded nodes are immutable (`owner == null`), so sharing them needs no copying. See [pages.md](../storage/pages.md#caching-and-io-accounting).
* `IoTrace` is bound with `ScopedValue.where(...)` around a query. `recordRead` throws `HStoreException.limit` once page reads plus cache hits exceed the query's page budget (`query_page_budget`).
* A tree changed since the last checkpoint starts with in-memory nodes (`Ref.Pending`). A read follows those directly and goes through `PagedNodeSource` only once it reaches a stored reference.
* Historical reads use `TransactionManager.snapshotAt(generation, branch)`. It looks the generation up in the retained `history` map; see [catalog-and-generations.md](../storage/catalog-and-generations.md#history-retention-and-time-travel).

## Life of a write

A `Transaction` (`engine/src/main/java/io/hstore/engine/txn/Transaction.java`) is a `View` over a private `Workspace`. The workspace starts from the pinned generation's roots. Each operation is recorded in an op log (`Op`) and applied to the workspace immediately. That application path-copies the touched trees under the transaction's `WriteScope`, runs *derivations* to maintain the derived indexes, and collects `MemberChange`/`SlotChange` events for the change feed. Nothing is visible to other transactions until publication.

```mermaid
sequenceDiagram
    autonumber
    participant W as Writer / Executor
    participant TX as Transaction
    participant WS as Workspace
    participant TM as TransactionManager
    participant CL as ChangeLog
    participant WAL as WriteAheadLog
    participant PS as PageStore
    participant GC as GroupCommitter (leading committer)
    participant F as ChangeFeed
    W->>TX: createNode / insert / put ...
    TX->>WS: Op.apply: path-copy trees (TreeWriter), emit MemberChange / SlotChange
    WS->>WS: derivations update reverse, type, canonical and extension indexes
    W->>TX: commit()
    TX->>TM: commit(txn)
    TM->>TM: awaitRoom: wait while the write buffer is over write_buffer_mb
    activate TM
    Note over TM: commitLock held from here
    TM->>TM: fast path when base roots or touched slots are unchanged,<br/>otherwise validateReads and replay the op log on the latest roots (rebase)
    TM->>TM: page budget: count the unwritten nodes the transaction created
    TM->>CL: encode(previous roots, new roots)
    CL->>CL: per changed slot: key changes (TreeDiff), or a reference
    CL-->>TM: Changes payload (a tree past 4,096 changes is written to pages here)
    TM->>WAL: append Begin, Page/PageRef* (only for such trees), Changes, Feed
    TM->>PS: write those pages, if any
    TM->>WAL: append Commit(generation, wallTime, nextAtom, nextTxn)
    TM->>TM: head = next generation, roots pointing at in-memory nodes
    TM->>GC: submit(Pending(next, publication, wrotePages))
    deactivate TM
    Note over TM: commitLock released; caller waits outside the lock
    GC->>PS: sync() in PAGE_REFERENCES mode, only if the batch wrote pages
    GC->>WAL: sync()
    GC->>TM: publication: history.put, current = next, trimHistory
    GC->>F: append(CommitEvent)
    GC-->>TX: await() returns published generation
```

The commit's tree nodes stay in memory. A checkpoint later writes every node reachable from a published generation to pages, syncs them and the page directory, publishes a catalog image pointing at them, and truncates the WAL up to the end of that generation's commit ([maintenance.md](../storage/maintenance.md#checkpoints)). Recovery starts from that image and replays the `Changes` records of the commits after it. When a commit does write pages, under `PAGE_REFERENCES` the WAL carries only `(pageId, address, length, crc32c)` for each, so the commit leader makes those pages durable *before* the log records that reference them, and recovery stops at the first commit whose referenced pages fail verification (the *durable-prefix* rule in `Recovery.intact`). The full protocol and the crash-point matrix are in [wal-and-recovery.md](../transactions/wal-and-recovery.md).

## Threading model

| Thread | Kind | Created in | Role |
|---|---|---|---|
| Caller threads | any (the server uses virtual threads) | the caller | Run reads without locks. Run a transaction's operations without locks. Before committing, wait if the write buffer is full, then take `commitLock` only for append (encoding the key changes, WAL append, and page writes for the rare tree past 4,096 changes). They then block in `GroupCommitter.await` outside the lock. |
| (none; commit leader) | the committing thread | `GroupCommitter.await` | There is no group-commit thread. The first committer waiting on an unpublished commit leads: it takes every queued `Pending` as one batch and calls `makeDurable(batchSize, wrotePages)` once (data sync in `PAGE_REFERENCES` mode if a commit in the batch wrote pages, then WAL sync, both only under `Durability.SYNC`). It then runs each publication in generation order and wakes the waiters. One fsync pair covers the whole batch (`averageGroupCommit` in the statistics). A failure poisons the pipeline, and every later commit fails until restart. |
| `hstore-maintenance` | virtual | `StorageEngine` constructor | Parks for `maintenanceInterval` (default 500 ms), or until a commit that finds the write buffer full, or that passes the checkpoint threshold, unparks it. Then, under the `maintenance` lock (`tryLock`): checkpoint once the write buffer passes half of `write_buffer_mb` or the WAL or the pages written since the last checkpoint exceed the checkpoint threshold (`CHECKPOINT AT`, else `checkpoint_wal_mb`, else a quarter of the live data between 2 MiB and 256 MiB), compact one segment if any has fallen below `compaction_live_ratio`, then reclaim retired segments that no pinned reader can still see. A background checkpoint writes the pages, counts references and publishes the catalog image on this thread. |
| `hstore-references` | platform, daemon | `StorageEngine` constructor | Marks dead the pages a transaction spilled and then gave up (abort, conflict, page budget, duplicate request). The queue holds up to 4 096 entries; past that, the caller waits. Counting itself happens at checkpoints, on the checkpointing thread. |
| `feed-subscriber` | virtual, one per subscription | `ChangeFeed.subscribe` | Tails the feed segments, waiting on a condition signalled by `append` (with a 250 ms timeout). Subscribers are `SemanticPlane`, `MaterializedViews` and `Statistics`. |
| Server connections | virtual, one per socket | `Server` (`Executors.newVirtualThreadPerTaskExecutor`) | Each connection runs a `Session`. |
| Studio requests | virtual | `Studio` (`HttpServer.setExecutor`) | HTTP API and static assets. |

Locks:

* `TransactionManager.commitLock` serializes generation assignment and the WAL append order.
* `StorageEngine.maintenance` serializes checkpoint and compaction.
* `TransactionManager.exclusive` holds `commitLock` and drains the group committer. A checkpoint takes it only to swap the pages it wrote into the head generation; it writes pages, syncs and publishes the catalog without it.
* `ReferenceCounter.pause` takes the counter's lock after handling every queued entry, so a checkpoint counts and journals without the counter thread changing anything. The checkpoint keeps it until the journal is applied.

Readers take no locks at all.

## Data directory layout

The following is real output from `hstore init` followed by loading `examples/clinical-claims.hql` and running `CHECKPOINT;` (default 16 KiB pages). It was taken with storage format 6. Format 7 writes the same files; its catalog images are smaller because they no longer hold the retained history:

```text
FORMAT                                   55 B  format=6, page-size=16384 (java.util.Properties)
LOCK                                      0 B  FileChannel.tryLock() held while open
hstore.conf                            2903 B  server configuration template written by `hstore init`
catalog/manifest                         20 B  name of the latest catalog file
catalog/generations/0000000000000000.cat 37 B  checkpoint of generation 0
catalog/generations/000000000000009d.cat 50554 B  checkpoint of generation 157 (current + retained history)
data/segments/00000001.seg           1029664 B  segment 1: packed node images
data/segments/pages.dir              8388608 B  page directory, one 8 MiB chunk mapped so far (sparse)
data/segments/pages.ref              8388608 B  reference counts, same layout (sparse)
data/segments/pages.ref.journal            0 B  emptied after every checkpoint
wal/segments/0000000000000000.wal      161389 B  WAL segment starting at LSN 0
feed/0000000000000000.feed             28782 B  change feed segment starting at byte 0
payload/000001.pay                        0 B  payload segment (database layer)
semantic/state                           53 B  persisted HNSW index state (database layer)
```

| Path | Owner | Format |
|---|---|---|
| `FORMAT` | `StorageEngine.verifyFormat` | `Properties` with `format=9` (reverse-index postings with validity and the retrieval indexes in slots 47 to 49, 53 and 56, leaf records framed by length, commits logged as key changes and tree pages written at checkpoints, page numbers resolved through a page directory, reference counts per page, small member trees inline in their edge record) and `page-size` (the maximum node size). Opening a directory written in an older format, or opening with a different page size, fails. |
| `LOCK` | `StorageEngine.open` | An exclusive OS file lock. A second process gets `database ... is opened by another process`. |
| `catalog/generations/%016x.cat` | `CatalogStore.publish` | `int magic 0x54414348`, `int crc32c(payload)`, `long length`, then the `CatalogImage` payload. Written to `*.tmp`, fsynced, atomically renamed, and the directory fsynced. The three newest files are kept. |
| `catalog/manifest` | `CatalogStore` | The file name of the latest `.cat`, written durably the same way. |
| `data/segments/%08x.seg` | `PageStore` / `SegmentFile` | Append-only extents of node images packed at 64-byte alignment, from 8 MiB per segment, doubling as the store grows, up to 256 MiB by default (1 GiB maximum); see [pages.md](../storage/pages.md). |
| `data/segments/pages.dir` | `PageDirectory` | 64-byte header, then one `i64` address (segment, offset, units) per page number, memory-mapped in 8 MiB chunks; see [pages.md](../storage/pages.md#page-numbers-and-the-page-directory). |
| `data/segments/pages.ref`, `pages.ref.journal` | `ReferenceCounts`, `ReferenceJournal` | One `i64` reference count per page number in the same layout, changed only at checkpoints through the journal; see [pages.md](../storage/pages.md#reference-counts). |
| `wal/segments/%016x.wal` | `WriteAheadLog` | Each segment is named by its starting LSN; see [wal-and-recovery.md](../transactions/wal-and-recovery.md). |
| `feed/%016x.feed` | `ChangeFeed` | Segments (64 MiB, named by starting byte offset) of frames `int length`, `int crc32c`, `long generation`, `payload`, trimmed by retention after checkpoints; see [change-feed.md](../storage/change-feed.md). |
| `payload/%06x.pay` | `db.payload.PayloadStore` | Append-only payload extents. |
| `semantic/state`, `semantic/hnsw-<model>.idx` | `db.semantic.SemanticPlane` | A `Properties` file with `generation` and `models`, plus one serialized HNSW graph per model, written atomically at checkpoints and on close. |
| `hstore.conf` | `server.ServerConfig` | Settings, overridden by `HSTORE_*` environment variables and then by `--setting` flags. |

Every hyperedge's membership is a nested tree; `hstore check` reports 85 nested trees under the `catalog` slot. All of them are small enough to be kept inline in their edge record, so the whole catalog, edges and members included, is one page. Segments pack node images at 64-byte alignment instead of reserving a full `page_size` slot per node, so the whole example occupies 1.0 MB of segment space, most of it older images still kept for history. With fixed 16 KiB slots it took 17.6 MB. See [pages.md](../storage/pages.md#segment-files).

## Verifying a directory

`hstore check <dir>` (`server/src/main/java/io/hstore/server/Check.java`) opens the engine and runs `TreeVerifier` over every slot root of every active branch, including nested trees. It checks the following:

* page checksums and identity, through decoding;
* strictly ascending keys in leaves;
* separators that route every child;
* equal height for all children of a branch;
* stored summaries equal to recomputed ones, at every node and at the root.

Sample output from the directory above:

```text
generation 157, recovered 0 commits from the log
branch main
  property-index          162 entries        9 pages      8 nested trees  height 1
  ...
  reverse                  53 entries        1 pages      0 nested trees  height 1
  catalog                 458 entries        1 pages     85 nested trees  height 1
verified 20 pages: checksums, ordering, balance and summaries are consistent
```

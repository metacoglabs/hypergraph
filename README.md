# HStore

HStore is a native, persistent hypergraph storage engine and database for the JVM. A hyperedge connects any
number of atoms, carries roles, weights, validity intervals and properties, and can itself be a member of
other hyperedges. HStore stores this structure directly instead of reifying it into pairwise edges or
relational join tables.

The engine implements the design of *Native Persistent Hypergraph Storage Engine* together with its
higher-order extension. Membership and incidence are copy-on-write counted B+trees with monoid summaries. A
commit logs only the keys it changed and publishes a new immutable generation; a background checkpoint later
writes the changed tree nodes to pages, once for all the commits since the last one. The database layer adds a
query language (HQL), hypergraph relational operators, a semantic plane, multi-tenancy and a web console.

It is written in Java 25 and ships as one GraalVM native executable, `hstore`.

<p align="center">
  <img src="docs/images/hypergraph.svg" width="100%"
       alt="Three hyperedges from the clinical claims example: a claim whose members have roles, an ordered care pathway, and an investigation whose members are claims">
</p>

The three kinds of hyperedge HStore stores, taken from the example dataset in
[`examples/clinical-claims.hql`](examples/clinical-claims.hql): a set hyperedge whose members have roles and weights,
an ordered hyperedge, and a hyperedge whose members are other hyperedges.

## Features

* **Native hypergraph topology.** Set and ordered hyperedges of any cardinality, including giant edges with
  millions of members. Per-member roles, weights, validity and qualifiers. Edges of edges.
* **Persistent copy-on-write trees.** Every generation is an immutable snapshot. Readers never block writers,
  and time travel (`AT GENERATION`, `AS OF`) costs nothing extra.
* **Transactions.** Snapshot isolation by default and `SERIALIZABLE` on request. Optimistic concurrency with
  commutative rebase of set operations. Group commit. Two WAL modes (page references or page images). Crash
  recovery that never exposes a partial commit.
* **Small commits.** A commit writes a log record of the keys it changed, a few hundred bytes, instead of new
  pages. Checkpoints write each changed node once, and `write_buffer_mb` caps the memory held by nodes that
  haven't been written yet.
* **Space reclamation.** Pages carry reference counts. Compaction moves the live pages out of mostly dead
  segments without rewriting the trees that point at them, while readers keep running.
* **Leapfrog incidence intersection.** Co-membership queries intersect sorted incidence trees and skip with
  subtree summaries.
* **Branches.** `CREATE BRANCH`, isolated what-if writes, `DIFF`, three-way `MERGE … ON CONFLICT`.
* **HQL.** Declarative queries with a cost-based planner (`EXPLAIN`, `TRACE`), DDL, secondary and JSON-path
  indexes, and hypergraph relational operators (gather, scatter, propagate, overlap join, closure,
  pattern matching).
* **Semantic plane.** Embeddings with a persistent HNSW index. Built-in hashing encoder, or OpenAI-compatible
  and Ollama endpoints.
* **Evidence and temporal facts.** Provenance, qualifiers, validity-aware membership policies.
* **Multi-tenancy and security.** Tenants with quotas, users with READER/WRITER/ADMIN roles, PBKDF2 password
  storage.
* **Operations.** PostgreSQL-style logs, `hstore.conf` / `HSTORE_*` / flag configuration, health checks, a
  Docker image with init scripts.
* **HStore Studio.** A built-in web console with an HQL editor, a native hypergraph visualiser (hubs, role
  spokes, hulls, ordered chains, time-travel slider), a schema browser, a live engine dashboard and access
  management.

## How it works

<p align="center">
  <img src="docs/images/overview.svg" width="100%"
       alt="Writers commit through a commit lock, which publishes new root vectors; a checkpoint writes changed nodes to segments; readers pin published generations">
</p>

Every structure is a copy-on-write B+tree over 64-bit keys, and the state of the database at one point in time is a
*generation*: an immutable root vector with one tree per slot (the catalog, the reverse incidence index, type and
canonical-key indexes, symbol tables, request ids). A commit logs the keys it changed and publishes a new generation
whose changed nodes are still in memory (yellow). A background checkpoint later writes those nodes to append-only
segment files, once for all the commits since the last checkpoint. Readers pin a generation and never take a lock.

<p align="center">
  <img src="docs/images/layout.svg" width="100%"
       alt="The catalog is a tree of trees: each hyperedge record holds its member tree, inline when small; the reverse index maps each atom to the hyperedges that contain it">
</p>

The catalog is a tree of trees. A hyperedge's record holds the root of its membership tree, and holds the whole tree
when it has up to 32 small members, which is the common case. The reverse index maps every atom to the set of
hyperedges that contain it. Every child reference carries the subtree's entry count and a summary (key range, weight
and time ranges, a role mask and a content fingerprint), so cardinality, rank, overlap and diff queries skip whole
subtrees.

<p align="center">
  <img src="docs/images/wal.svg" width="100%"
       alt="Recovery replays commits from the checkpoint LSN, stops at the first commit whose pages do not verify, and drops transactions that never committed">
</p>

Recovery reads the log from the checkpoint recorded in the newest catalog image and replays every commit whose
`Commit` record is intact. It stops at the first commit whose referenced pages fail their checksum and drops
transactions that never committed, so the recovered state is always a prefix of the commit order.

<p align="center">
  <img src="docs/images/compaction.svg" width="55%"
       alt="Compaction copies live page images out of a mostly dead segment and repoints their directory entries; no tree changes">
</p>

Trees refer to pages by permanent page numbers, and a memory-mapped page directory maps each number to its current
place in a segment. Compaction copies the live images out of a mostly dead segment and changes only their directory
entries, so no tree, generation or branch is rewritten and commits keep running while it works.

The [paper](docs/hstore-paper.pdf) and [docs/architecture/overview.md](docs/architecture/overview.md) cover each part
in depth.

## Performance

HStore against HyperGraphDB 1.4 on Berkeley DB JE, both with 64 MiB caches, on one Azure `Standard_D8ds_v5`
machine (8 vCPUs, 32 GiB, local SSD). Scale 1 is 50,000 nodes and 100,000 hyperedges with `async` durability;
each figure is the median of 5 runs per system:

| Workload | HStore | HyperGraphDB | |
|---|---:|---:|---:|
| Incidence set of a node | 3.10 M/s | 1.25 M/s | 2.5× faster |
| Incidence sets from 8 threads | 14.1 M/s | 1.28 M/s | 11× faster |
| Hyperedges shared by two nodes | 272 k/s | 49.8 k/s | 5.5× faster |
| Value updates, 1,000 per transaction | 40.9 k/s | 7.26 k/s | 5.6× faster |
| Bytes written per single-update commit | 659 B | 11.1 KiB | 17× fewer |
| Single-update commit latency, p99 | 228 µs | 767 µs | 3.4× lower |
| Reader latency during writes, p99.9 | 5.47 µs | 94.1 µs | 17× lower |
| Disk after loading | 85.7 MiB | 400 MiB | 4.7× smaller |
| Clean reopen | 78.5 ms | 978 ms | 12.5× faster |

<p align="center">
  <img src="docs/images/ratios-wide.svg" width="100%"
       alt="HStore's advantage over HyperGraphDB for every workload in three configurations, on a log scale">
</p>

At scale 4 (200,000 nodes, 400,000 hyperedges), where the files of both stores outgrow their caches, the leads
widen: hyperedge ingest 7.9×, value updates 12×, incidence sets read right after a reopen 82×, and 13.7× less disk.
Opening a store whose ingest was killed with SIGKILL takes 162 ms against 449 ms, and no acknowledged commit was lost
in any run of either system. Over a whole scale-4 run HStore writes 2.06 GiB to disk and HyperGraphDB 104 GiB, most of
it while JE's cleaner reclaims space after the deletes.

<p align="center">
  <img src="docs/images/resources.svg" width="100%"
       alt="Bytes written per operation, heap bytes allocated per read, and bytes on disk through the load, the deletes and reclamation">
</p>

Where HStore is not ahead: the median latency of a single commit under `async` durability is 143 µs against
HyperGraphDB's 93.7 µs, although HStore's tail is much shorter. HStore keeps more heap after ingest (179 MiB against
118 MiB at scale 1). At scale 4 its slowest single commit in a run is slower than HyperGraphDB's. The
[paper](docs/hstore-paper.pdf) has every number, the latency percentiles, the before and after runs for three design
choices, and the caveats; [docs/benchmarks.md](docs/benchmarks.md) explains how to rerun the comparison.

## Quick start with Docker

Images for linux/amd64 and linux/arm64 are published to the GitHub Container Registry with every release:

```
docker run -d --name hstore \
  -e HSTORE_USER=admin -e HSTORE_PASSWORD=change-me \
  -v "$PWD/examples/clinical-claims.hql:/docker-entrypoint-initdb.d/01-clinical-claims.hql:ro" \
  -p 127.0.0.1:7432:7432 -p 127.0.0.1:7480:7480 \
  ghcr.io/metacoglabs/hypergraph:0.1.0
```

The package is private, so run `docker login ghcr.io` first with a token that has `read:packages`. To build
the image yourself instead, run `docker build -t hstore .` and use `hstore` as the image name.

* Open **http://localhost:7480** and sign in as `admin` / `change-me` to use HStore Studio.
* Open a shell over the wire protocol: `docker exec -it hstore hstore connect 127.0.0.1:7432`.
* Follow the server log: `docker logs -f hstore`.

`HSTORE_PASSWORD=<password> docker compose up -d` does the same with the repository's
[`docker-compose.yml`](docker-compose.yml), which publishes both ports on localhost only. See
[operations/docker.md](docs/operations/docker.md) for every option.

To keep Markdown memory for another project, copy [`examples/memory`](examples/memory): a Compose file and a
small Python client for storing documents as revisions, searching them and asking `RETRIEVE` questions. See
[memory from another repository](docs/operations/docker.md#memory-from-another-repository).

## A taste of HQL

The example dataset ([`examples/clinical-claims.hql`](examples/clinical-claims.hql)) models patients,
providers, drugs and pharmacies. Prescriptions and claims are role-labelled hyperedges, care pathways are
ordered hyperedges, and investigations are hyperedges *over claims*:

```sql
CREATE NODE TYPE Provider (name STRING INDEXED REQUIRED, specialty STRING INDEXED);
CREATE SET EDGE TYPE Claim (amount FLOAT INDEXED, status STRING INDEXED) ROLES (patient, provider, drug, pharmacy);
CREATE ORDERED EDGE TYPE CarePathway (condition STRING INDEXED);
CREATE SET EDGE TYPE Investigation (reason STRING) ROLES (subject, investigator, witness);

INSERT EDGE Claim {amount: 120.0, status: 'pending'}
  MEMBERS (@Patient:'asha-rao' AS patient, @Provider:'dr-ada-park' AS provider WEIGHT 0.8) AS $claim;
```

```
hstore> MATCH EDGE c:Claim WHERE c CONTAINS (@Provider:'dr-gil-moran', @Patient:'rosa-bianchi')
   ...> RETURN c, c.amount, c.status;
+------------+----------+----------+
| c          | c.amount | c.status |
+------------+----------+----------+
| @105 Claim | 9500.13  | paid     |
+------------+----------+----------+

hstore> MATCH EDGE i:Investigation RETURN i.reason, card(i);
+----------------------------+---------+
| i.reason                   | card(i) |
+----------------------------+---------+
| duplicate billing pattern  | 5       |
| upcoding across visits     | 5       |
| opioid prescribing outlier | 5       |
+----------------------------+---------+

hstore> NEIGHBORS OF @Provider:'dr-gil-moran' THRESHOLD 3;
+--------------------------+
| neighbor                 |
+--------------------------+
| @44 Pharmacy:'harbor-rx' |
+--------------------------+

hstore> EXPLAIN MATCH NODE p:Patient WHERE p.age BETWEEN 60 AND 70 RETURN p.name;
IndexRange(Patient.age [60, 70])  rows≈3  cost≈12
  -> Project(p.name)
  rejected CatalogScan(nodes) rows≈122 cost≈29
  rejected TypeScan(Patient) rows≈24 cost≈14
```

## Embedded use

The database is a plain Java module (`io.hstore.db`) and can run inside your application:

```java
try (HypergraphDatabase database = HypergraphDatabase.open(Path.of("data"))) {
    database.write(writer -> {
        writer.defineNode("Person", List.of(new PropertyDef("name", TypeTag.STRING, true, true)));
        writer.defineEdge("Meeting", AtomKind.SET_EDGE, List.of(), List.of("host", "guest"));
        return null;
    });
    long meeting = database.write(writer -> {
        long ann = writer.node("Person", "ann", Map.of("name", "Ann"));
        long bo = writer.node("Person", "bo", Map.of("name", "Bo"));
        long edge = writer.edge("Meeting");
        writer.add(edge, ann, "host");
        writer.add(edge, bo, "guest");
        return edge;
    });
    long members = database.read(reader -> reader.members(meeting).count());
    try (Session session = new Session(database)) {
        IO.println(session.execute("MATCH EDGE m:Meeting WHERE m CONTAINS (@Person:'ann', @Person:'bo') RETURN m;").render());
    }
}
```

## Release downloads

Each [release](https://github.com/metacoglabs/hypergraph/releases) has:

| File | Contents |
|---|---|
| `hstore-<version>-linux-amd64.tar.gz` | native executable for Linux x86-64 |
| `hstore-<version>-darwin-arm64.tar.gz` | native executable for Apple silicon Macs |
| `hstore-<version>-jvm.tar.gz` | the three module jars and `bin/hstore`, for any OS with Java 25 |
| `SHA256SUMS` | checksums of the archives |

## Building from source

Requires GraalVM for JDK 25.

```
./mvnw install                                   # build and test
./mvnw -Pnative -DskipTests package -pl server   # native executable at server/target/hstore
server/target/hstore init data --superuser admin --password change-me
server/target/hstore serve data
```

## Documentation

The full index is [docs/README.md](docs/README.md).

| Topic | Document |
|---|---|
| Architecture | [docs/architecture/overview.md](docs/architecture/overview.md) |
| Storage engine | [docs/storage](docs/storage) |
| Transactions, WAL and recovery | [docs/transactions](docs/transactions) |
| Database layer and HQL | [docs/database](docs/database) |
| Docker | [docs/operations/docker.md](docs/operations/docker.md) |
| Configuration | [docs/operations/configuration.md](docs/operations/configuration.md) |
| Logging | [docs/operations/logging.md](docs/operations/logging.md) |
| Command line | [docs/operations/cli.md](docs/operations/cli.md) |
| Wire protocol | [docs/operations/wire-protocol.md](docs/operations/wire-protocol.md) |
| HStore Studio | [docs/operations/studio.md](docs/operations/studio.md) |
| Benchmarks against HyperGraphDB | [docs/benchmarks.md](docs/benchmarks.md) |
| Benchmark report (PDF) | [docs/benchmark-report.pdf](docs/benchmark-report.pdf) |
| Paper: design and evaluation (PDF) | [docs/hstore-paper.pdf](docs/hstore-paper.pdf) |
| Development | [docs/development.md](docs/development.md) |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

HStore is source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE)
(SPDX: `PolyForm-Noncommercial-1.0.0`). You may use, modify and share it for any noncommercial purpose as that
license defines it.

**Commercial use needs a separate commercial license from MetaCognition India Pvt Limited.** Anything the
noncommercial license doesn't cover can be licensed commercially: write to krish@getmetacognition.com. The server
prints this notice at startup and in `hstore version`.

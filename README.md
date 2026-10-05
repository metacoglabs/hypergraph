# HStore

HStore is a native, persistent hypergraph storage engine and database for the JVM. A hyperedge connects any
number of atoms, carries roles, weights, validity intervals and properties, and can itself be a member of
other hyperedges. HStore stores this structure directly instead of reifying it into pairwise edges or
relational join tables.

The engine implements the design of *Native Persistent Hypergraph Storage Engine* together with its
higher-order extension. Membership and incidence are copy-on-write counted B+trees with monoid summaries.
Commits are atomic root swaps. The database layer adds a query language (HQL), hypergraph relational operators,
a semantic plane, multi-tenancy and a web console.

It is written in Java 25 and ships as one GraalVM native executable, `hstore`.

## Features

* **Native hypergraph topology.** Set and ordered hyperedges of any cardinality, including giant edges with
  millions of members. Per-member roles, weights, validity and qualifiers. Edges of edges.
* **Persistent copy-on-write trees.** Every generation is an immutable snapshot. Readers never block writers,
  and time travel (`AT GENERATION`, `AS OF`) costs nothing extra.
* **Transactions.** Snapshot isolation by default and `SERIALIZABLE` on request. Optimistic concurrency with
  commutative rebase of set operations. Group commit. Two WAL modes (page references or page images). Crash
  recovery that never exposes a partial commit.
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

## Quick start with Docker

Images for linux/amd64 and linux/arm64 are published to the GitHub Container Registry with every release:

```
docker run -d --name hstore \
  -e HSTORE_USER=admin -e HSTORE_PASSWORD=change-me \
  -v "$PWD/examples/clinical-claims.hql:/docker-entrypoint-initdb.d/01-clinical-claims.hql:ro" \
  -p 7432:7432 -p 7480:7480 \
  ghcr.io/metacoglabs/hypergraph:0.1.0
```

While the repository is private, run `docker login ghcr.io` first with a token that has `read:packages`. To
build the image yourself instead, run `docker build -t hstore .` and use `hstore` as the image name.

* Open **http://localhost:7480** and sign in as `admin` / `change-me` to use HStore Studio.
* Open a shell over the wire protocol: `docker exec -it hstore hstore connect 127.0.0.1:7432`.
* Follow the server log: `docker logs -f hstore`.

`docker compose up -d` does the same with the repository's [`docker-compose.yml`](docker-compose.yml). See
[operations/docker.md](docs/operations/docker.md) for every option.

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
| Development | [docs/development.md](docs/development.md) |

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

HStore is source-available under the [PolyForm Noncommercial License 1.0.0](LICENSE)
(SPDX: `PolyForm-Noncommercial-1.0.0`). You may use, modify and share it for any noncommercial purpose.
Commercial use is not covered by this license; contact the maintainers if you need it.

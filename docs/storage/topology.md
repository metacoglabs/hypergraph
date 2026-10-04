# Hypergraph topology

This document describes how atoms, hyperedges and their memberships are laid out in trees, and how the reverse direction (which hyperedges contain an atom) is kept consistent. It builds on the tree described in [persistent-tree.md](persistent-tree.md). Source files are under `engine/src/main/java/io/hstore/engine/{catalog,topology,index,txn}`.

## Atoms

Everything is an **atom** identified by a 64-bit id, allocated monotonically by `TransactionManager.allocateAtom()`. The allocator is seeded from `Generation.nextAtom`, which every `Commit` WAL record persists. There are two kinds of atom, both stored in the `catalog` slot (slot 1, `EngineSlots.CATALOG`, tree schema `atoms` #1, SET fingerprint) keyed by atom id:

```java
sealed interface AtomRecord {
    record NodeRecord(int type, String canonicalKey, long dataRef, long embeddingRef, int flags, int tenant, int isolation) ...
    record EdgeRecord(int type, EdgeKind kind, Ref members, Ref order, long version, long dataRef, int tenant, int isolation) ...
}
```

| Field | Meaning |
|---|---|
| `type` | Type id assigned by the database schema layer. The engine treats it as opaque. |
| `canonicalKey` | Optional unique name within `(tenant, type)`, at most 512 characters (`NodeRecord.MAX_KEY_LENGTH`). HQL writes it as `@Person:'alice'`. |
| `dataRef` | Locator of the atom's property bag or payload, owned by the database layer. |
| `embeddingRef` | Locator of the node's embedding. |
| `kind` | `SET` or `ORDERED` (`topology/EdgeKind.java`). |
| `members`, `order` | Roots of the edge's nested membership tree and order index. These are `Ref`s embedded *inside* the catalog value. |
| `version` | Incremented by `EdgeRecord.withRoots` on every topology change. |
| `tenant`, `isolation` | The tenant that owns the atom. The tenant id scopes every derived key (see below). |

### Encoding (`catalog/AtomCodec.java`)

Values are written one after another after the leaf's key stream (`NodeCodec`, see [pages.md](pages.md)):

```text
NodeRecord: u8 tag=0 | varint type | u8 hasKey | [varint len | utf8 key] | varint dataRef | varint embeddingRef
            | varint flags | varint tenant | varint isolation
EdgeRecord: u8 tag=1 | varint type | u8 kind (0=SET, 1=ORDERED) | Ref members | Ref order
            | varint version | varint dataRef | varint tenant | varint isolation
Ref:        i64le pageId (0 = empty) | [Summary encoding, only when pageId != 0]
```

Because `AtomCodec.holdsRefs()` is true, the materializer writes each edge's member and order trees *before* the catalog leaf that references them. `TreeWalker` and `TreeVerifier` descend into them, and compaction relocates them. The catalog therefore forms a two-level tree of trees: catalog leaves hold edge records, and each edge record roots its own membership tree.

A record's `contentHash()` feeds the catalog's SET fingerprint. The hash of an edge record is computed from `(1, type, kind, members.count, members.fingerprint, version, dataRef, tenant, isolation)`. It is a function of the edge's member *contents*, never of page ids, so relocating pages during compaction does not change catalog fingerprints.

## Hyperedges

`topology/Hyperedge.java` is the in-memory view of one edge: `Hyperedge(id, kind, Tree<Incidence> members, Tree<Long> order)`. Hyperedges are created empty (`Op.CreateAtom` rejects an edge record that has members). All topology changes go through `EdgeAction`s (`Insert`, `Upsert`, `Replace`, `Remove`, `InsertAt`, `RemoveAt`, `UpdateAt`, `Load`). `Op.UpdateAtom` refuses to change `members` or `order` directly.

### Incidence: one member's participation

```java
record Incidence(long member, int roleSet, long weight, long validFrom, long validTo, long dataRef, long qualifier)
```

| Field | Encoding and semantics | Default |
|---|---|---|
| `member` | Atom id of the participant: a node *or another hyperedge* | — |
| `roleSet` | Symbol id of a `Symbol.Group(ROLE_SET, sorted role ids)` interned in the `symbols` slot by `Dictionary.roleSet(roles)`. A member can play several roles in one edge, e.g. `buyer\|approver`. `0` means no roles. | `0` |
| `weight` | Fixed point with 10⁹ scale (`topology/Weight.java`): `Weight.of(0.5) = 500_000_000`. Exact, totally ordered, and summable in `Summary.weightSum`. | `Weight.ONE = 1_000_000_000` |
| `validFrom`, `validTo` | Valid-time interval, half-open `[from, to)`. An empty interval is rejected. | `Long.MIN_VALUE`, `Long.MAX_VALUE` (always valid) |
| `dataRef` | Locator of per-incidence payload data | `0` |
| `qualifier` | Atom id of a qualifier/evidence record (database layer, `db.evidence`) | `0` |

`Incidence.contentHash()` is `Hashing.of(member, roleSet, weight, validFrom, validTo, dataRef, qualifier)`.

`Incidence.roleBit(roleSet) = 1L << (roleSet & 63)` maps a role-set id into one of 64 buckets. It is a 64-bit Bloom-style mask that summaries OR together. A clear bit proves absence; a set bit only means "maybe", so `Hyperedge.withRoleSets` always re-checks the exact role set after pruning.

### SET hyperedges

The `members` tree uses schema `set-members` #2 with SET fingerprint and the `IncidenceCodec.KEYED` codec. Its **key is the member atom id**. The `order` tree is empty.

* `contains(m)`, `get(m)`: O(log_B n) point lookups.
* Duplicate members are rejected (`requireAbsent`): a set edge holds each atom at most once.
* `indexOf(m)` is `rankOf(m)`. Iteration order is ascending member id.

### Ordered hyperedges

An `ORDERED` edge is a sequence that may be edited at any position:

* `members` uses schema `ordered-members` #3, a **SEQUENCE** fingerprint and the `IncidenceCodec.SEQUENCED` codec. The key is an **order-maintenance label**, a sparse `long` whose ascending order is the sequence order. The member id is stored as a column of the leaf.
* `order` uses schema `order-index` #4 (SET). Each entry maps member atom id to its label, so `contains(m)`, `get(m)` and `indexOf(m)` stay O(log_B n). `Hyperedge.membership()` returns this tree for ordered edges, which is the tree used in set algebra.

Labels live in `[LABEL_FLOOR = 0, LABEL_CEILING = 2^62)`.

* **Bulk build** (`Hyperedge.build`): labels are `(i + 1) · min(2^24, 2^62 / (n + 1))`.
* **Insert at position i** (`between`): the new label is the midpoint of the neighbouring labels. Appends and prepends take a step of `APPEND_STEP = 2^24` when the gap allows it, so appending to a sequence never relabels.
* **Gap exhausted** (`relabel`): the window around `i` doubles, with widths 8, 16, 32, …, until the free space spread over the window reaches `MIN_SPACING = 2^8` per slot, or until the window covers the whole edge. The window is then relabelled evenly (`spread`). Each moved member emits `MemberChange.Updated(…, locatorBefore, locatorAfter)`, so the reverse index records the new locator. If even the whole edge cannot reach spacing 1, the operation fails with `HStoreException.limit`.

Amortized relabel cost is O(log n) per insert, the classic order-maintenance bound. Positional reads (`at(index)`, `insertAt`, `removeAt`, `updateAt`) use the tree's counts, so `at(i)` costs O(log_B n).

Because the entry hash is `Incidence.contentHash()` and not the label, relabelling never changes an ordered edge's SEQUENCE fingerprint. Two ordered edges with equal fingerprints hold the same members in the same order with high probability.

### Leaf encoding of incidences (`topology/IncidenceCodec.java`)

Incidence leaves are columnar and sparse. Most incidences carry defaults, so absent columns cost nothing and partly present columns cost one bit per row:

```text
u8   presence flags         bit c set ⇔ some row has a non-default value in column c
                            c: 0 ROLES, 1 WEIGHT, 2 VALID_FROM, 3 VALID_TO, 4 DATA_REF, 5 QUALIFIER
[SEQUENCED only] zigzag-varint member × n
for each present column c in order:
    bitmap ⌈n/8⌉ bytes      bit i set ⇔ row i has a non-default value (LSB first)
    value × popcount        ROLES, DATA_REF, QUALIFIER: unsigned varint; WEIGHT, VALID_*: zigzag varint
```

A plain set edge with no roles or weights therefore encodes to one flag byte plus the delta-varint member keys, about 1–3 bytes per member.

`IncidenceCodec.maxSize` charges each entry `Σ present column sizes` (plus the member varint when sequenced), and `IncidenceCodec.leafOverhead(n) = 1 + 6·⌈n/8⌉` charges the leaf for its flags byte and column bitmaps, so leaf sizes are an exact upper bound ([pages.md](pages.md#payload-treenodecodecjava)). The change feed reuses this codec to serialize a single incidence (`FeedCodec.writeIncidence`).

### Filtered member scans with summary pruning

The incidence measure in `TopologySchemas` feeds every summary field:

```java
(key, incidence, into) -> into.entry(key, incidence.contentHash())
                              .weight(incidence.weight())
                              .validity(incidence.validFrom(), incidence.validTo())
                              .roles(Incidence.roleBit(incidence.roleSet()))
```

Every reference in a member tree therefore knows the weight range, validity envelope and role mask of its subtree. The filtered accessors on `Hyperedge` use a pruned scan: a subtree that cannot match is never read.

| Method | Prunes subtrees where | Then filters exactly on |
|---|---|---|
| `validAt(t)` | `!(timeMin <= t < timeMax)` | `incidence.validAt(t)` |
| `overlapping(from, to)` | `!(timeMin < to && from < timeMax)` | `validity().overlaps(from, to)` |
| `weightedBetween(lo, hi)` | `weightMax < lo \|\| weightMin > hi` | `lo <= weight <= hi` |
| `withRoleSets(sets)` | `(roleBits & mask) == 0` | `sets.contains(roleSet)` |

HQL examples (see [database/](../database/)) that map onto these:

```sql
MEMBERS OF $c1 ROLE approver;
MEMBERS OF $c1 WEIGHT BETWEEN 0.4 AND 0.6;
MEMBERS OF $c2 POLICY OBSERVED;
```

### Higher-order hyperedges

A member is any atom id, so a hyperedge can contain other hyperedges. Nothing in the representation distinguishes this case: the inner edge's id is the key of the outer edge's member tree, and the reverse index lists the outer edge among the inner edge's incident edges. This is how, for example, an `Investigation` edge can have three `Claim` edges as `subject` members. Deleting an atom (`Op.DeleteAtom`) removes it from every edge that contains it, through the reverse index. Deleting an edge also emits `Removed` for each of its own members and empties its roots.

### Cardinality and degree in O(1)

* **Cardinality** of an edge is `EdgeRecord.members.count()`, which reads `Ref.summary().count` from the catalog value without loading the member tree (`View.cardinality`).
* **Degree** of an atom is the size of its posting set in the reverse index (`PostingIndex.count`): the array length for inline postings, the root count for promoted ones.

In the database layer, `Reader.edge(id)` and `Reader.members(id)` resolve the catalog record once and build the `Hyperedge` from it with `View.edge(id, EdgeRecord)`, so a member listing costs one catalog lookup plus the member-tree scan. Role names come from `Dictionary.roles(roleSet)`, which caches the resolved name list per role-set id. Symbols are append-only, so the cache never needs invalidation.

## The reverse incidence index

The `reverse` slot (slot 2, derived) is a `PostingIndex<Incident>` (`EngineSlots.REVERSE_INDEX`, directory schema #5, postings schema #6, inline limit 48):

```text
directory tree:  key = member atom id  →  Postings<Incident>
postings:        key = edge atom id    →  Incident(roleSet, locator)
```

`locator` is the member's key inside the edge: the member id for set edges, the label for ordered edges. It lets readers jump straight to the incidence.

`index/PostingIndex.java` stores each posting set in one of two forms:

* **`Inline(long[] keys, List<P> values, long fingerprint, int bytes)`** while it has at most `inlineLimit` entries *and* its encoded size is at most `maxValueBytes / 2`. It is stored inside the directory leaf: `u8 0 | varint count | zigzag-varint first key | varint deltas | values`. The fingerprint and encoded size are worked out once when the list is built or decoded (`PostingsCodec.inline`), and are not stored on disk. Leaf summaries and size accounting read them in O(1) instead of re-hashing every posting each time a directory leaf is copied or frozen.
* **`Promoted(Ref root)`**: once either limit is exceeded, the set is bulk-built into its own postings tree, and the directory stores `u8 1 | Ref`. On removal it is demoted back to inline when it shrinks to `inlineLimit / 2` entries. The gap between the two thresholds prevents thrashing.

The directory's entry measure sets `weight = postings.size()`, so `countRange(lo, hi)` sums posting counts over a key range from summaries alone. The fingerprint of a posting set is identical in both forms: the inline form sums `combine(mix(edge), hash(incident))`, which is exactly the SET fingerprint of the promoted tree.

The index is maintained by the `REVERSE_INCIDENCE` derivation (`txn/EngineDerivations.java`), which runs inside the writing transaction's `Workspace` for every `MemberChange`:

| Change | Reverse-index effect |
|---|---|
| `Added(edge, incidence, locator)` | `add(member → edge, Incident(roleSet, locator))` |
| `Updated(…)` with a changed role set or locator | overwrite the posting with the new `Incident` |
| `Updated(…)` with only weight, validity or data changed | none |
| `Removed(edge, incidence, _)` | `remove(member → edge)` |

`View.incidentTree(atom)` exposes a member's posting set as a `Tree` (it builds an ephemeral tree for the inline form), so it can be passed directly to `TreeAlgebra.intersectKeys`. "Hyperedges containing all of a₁…a_k" is therefore a k-way leapfrog intersection of k incident trees:

```sql
MATCH EDGE c:Claim WHERE c CONTAINS ($alice, $bob) RETURN c.amount, card(c);
```

## Other engine-maintained indexes

| Slot | Kind | Directory key | Postings | Maintained by |
|---|---|---|---|---|
| 3 `type-index` (#7/#8, inline 32) | derived | `typeKey(tenant, type) = (tenant << 32) \| type` | atom ids → `Marker` | `ATOM_INDEXES` on catalog changes |
| 4 `canonical` (#9/#10, inline 8) | derived | `canonicalKey(tenant, type, key) = Hashing.of(tenant, type, Hashing.of(key))` | atom ids → `CanonicalName(tenant, type, key)` | `ATOM_INDEXES` |
| 5 `symbols` (#11) | primary | symbol id | `Symbol.Name` / `Symbol.Group` | `Dictionary.intern` (system transaction) |
| 6 `symbol-lookup` (#12/#13, inline 8) | derived | `symbol.hash()` | symbol ids | `SYMBOL_LOOKUP` |
| 7 `requests` (#14) | primary | `Hashing.of(requestId)` | `RequestRecord(requestId, txnId, generation)` | idempotent commits (`TxnOptions.requestId`) |

Hash-keyed indexes (`canonical`, `symbol-lookup`, `requests`) store the full original value in the posting and compare it on lookup (`Views.resolve`, `Dictionary.find`, `priorRequest`). A 64-bit collision is therefore detected and never confused with a match.

### Tenant scoping

Every key that names an atom by its attributes includes the tenant:

* `typeKey` places the tenant in the high 32 bits. All atoms of a tenant are contiguous in the type index, and `atomsOfType(tenant, type)` cannot see another tenant's atoms.
* `canonicalKey` and `CanonicalName` include the tenant, so the same `Person:'alice'` may exist independently in two tenants.
* The database layer adds the same scoping to its own extension indexes. For example, `PropertySlots.directoryKey(tenant, key, tag) = (tenant << 40) | (key << 8) | tag`.

Isolation is therefore a property of the key space, not a filter applied after the read. See [database/](../database/) for principals and quotas.

## Change events

Every topology change produces a `MemberChange` (`Added`, `Removed` or `Updated` with locators before and after). It is emitted into the workspace, applied to derivations, and recorded in the commit's `CommitEvent`. `Hyperedge.diff(before, after, sink)` reconstructs the exact changes between two versions of an edge. It is used by `EdgeAction.Load`, which swaps in bulk-built roots, so bulk loads also produce precise change events. The feed format is described in [catalog-and-generations.md](catalog-and-generations.md#change-feed).

## Example

The Java API, at the engine level:

```java
try (StorageEngine engine = StorageEngine.open(dir, EngineOptions.defaults())) {
    long edge = engine.write(txn -> {
        int buyer = engine.dictionary().roleSet(List.of("buyer"));
        long alice = txn.createNode(1, "alice");
        long bob = txn.createNode(1, "bob");
        long claim = txn.createEdge(2, EdgeKind.SET);
        txn.insert(claim, new Incidence(alice, buyer, Weight.of(0.5), Long.MIN_VALUE, Long.MAX_VALUE, 0, 0));
        txn.insert(claim, bob);
        return claim;
    });
    try (Snapshot snapshot = engine.snapshot()) {
        snapshot.cardinality(edge);
        snapshot.requireEdge(edge).weightedBetween(Weight.of(0.4), Weight.of(0.6)).toList();
        snapshot.incident(snapshot.requireEdge(edge).at(0).member()).toList();
    }
}
```

`cardinality` comes from the summary, `weightedBetween` returns alice's incidence, and `incident` lists `IncidentEdge(edge, roleSet, locator)` from the reverse index.

The same in HQL, through the database layer:

```sql
CREATE NODE TYPE Person (name STRING INDEXED);
CREATE SET EDGE TYPE Claim (amount FLOAT INDEXED) ROLES (buyer, seller);
INSERT NODE Person 'alice' {name: 'Alice'} AS $alice;
INSERT NODE Person 'bob' {name: 'Bob'} AS $bob;
INSERT EDGE Claim {amount: 120.5} MEMBERS ($alice AS buyer WEIGHT 0.5, $bob AS seller) AS $c1;
MEMBERS OF $c1 WEIGHT BETWEEN 0.4 AND 0.6;
INCIDENT TO $alice;
```

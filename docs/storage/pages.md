# Pages, segments and node images

Every tree node is stored as one self-describing, checksummed **node image**: an 80-byte header followed by a variable-length payload. Images are packed back to back into append-only **segment files**, each image aligned to a 64-byte unit. This document gives the byte-exact formats, the addressing scheme, and the read, write and cache paths. Sources are in `engine/src/main/java/io/hstore/engine/page/` and the codec in `engine/src/main/java/io/hstore/engine/tree/NodeCodec.java`.

The word *page* is kept throughout the code (`PageStore`, `PageId`, `pageSize`) for historical reasons. A page here means one node image, and `pageSize` is the **maximum** image size, not a fixed slot size. Trees split nodes so that their encoding fits in `pageSize` ([persistent-tree.md](persistent-tree.md#node-structure)). Images are stored at their encoded length rounded up to 64 bytes.

## Byte order and primitive encodings

`ByteCursor` (`page/ByteCursor.java`) is the only codec used on disk. It wraps a `java.lang.foreign.MemorySegment`.

| Primitive | Encoding |
|---|---|
| `putInt` / `putLong` | Fixed width, **little-endian**, unaligned (`ValueLayout.JAVA_INT_UNALIGNED.withOrder(LITTLE_ENDIAN)`) |
| `putByte` | 1 byte |
| `putVarLong` | Unsigned LEB128: 7 bits per byte, low group first, high bit = continuation; 1–10 bytes |
| `putSignedVarLong` | ZigZag (`(v << 1) ^ (v >> 63)`) then LEB128, so small negative numbers stay short |
| `putVarInt` | `putVarLong(Integer.toUnsignedLong(v))` |
| `putBlob` / `putString` | varint length, then raw bytes (UTF-8 for strings) |
| `putDouble` | raw IEEE-754 bits as a little-endian `long` |

Sizes are computed exactly before writing (`varLongSize`, `signedVarLongSize`, `stringSize`). Leaves use these to track their own encoded size ([persistent-tree.md](persistent-tree.md#node-structure)).

## The node image

```text
┌──────────────────────────── 80-byte header ────────────────────────────┐┌──── payload (payloadLength bytes) ────┐
0        4  5  6  7  8          16         24   28   32         40         48         56   60   64         72   76   80
│ magic  │fm│ty│sc│fl│ pageId   │ epoch    │plen│slot│ count    │ lower    │ upper    │hgt │ 0  │ fprint   │crc │ 0  ││ ...
```

### Header (`page/PageHeader.java`)

| Offset | Size | Field | Notes |
|---:|---:|---|---|
| 0 | 4 | `magic` | `0x47505348`. Stored little-endian, so the bytes on disk read `48 53 50 47` = ASCII `HSPG`. |
| 4 | 1 | `format` | Page format version, `1` (`PageHeader.FORMAT`). Distinct from the data-directory format (see [Format versioning](#format-versioning)). |
| 5 | 1 | `type` | `PageType`: `1` = `INTERNAL` (branch), `2` = `LEAF` |
| 6 | 1 | `schema` | `TreeSchema.id` (1–255) of the tree the node belongs to |
| 7 | 1 | `flags` | Currently always `0` |
| 8 | 8 | `pageId` | The image's own packed address (below). It is written last, after allocation (`PageHeader.assign`). |
| 16 | 8 | `creationEpoch` | Id of the generation whose commit wrote the image. Spilled pages use `head + 1`. |
| 24 | 4 | `payloadLength` | Payload bytes after the header. Readers use it to size the second read. |
| 28 | 4 | `slotCount` | Number of entries (leaf) or children (branch) |
| 32 | 8 | `logicalCount` | `Summary.count` of the subtree |
| 40 | 8 | `lowerBound` | `Summary.min` |
| 48 | 8 | `upperBound` | `Summary.max` |
| 56 | 4 | `height` | `0` for leaves |
| 60 | 4 | reserved | written as `0` |
| 64 | 8 | `fingerprint` | `Summary.fingerprint` (SET or SEQUENCE, see [persistent-tree.md](persistent-tree.md#fingerprints)) |
| 72 | 4 | `checksum` | CRC-32C, see below |
| 76 | 4 | reserved | written as `0`, not covered by the checksum |

**Checksum coverage.** `checksum = crc32c(image[0, 72) ‖ image[80, 80 + payloadLength))` (`PageHeader.seal`, using `java.util.zip.CRC32C`). It covers every header field, including `pageId`, plus the payload. It excludes the checksum itself and the final reserved word. Any bytes between the end of the payload and the next 64-byte boundary are never covered and never read.

**Verification.** `PageHeader.verify(image, expectedPageId)` runs on every decode. It rejects the image with `HStoreException.corrupt(pageId, …)` when any of these holds:

* the magic is wrong;
* the format is not `1`;
* the type is unknown;
* `pageId != expectedPageId` (a misdirected read, or a stale address pointing into reused bytes);
* `80 + payloadLength` exceeds the bytes read;
* the CRC does not match.

`NodeCodec.decode` also rejects an image whose `schema` differs from the schema of the tree being read.

### Payload (`tree/NodeCodec.java`)

The header carries `count`, `min`, `max` and `fingerprint` of the node's own summary. The payload begins with the remaining summary fields, then the keys, then either values or child references:

```text
zigzag  weightSum
zigzag  weightMin
zigzag  weightMax
zigzag  timeMin
zigzag  timeMax
i64le   roleBits
keys:   zigzag keys[0], then varint (keys[i] − keys[i−1]) for i = 1..slotCount−1
LEAF:     schema.codec().encode(out, keys, values)          (value format owned by the schema)
INTERNAL: Ref × slotCount
          Ref := i64le pageId | Summary      (pageId 0 = empty, followed by nothing)
          Summary := varint count | zigzag min | varint (max − min) | zigzag weightSum | zigzag weightMin
                     | zigzag weightMax | zigzag timeMin | zigzag timeMax | i64le roleBits | i64le fingerprint
```

For an internal node, "keys" are the separators. `separators[0]` is the first child's minimum (set by `Branch.frozenWith`), so the delta stream is non-negative. Each child reference embeds the child's image size in 64-byte units and its **complete** summary. This lets readers prune and count without loading children, lets compaction measure live bytes without reading pages, and is why a branch entry costs up to `KEY_BYTES + 8 + 3 + 96` bytes in the fanout calculation (`Layout`).

**Size invariant.** Splits are decided from each value codec's `maxSize`, summed per entry, plus the codec's `leafOverhead(n)` and the exact key-stream size (`Leaf.bytes`, `BulkBuilder`). `IncidenceCodec.leafOverhead(n)` is `1 + 6·⌈n/8⌉`: the flags byte plus one bitmap per column, assuming every column is present. The accounting is therefore an upper bound for any leaf, and exact when all columns are present (`IncidenceCodecTest`). Other codecs have no per-leaf overhead. `Layout` still keeps a 32-byte reserve beyond `leafBudget` (`RESERVED = 32`), but correctness no longer depends on it. `NodeCodec.encode` encodes into a cursor of exactly `pageSize` bytes and would fail loudly (`node of N entries overflows a P byte page`) if the invariant were broken.

Leaf value formats in use:

| Schema | Codec | Format |
|---|---|---|
| `atoms` #1 | `catalog/AtomCodec` | Tagged node/edge records with embedded `Ref`s to member and order trees ([topology.md](topology.md#encoding-catalogatomcodecjava)) |
| `set-members` #2, `ordered-members` #3 | `topology/IncidenceCodec` | Columnar: presence flags, per-column bitmaps, varint columns ([topology.md](topology.md#leaf-encoding-of-incidences-topologyincidencecodecjava)) |
| `order-index` #4 | `TopologySchemas.LONG_CODEC` | zigzag varint per value |
| posting directories (#5, #7, #9, #12, …) | `index/PostingsCodec` | `u8 0 \| varint n \| delta keys \| values` (inline) or `u8 1 \| Ref` (promoted) |
| simple rows | `ValueCodec.rows(size, writer, reader)` | values written one after another |

A worked example, produced by running `Materializer` over a two-entry `order-index` leaf (`{17 → 16777216, 18 → 33554432}`) allocated at `1:1234@157`. The image is 139 bytes, so it occupies 3 units (192 bytes):

```text
00  48 53 50 47 01 02 04 00 9d 00 d2 04 00 01 00 00   magic HSPG | format 1 | LEAF | schema 4 | flags 0 | pageId (le) = 0x10004d2009d
10  9d 00 00 00 00 00 00 00 3b 00 00 00 02 00 00 00   epoch 157 | payloadLength 59 | slotCount 2
20  02 00 00 00 00 00 00 00 11 00 00 00 00 00 00 00   count 2 | lower 17
30  12 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00   upper 18 | height 0 | reserved
40  0a 2a 2f 32 04 9f 42 eb 15 bc b1 19 00 00 00 00   fingerprint | crc32c | reserved
50  00                                                weightSum = zigzag(0)
51  fe ff ff ff ff ff ff ff ff 01                     weightMin = zigzag(Long.MAX_VALUE)  (EMPTY sentinel)
5b  ff ff ff ff ff ff ff ff ff 01                     weightMax = zigzag(Long.MIN_VALUE)
65  fe ff ff ff ff ff ff ff ff 01                     timeMin
6f  ff ff ff ff ff ff ff ff ff 01                     timeMax
79  00 00 00 00 00 00 00 00                           roleBits
81  22 01                                             keys: zigzag(17) = 0x22, delta 1
83  80 80 80 10 80 80 80 20                           values: zigzag varints of 16777216 and 33554432
```

An `EntryMeasure.keyed` measure never feeds weight or validity, so those fields keep the `Summary.EMPTY` sentinels. They round-trip exactly at a fixed cost of 40 bytes per node. Incidence trees, whose measure does feed them, encode real ranges there instead.

## Addressing: packed `PageId`

A `PageId` (`page/PageId.java`) is a 64-bit value:

```text
 63                   40 39                   16 15            0
┌───────────────────────┬───────────────────────┬───────────────┐
│ segment (24 bits)     │ offset (24 bits)      │ generation    │
│ 1 .. 16 777 215       │ 64-byte units         │ (16 bits)     │
└───────────────────────┴───────────────────────┴───────────────┘
```

* `segment` is the segment file id; `0` is reserved, so `PageId.NONE = 0` means "no page".
* `offset` counts **64-byte units** (`PageId.UNIT_BYTES = 64`) from the start of the segment file. The byte position is `offset × 64`. With 24 bits, one segment can address at most 2²⁴ × 64 B = 1 GiB.
* `generation` is `creationEpoch & 0xFFFF`. It is not needed for addressing. It makes the id of an image written in a different epoch differ from one written at the same location, which the identity check in `verify` exploits, and it makes ids readable in diagnostics (`PageId.toString()` prints `segment:offset@generation`).

Worked encode/decode: segment 1, unit offset 1234, generation 157.

```text
pack   = (1 << 40) | (1234 << 16) | 157
       = 0x0000_0100_04D2_009D = 1 099 592 499 357
byte offset in 00000001.seg = 1234 × 64 = 78 976
segmentOf(p)    = p >>> 40                 = 1
offsetOf(p)     = (p >>> 16) & 0xFFFFFF    = 1234
generationOf(p) = p & 0xFFFF               = 157
```

A generation above 65 535 wraps: generation 70 000 is stored as 4 464.

## Segment files

| Item | Value |
|---|---|
| Location | `<data>/data/segments/%08x.seg`, e.g. `00000001.seg` |
| Contents | Node images packed back to back. Each image starts on a 64-byte boundary and occupies ⌈length / 64⌉ units. The bytes after an image up to the next boundary are never written (file holes or zeros). |
| Capacity | `segmentUnits = min(2²⁴, pagesPerSegment × pageSize / 64)`. With the defaults (16 KiB × 16 384) that is 4 194 304 units = 256 MiB. |
| Mutability | Append-only. A byte range, once written, is never rewritten. Old images become garbage when no retained generation references them, and are removed by deleting whole segments ([maintenance.md](maintenance.md)). |
| Id assignment | `segments.lastKey() + 1` |

`SegmentInfo(id, state, pages, units, retiredAt)` (`page/SegmentInfo.java`) tracks each segment:

* `pages` is the number of images allocated in it.
* `units` is the allocation high-water mark (`bytes() = units × 64`).
* `state` is `ACTIVE`, `SEALED`, `COMPACTING` or `RETIRED`.
* `retiredAt` is the generation at which the segment was retired.

The list is persisted in each catalog image ([catalog-and-generations.md](catalog-and-generations.md#catalog-images-checkpoints)) and restored by `PageStore.open`. On open, each `.seg` file found on disk gets `units = max(recorded units, ⌈file size / 64⌉)`. A file with no catalog entry (for example, one created after the last checkpoint and abandoned by a crash) is registered as `SEALED` with `pages = units = ⌈file size / 64⌉`. That is an upper bound on its image count, so its live ratio is underestimated and the segment stays a compaction candidate instead of being stranded. The newest `ACTIVE` segment resumes allocation at its recorded `units`.

### Allocation (`PageStore.allocate(epoch, length)`)

Allocation happens **after** encoding, because the exact length is only known then:

```mermaid
sequenceDiagram
    participant M as Materializer
    participant NC as NodeCodec
    participant PS as PageStore
    participant H as PageHeader
    M->>NC: encode(frozen, PageId.NONE, epoch, pageSize)
    NC-->>M: image (80 + payloadLength bytes, sealed with pageId 0)
    M->>PS: allocate(epoch, image.byteSize())
    PS->>PS: units = ⌈len / 64⌉; roll the segment if nextUnit + units > segmentUnits
    PS-->>M: pack(activeSegment, nextUnit, epoch); nextUnit += units
    M->>H: assign(image, pageId): write pageId at offset 8, recompute the crc
    M->>M: sink.accept(pageId, image, frozen): WAL record, queued write, cache admission
```

* `allocate` rejects `length > pageSize`.
* Allocation is serialized by a `ReentrantLock`, and only the counter update runs under it.
* Rolling a segment marks the previous one `SEALED`, creates the next id, and resets `nextUnit` to 0.
* `PageStore.write(pageId, image)` performs a positional `FileChannel.write` at `offset × 64` and marks the file dirty. The write happens later, inside `TransactionManager.append`, after the WAL records have been appended (see [wal-and-recovery.md](../transactions/wal-and-recovery.md)).
* `PageStore.sync()` calls `FileChannel.force(false)` on every dirty segment. The group committer calls it once per batch in `PAGE_REFERENCES` WAL mode, before the WAL sync.

Compared with fixed page slots, packing removes almost all internal fragmentation for the many small nodes a hypergraph produces. A two-member hyperedge's member tree is one leaf of roughly 100–150 bytes, which now occupies 2–3 units instead of a full 16 KiB page.

### Reading (`SegmentFile.read(position, maximum)`)

Each segment file has a read-only memory map. If the map covers the image, `read` copies exactly
`80 + payloadLength` bytes out of it into a new heap array and returns that. There is no system call, and the raw
bytes stay in the OS page cache rather than on the Java heap, which only holds the decoded nodes in `NodeCache`.

The copy is deliberate. `ByteCursor` reads one byte at a time through `MemorySegment`, and the JIT only makes that
fast when every segment it sees is the same kind. Decoding straight from the map, while the WAL, catalog and
feed decode from heap arrays, made decoding two to five times slower everywhere in a microbenchmark. Copying a
page costs well under a microsecond and keeps every decoder on heap memory.

If the image ends past the map, the file may need mapping again:

* A sealed segment is remapped at its current size right away. The store seals a segment when it rolls to the
  next one, and seals every segment except the active one when it opens.
* The active segment is remapped only after the file has grown 4 MiB past the map. Until then, reads near the end
  of the file use `pread`. This keeps a segment that is still filling up from being remapped on every new page.

Maps are created in `Arena.ofAuto()` and are unmapped once nothing references them, so a thread that is copying a
page when compaction deletes its segment still finishes the copy. `Arena.ofShared()` is not used, because closing
one in a Native Image build needs the experimental `-H:+SharedArenaSupport`. Segment files are only ever appended to
or deleted, never truncated, so a map can't end up pointing past the end of its file.

`pread` doesn't know how long an image is until it has the header, so it takes one or two system calls:

1. Allocate a buffer of `min(pageSize, 4096)` bytes and read it at `offset × 64`.
2. If at least 80 bytes came back, compute `length = 80 + payloadLength` from header offset 24:
   * if `length` is larger than `pageSize`, the header is corrupt; return the buffer and let `verify` fail it;
   * if the first read already has `length` bytes, return those;
   * otherwise allocate a buffer of exactly `length` bytes, copy in what was read, and read the rest.

A short read at the end of the file returns what it got, and the header check rejects it. Both paths return
read-only memory, so nothing can write into a stored image through a read.

## Caching and I/O accounting

`PagedNodeSource.load(pageId, schema)` (`tree/PagedNodeSource.java`):

1. `NodeCache.get(pageId)`. On a hit, verify the cached node's schema id, record a hit, and return the node.
2. On a miss: `IoTrace.recordRead()`, `PageStore.read`, then `NodeCodec.decode` (which runs `verify`), then `NodeCache.put`.
3. Newly materialized nodes are admitted at commit time (`PagedNodeSource.admit`), so freshly written data is served from memory without being re-read.

### `NodeCache` (`tree/NodeCache.java`)

The cache stores **decoded, frozen** `Node` objects, not bytes. A hit therefore costs no decoding and no allocation. The cache has 16 shards; a node goes to shard `mix(pageId) >>> 60`. Each shard has:

* a `ConcurrentHashMap<Long, Slot>` from page id to `Slot(pageId, node, volatile referenced)`;
* a `Slot[] ring` of capacity `max(16, cache_nodes / 16)` with a CLOCK hand;
* a `ReentrantLock` taken only for admission.

**Reads are lock-free.** `get` is a `ConcurrentHashMap.get`, plus setting `referenced = true` if it was clear. The check avoids a redundant volatile write on hot entries.

**Admission (`put`) uses CLOCK second-chance eviction under the shard lock:**

* An entry already holding the same node is left alone.
* An entry for the same page with a different node is replaced in its ring slot.
* Otherwise the hand advances, clearing `referenced` bits, until it finds an empty slot or an unreferenced one. That victim is removed from the map, conditionally on the identity of its `Slot`, the new slot takes its place, and the hand moves on.

This approximates LRU. Readers never contend with each other, and they contend with writers only on the map's internal bins.

`NodeCache.hits()` and `misses()` feed `EngineStats.cacheHitRate`, which appears in `STATS`, the Studio dashboard and the benchmarks.

### `IoTrace` (`page/IoTrace.java`)

A per-query I/O meter bound with `ScopedValue.where(CURRENT, trace)`. It is therefore visible on the query's own thread and on any thread that inherits the scope, without thread-locals. It counts:

* `pagesRead`: decodes on cache misses;
* `cacheHits`.

When `pagesRead + cacheHits` exceeds the budget, it throws `HStoreException.limit("query exceeded its budget of N page visits")`. The budget is `query_page_budget`, capped per tenant by its quota. `EXPLAIN` and traced results report both counters.

## Write-ahead logging of pages

For each materialized image, the commit appends one WAL record ([wal-and-recovery.md](../transactions/wal-and-recovery.md)):

* `PAGE_REFERENCES` (the default) appends `PageRef(txnId, pageId, length, crc32c(image))`, about 20 bytes. The image itself is written only to its segment, and it must be durable before the WAL record that refers to it (the data-before-log ordering in `TransactionManager.makeDurable`). Recovery accepts a committed transaction only if every referenced image reads back with a valid header and matching CRC (`Recovery.intact`). Replay stops at the first commit that fails this check.
* `PAGE_IMAGES` appends `Page(txnId, pageId, image bytes)`. Recovery rewrites the image at its address, and the data file needs no sync before the WAL.

Because images are never overwritten in place, a torn write can only damage an image that no durable commit references yet. Reference mode is safe without full-page images.

## Format versioning

| Version | Where | Meaning |
|---|---|---|
| `format=3` in `<data>/FORMAT` | `StorageEngine.verifyFormat` | Packed 64-byte-unit extents, with image sizes in every stored reference. Directories written by older builds (`format=1` fixed page slots, `format=2` references without sizes) fail to open: `uses storage format 2; this build reads format 3 (sized page references); export and reload it`. |
| `page-size=N` in `<data>/FORMAT` | same | Fixed at creation. A mismatching `--page_size` is rejected. |
| header byte 4 = `1` | `PageHeader.FORMAT` | Node image layout; unchanged by packing. |
| `SegmentInfo` in catalog images | `CatalogImage` | Now `(id, u8 state, pages, units, retiredAt)` |

## Inspecting images by hand

The layout is simple enough to inspect with standard tools. To print the header of the image at `1:1234@157` (byte 1234 × 64 = 78 976 of segment 1):

```bash
xxd -s $((1234 * 64)) -l 80 data/segments/00000001.seg
# 00013480: 4853 5047 0102 0400 9d00 d204 0001 0000  HSPG, format 1, LEAF, schema 4, flags 0, pageId…
```

`hstore check <dir>` decodes and verifies every reachable image, including checksums, identity, ordering, balance and summaries. See [maintenance.md](maintenance.md#verification-hstore-check).

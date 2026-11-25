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
| 7 | 1 | `flags` | Bit 0 (`REFERS_TO_PAGES`): a leaf with at least one value that points at another page, such as a catalog edge whose member tree is stored as a page. A leaf whose nested trees are all inline or empty leaves it clear. `NodeCodec.decode` checks it against the values. The other bits are `0`. |
| 8 | 8 | `pageId` | The image's own page number (below). It is written last, after allocation (`PageHeader.assign`). |
| 16 | 8 | `creationEpoch` | Id of the generation the image was written for: the checkpointed generation for a page a checkpoint writes, the new generation for a page a commit writes. Spilled pages use `head + 1`. |
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
* `pageId != expectedPageId` (a directory entry that points at the wrong image);
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
          or, for a codec with independentValues():
          (varint length | value) × slotCount                  (catalog, posting lists, row codecs)
INTERNAL: Ref × slotCount
          Ref := u8 0                                          empty tree
               | u8 1 | i64le pageId | varint units | Summary  stored tree
               | u8 2 | varint n | keys | values               inline tree (nested references only)
          Summary := varint count | zigzag min | varint (max − min) | zigzag weightSum | zigzag weightMin
                     | zigzag weightMax | zigzag timeMin | zigzag timeMax | i64le roleBits | i64le fingerprint
```

For an internal node, "keys" are the separators. `separators[0]` is the first child's minimum (set by `Branch.frozenWith`), so the delta stream is non-negative. Each child reference embeds the child's image size in 64-byte units and its **complete** summary. This lets readers prune and count without loading children, lets compaction measure live bytes without reading pages, and is why a branch entry costs up to `KEY_BYTES + 1 + 8 + 3 + 96` bytes in the fanout calculation (`Layout`). An empty summary is a single `0` byte.

**Reference encoding** (`tree/Ref.java`). Every reference starts with a tag byte. `0` is the empty tree and nothing follows. `1` is a stored tree: the page number, the image size in 64-byte units and the subtree's summary. `2` is an inline tree: the entry count `n` (1 to 32), then the leaf exactly as a leaf page payload encodes it, keys as a zigzag first key and varint gaps followed by `schema.codec().encode(...)`. There is no summary, because the reader recomputes it from the entries. An inline tree can only be decoded where the reader knows its schema, through `Ref.read(in, schema)`. `AtomCodec` passes the member schema of the edge's kind for `members` and `order-index` for `order`. The tree changes in a WAL `Changes` record are read with their schema as well, so a nested tree change can carry an inline tree. Everywhere else (branch children, catalog images, posting and property directories) `Ref.read(in)` rejects tag `2`, since only nested references in codecs that opt in with `inlinesSmallTrees()` are ever inline. An inline reference is never larger than `Ref.maxEncodedSize()` (108 bytes); see [persistent-tree.md](persistent-tree.md#inline-trees) for why that keeps pages from overflowing.

**Framed values.** A codec whose values are written one after another, each without reference to the others, says so with `ValueCodec.independentValues()`: `AtomCodec` (the catalog), `PostingsCodec` (posting directories) and every `ValueCodec.rows` codec. In their leaves `NodeCodec` writes each value's length in front of it, so a reader can find one value without decoding the ones before it. The length is counted in `TreeSchema.entryBytes`, so the leaf budget still keeps every image within a page. Columnar codecs, such as the member and incidence codecs, keep the plain layout. When a framed leaf is read from a page, `NodeCodec.decode` walks the lengths to record where each value starts, rejects the image if a value runs past the payload or the values stop short of its end, and returns a packed leaf ([caching](#nodecache-treenodecachejava)). An inline tree of a framed codec uses the same layout and is decoded in full.

An edge record with three plain members `{1000, 1001, 1005}` holds this as its `members` reference. It takes 7 bytes. A stored reference to the same tree would take 65 bytes, most of them summary, and point at an image of at least 128:

```text
02          tag: inline
03          n = 3
d0 0f       zigzag(1000)
01 04       gaps 1 and 4
00          IncidenceCodec flags: no columns, so no bitmaps or column values follow
```

**Size invariant.** Splits are decided from each value codec's `maxSize`, summed per entry, plus the codec's `leafOverhead(n)` and the exact key-stream size (`Leaf.bytes`, `BulkBuilder`). `IncidenceCodec.leafOverhead(n)` is `1 + 6·⌈n/8⌉`: the flags byte plus one bitmap per column, assuming every column is present. The accounting is therefore an upper bound for any leaf, and exact when all columns are present (`IncidenceCodecTest`). Other codecs have no per-leaf overhead. `Layout` still keeps a 32-byte reserve beyond `leafBudget` (`RESERVED = 32`), but correctness no longer depends on it. `NodeCodec.encode` encodes into a cursor of exactly `pageSize` bytes and would fail loudly (`node of N entries overflows a P byte page`) if the invariant were broken.

Leaf value formats in use:

| Schema | Codec | Format |
|---|---|---|
| `atoms` #1 | `catalog/AtomCodec` | Tagged node/edge records with embedded `Ref`s to member and order trees, which are inline when small ([topology.md](topology.md#encoding-catalogatomcodecjava)) |
| `set-members` #2, `ordered-members` #3 | `topology/IncidenceCodec` | Columnar: presence flags, per-column bitmaps, varint columns ([topology.md](topology.md#leaf-encoding-of-incidences-topologyincidencecodecjava)) |
| `order-index` #4 | `TopologySchemas.LONG_CODEC` | zigzag varint per value |
| posting directories (#5, #7, #9, #12, …) | `index/PostingsCodec` | `u8 0 \| varint n \| delta keys \| values` (inline) or `u8 1 \| Ref` (promoted) |
| simple rows | `ValueCodec.rows(size, writer, reader)` | values written one after another |

A worked example, produced by running `Materializer` over a two-entry `order-index` leaf (`{17 → 16777216, 18 → 33554432}`) written as page `#1234` by generation 157. The image is 139 bytes, so it occupies 3 units (192 bytes):

```text
00  48 53 50 47 01 02 04 00 00 00 d2 04 00 00 00 00   magic HSPG | format 1 | LEAF | schema 4 | flags 0 | pageId (le) = 0x4d20000
10  9d 00 00 00 00 00 00 00 3b 00 00 00 02 00 00 00   epoch 157 | payloadLength 59 | slotCount 2
20  02 00 00 00 00 00 00 00 11 00 00 00 00 00 00 00   count 2 | lower 17
30  12 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00   upper 18 | height 0 | reserved
40  0a 2a 2f 32 04 9f 42 eb da c5 41 7e 00 00 00 00   fingerprint | crc32c | reserved
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

## Page numbers and the page directory

A `PageId` (`page/PageId.java`) is a page's permanent number, not its location:

```text
 63                                             16 15            0
┌───────────────────────────────────────────────┬────────────────┐
│ index (48 bits)                               │ reuse (16 bits)│
│ 1 .. 2⁴⁸ − 1                                  │                │
└───────────────────────────────────────────────┴────────────────┘
```

* `index` starts at 1 and goes up by one for every image allocated. `0` is reserved, so `PageId.NONE = 0` means
  "no page".
* `reuse` is always 0 for now. It exists so a number can be handed out again later, once nothing can reach its old
  image, and still differ from the id the old image was written with. `PageId.toString()` prints `#index`, or
  `#index.reuse` when `reuse` isn't 0.

Where an image is stored is a `PageAddress` (`page/PageAddress.java`):

```text
 63                   40 39                   16 15            0
┌───────────────────────┬───────────────────────┬────────────────┐
│ segment (24 bits)     │ offset (24 bits)      │ units (16 bits)│
│ 1 .. 16 777 215       │ 64-byte units         │                │
└───────────────────────┴───────────────────────┴────────────────┘
```

* `segment` is the segment file id.
* `offset` counts **64-byte units** (`PageId.UNIT_BYTES = 64`) from the start of the file, so the byte position is
  `offset × 64`. With 24 bits, one segment holds at most 2²⁴ × 64 B = 1 GiB.
* `units` is the image length rounded up to whole units. 16 bits allow images up to 4 MiB, far above any page size.

The **page directory** (`page/PageDirectory.java`) maps numbers to addresses. It is the file
`<data>/data/segments/pages.dir`: a 64-byte header (`i32 magic 0x52494450`, `i32 format 1`, `i64` next index),
then one little-endian `i64` address per index, entry `i` at byte `64 + 8 × i`. An entry of 0 means the number has
no image. The file is memory-mapped in 8 MiB chunks, and a new chunk is mapped when the file grows into it, so a
lookup is a load from the OS page cache and the directory takes no Java heap. A million pages need 8 MB of it.

Trees, nested references, `NodeCache` and `Ref` use numbers and never see an address. Only `PageStore` resolves
them, and the WAL logs the address of each page a commit or a spill writes next to its number so that recovery
can put the directory entry back. That is what lets compaction move an image by copying its bytes and changing one directory entry, without
rewriting the nodes that point at it ([maintenance.md](maintenance.md#compaction)). Entries are written and read with
release and acquire ordering, so a reader racing a move sees either the old address or the new one, never a mix.

Page `#1234`, stored as 3 units at unit 1234 of segment 1:

```text
pageId           = 1234 << 16                     = 0x0000_0000_04D2_0000
address          = (1 << 40) | (1234 << 16) | 3   = 0x0000_0100_04D2_0003
directory entry  = byte 64 + 8 × 1234             = byte 9 936 of pages.dir
image            = byte 1234 × 64                 = byte 78 976 of 00000001.seg
```

Entries are written when a page is allocated and forced to disk only at checkpoints (`PageStore.checkpoint`). The
commit path never syncs the directory, because every WAL record for a page carries its address too. Pages a
checkpoint writes have no WAL record; the checkpoint forces the directory before it publishes the catalog image that
refers to them. Recovery
writes each logged address back into the directory before it checks or replays the page, so an entry that a crash
lost comes back from the log. The header's next index is updated on every allocation, and recovery raises it past
every number it restores, so a restored number is never handed out a second time. Number 0 is never used, so a
next index of 0 (a directory whose header was forced when the file was created, before the first checkpoint forced
it again) is read as 1.

`pages.dir` and `pages.ref` force the `data/segments` directory when they are created, and so does every new segment
file, so that a power loss can't drop the file itself after its contents were forced.

### Reference counts

`<data>/data/segments/pages.ref` (`page/ReferenceCounts.java`) has the same layout as the directory: a 64-byte
header (`i32 magic 0x53464552`, `i32 format 1`, `i64` state), then one little-endian `i64` per index at byte
`64 + 8 × i`, memory-mapped in 8 MiB chunks. An entry is one of:

| Entry | Meaning |
|---|---|
| `0` | No count: the number was never used, was released, or belongs to a page no checkpoint has counted yet. |
| `n > 0` | The page is in use and `n` places point at it: branch roots, branch nodes, or leaf values holding a nested tree that is stored as a page. Inline trees have no page and are never counted. |
| `0x8000_0000_0000_0000 \| g` (negative) | The page died in generation `g`. Generations before `g` can still reach it, so it keeps its address until it is released. |

The header's state is `0` when the counts can be trusted and `1` when they have to be rebuilt on the next open.
How the counts change and when pages are released is in [maintenance.md](maintenance.md#reference-counts).

The file is written only at checkpoints, through `<data>/data/segments/pages.ref.journal`
(`page/ReferenceJournal.java`):

```text
i32 magic 0x4C4E524A   i32 format 1   i64 checkpoint lsn   i32 count entries (n)   i32 released entries (m)
n × (i64 index, i64 entry)       the new value of every count changed since the last checkpoint
m × i64 index                    directory entries to clear
i32 crc32c of everything above
```

The journal is written and forced before the catalog is published and applied afterwards, then truncated to zero
bytes. On open it is applied again only if its LSN is the published checkpoint's, so a half-applied journal is
finished and one from a checkpoint that never published is ignored.

Page `#1234` in use by two parents, then dead since generation 31:

```text
xxd -s $((64 + 1234 * 8)) -l 8 -g 8 -e data/segments/pages.ref
# 000026d0: 0000000000000002   in use, two references
# 000026d0: 800000000000001f   the same entry after the page died in generation 31
```

## Segment files

| Item | Value |
|---|---|
| Location | `<data>/data/segments/%08x.seg`, e.g. `00000001.seg` |
| Contents | Node images packed back to back. Each image starts on a 64-byte boundary and occupies ⌈length / 64⌉ units. The bytes after an image up to the next boundary are never written (file holes or zeros). |
| Capacity | Grows with the store. A new segment gets 8 MiB, doubled for as long as the result is at most an eighth of the bytes in the store's other segments (retired ones not counted) and at most `maximumUnits = min(2²⁴, pagesPerSegment × pageSize / 64)`. With the defaults (16 KiB × 16 384) the maximum is 256 MiB, so segments are 8 MiB until the store holds 128 MiB, 16 MiB from there, and 256 MiB from 2 GiB on. A store that shrinks after compaction gets small segments again. |
| Mutability | Append-only. A byte range, once written, is never rewritten. Old images become garbage when no retained generation references them, and are removed by deleting whole segments ([maintenance.md](maintenance.md)). |
| Id assignment | `segments.lastKey() + 1` |

`SegmentInfo(id, state, pages, units, retiredAt)` (`page/SegmentInfo.java`) tracks each segment:

* `pages` is the number of images allocated in it.
* `units` is the allocation high-water mark (`bytes() = units × 64`).
* `state` is `ACTIVE`, `SEALED`, `COMPACTING` or `RETIRED`.
* `retiredAt` is the generation at which the segment was retired.

The list is persisted in each catalog image ([catalog-and-generations.md](catalog-and-generations.md#catalog-images-checkpoints)) and restored by `PageStore.open`. On open, each `.seg` file found on disk gets `units = max(recorded units, ⌈file size / 64⌉)`. A file with no catalog entry (for example, one created after the last checkpoint and abandoned by a crash) is registered as `SEALED` with `pages = units = ⌈file size / 64⌉`. That is an upper bound on its image count, so its live ratio is underestimated and the segment stays a compaction candidate instead of being stranded. The newest `ACTIVE` segment resumes allocation at its recorded `units`.

### Allocation (`PageStore.allocate(length)`)

Allocation happens **after** encoding, because the exact length is only known then:

```mermaid
sequenceDiagram
    participant M as Materializer
    participant NC as NodeCodec
    participant PS as PageStore
    participant H as PageHeader
    M->>NC: encode(frozen, PageId.NONE, epoch, pageSize)
    NC-->>M: image (80 + payloadLength bytes, sealed with pageId 0)
    M->>PS: allocate(image.byteSize())
    PS->>PS: units = ⌈len / 64⌉; roll the segment if nextUnit + units > activeUnits
    PS->>PS: index = next index; directory[index] = (activeSegment, nextUnit, units)
    PS-->>M: pack(index, 0); nextUnit += units
    M->>H: assign(image, pageId): write pageId at offset 8, recompute the crc
    M->>M: sink.accept(pageId, image, frozen): page write (checkpoint) or WAL record and queued write (commit), cache admission
```

* `allocate` rejects `length > pageSize`.
* Allocation is serialized by a `ReentrantLock`, and only the counter and directory updates run under it.
* Rolling a segment marks the previous one `SEALED`, creates the next id, sets `activeUnits` to the new segment's capacity, forces the `data/segments` directory so the new file's entry is durable before any page in it is, and resets `nextUnit` to 0.
* A segment's capacity is not recorded anywhere. Page addresses are a segment id and a unit offset, so nothing depends on how big a segment was meant to be. On open, the active segment's capacity is worked out again from the other segments. Segments of different sizes sit side by side, and a store written with fixed 256 MiB segments opens as it is.
* `PageStore.write(pageId, image)` looks the number up in the directory and performs a positional `FileChannel.write` at `offset × 64`, then marks the file dirty. It refuses an image longer than the units allocated for it. A checkpoint writes each image as soon as it has allocated it. For the pages a commit writes, the write happens later, inside `TransactionManager.append`, after the WAL records have been appended (see [wal-and-recovery.md](../transactions/wal-and-recovery.md)).
* `PageStore.sync()` calls `FileChannel.force(false)` on every segment written since the last sync, and leaves every other segment alone, open or not. The group committer calls it once per batch in `PAGE_REFERENCES` WAL mode, before the WAL sync, if a commit in the batch wrote pages. `PageStore.checkpoint()` does the same and then forces the page directory; only `Checkpointer` calls it.

Compared with fixed page slots, packing removes almost all internal fragmentation for the many small nodes a hypergraph produces. A member tree stored as a page is still an image of at least 128 bytes (two units), even for two members. That is why small member trees are not stored as pages at all, but inline in their edge record ([persistent-tree.md](persistent-tree.md#inline-trees)).

### Reading (`SegmentFile.read(position, maximum)`)

`PageStore.read(pageId)` looks the number up in the directory, then asks that segment for at most `units × 64` bytes
at `offset × 64`. A number with no entry fails with `corrupt(pageId, "page has no address")`.

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

Each map has its own `Arena.ofShared()`. When a segment is remapped, the old map is closed as soon as the new one is
in place, and deleting or closing a segment closes its map. A deleted segment's disk space therefore comes back
straight away instead of whenever the garbage collector gets to the map. A read that was copying from a map when it
was closed gets an `IllegalStateException`, sees that the map is no longer alive, and reads again from the current
one. Readers only touch a map for the length of one copy, and compaction only deletes segments that no reader can
reach, so this retry is rare. Closing a shared arena in a Native Image build needs `-H:+SharedArenaSupport`, which
GraalVM still lists as experimental; the `native` profile in `server/pom.xml` turns it on. Segment files are only
ever appended to or deleted, never truncated, so a map can't end up pointing past the end of its file.

`pread` doesn't know how long an image is until it has the header, so it takes one or two system calls:

1. Allocate a buffer of `min(units × 64, 4096)` bytes and read it at `offset × 64`.
2. If at least 80 bytes came back, compute `length = 80 + payloadLength` from header offset 24:
   * if `length` is larger than `units × 64`, the header is corrupt; return the buffer and let `verify` fail it;
   * if the first read already has `length` bytes, return those;
   * otherwise allocate a buffer of exactly `length` bytes, copy in what was read, and read the rest.

A short read at the end of the file returns what it got, and the header check rejects it. Both paths return
read-only memory, so nothing can write into a stored image through a read.

### Open files (`FilePool`)

A store does not keep a file open per segment. `PageStore` owns a `FilePool` of at most `max_open_segment_files`
(`EngineOptions.maxOpenSegmentFiles`, default 256) open `FileHandle`s, and every write, `pread`, map and sync on a
segment borrows its handle from the pool for the length of that one call:

* Opening the store only lists the segment files and reads their sizes with `stat`. A file is opened the first
  time a call needs it, and rolling to a new segment opens (and creates) the new file straight away.
* A handle in use by a call is never closed. When the pool is full, the handle that has gone unused the longest is
  closed to make room. If every handle is in use, the caller waits until one is returned.
* A file closed to make room is forced first if it has writes no sync has covered yet. A failed force puts the file
  back in the unsynced set and fails the call that needed the room.
* Each write marks its file unsynced. `sync()` forces only the unsynced files and takes them out of the set.
* Deleting or closing a segment closes its handle once no call is using it. A deleted segment's entry refuses to
  open again, so a late sync can't recreate the file.

A memory map does not need its file to stay open, so a sealed segment that is mapped is read without a handle at
all. Its handle is closed like any other once it is the least recently used. The map is only replaced when the
segment grows past it, which takes a handle again for the `map` call.

### Interrupted threads

A `FileChannel` closes itself, for every thread using it, when a thread is interrupted during a read, write, sync or
map on it. Commits, reads and feed subscribers run on the caller's threads, and callers do interrupt them, for
example when a request times out. Before this was handled, one interrupted commit closed the write-ahead log and
every later commit failed until the process restarted.

Segment files, the active write-ahead log segment, the change feed's segments, `pages.dir` and `pages.ref` therefore
go through `page/FileHandle`:

* It clears the caller's interrupt flag for the length of the call and sets it again afterwards, so the caller still
  sees its interrupt.
* If the channel was closed anyway (an interrupt that arrived in the middle of the call, or another thread's
  interrupt), it reopens the file and runs the call again. Every read and write is positional, so running it again
  reads or writes the same bytes.

A checkpoint and `close()` also run with the flag cleared, because they write short-lived files such as the catalog
and the reference journal.

## Caching and I/O accounting

`PagedNodeSource.load(pageId, schema)` (`tree/PagedNodeSource.java`):

1. `NodeCache.get(pageId)`. On a hit, verify the cached node's schema id, record a hit, and return the node.
2. On a miss: `IoTrace.recordRead()`, `PageStore.read`, then `NodeCodec.decode` (which runs `verify`), then `NodeCache.put`.
3. Nodes are admitted when they are written (`PagedNodeSource.admit`), by a checkpoint or by a commit that writes a large tree, so freshly written data is served from memory without being re-read. The nodes a commit leaves in memory never pass through the cache: readers reach them through `Ref.Pending` until a checkpoint writes them.

### `NodeCache` (`tree/NodeCache.java`)

The cache stores **decoded, frozen** `Node` objects, not bytes. A hit therefore costs no decoding and no allocation. The exception is a leaf of framed values, which stays **packed**: it holds the page image, the decoded keys and the offset of every value, and decodes a value the first time it is read (`Leaf.value`). The decoded value is kept in the leaf, published with a compare-and-set so another thread sees it whole, and two threads that decode the same value at once keep whichever got there first. A catalog lookup on a cold page therefore decodes the keys and one record instead of every record and every inline member list on the page. The cache has 16 shards; a node goes to shard `mix(pageId) >>> 60`. Each shard has:

* an open-addressing table of `Slot(pageId, node, volatile referenced)`, probed linearly from `mix(pageId)`, so a lookup needs no boxed key. It is kept at most half full, counting removed slots, and is rebuilt into a new array when it would pass that;
* a byte budget of `max(64 KiB, node_cache_mb / 16)` and a circular, doubly linked ring of slots with a CLOCK hand. Each slot weighs `Node.heapBytes()`, an estimate of the decoded node's heap size: 144 bytes for the node and its summary, its key and value or child arrays, then 96 bytes plus the encoded value per leaf entry, or 128 bytes per child reference in a branch. A leaf whose values are implied by its keys or member ids counts only its `long` arrays, and a catalog leaf adds the estimate of every inline tree it holds. A packed leaf starts at its image size plus its key and offset arrays. Each value it decodes adds 96 bytes plus the encoded value and any inline tree in it, and charges that to the leaf's slot (`Node.chargeTo`), which evicts under the shard lock until the shard is back within budget. A charge for a slot that was evicted, replaced or cleared is dropped. Those constants come from measuring retained heap against cache contents at two dataset sizes;
* a `ReentrantLock` taken only for admission.

**Reads are lock-free.** `get` probes the table, reading each slot with acquire ordering, plus setting `referenced = true` if it was clear. Writers change the table only under the shard lock, publishing each slot with release ordering, and a rebuild publishes the new array through a volatile field. A reader still probing the old array can find a node that was just evicted; that is harmless, because a page number's contents never change. The check avoids a redundant volatile write on hot entries.

**Admission (`put`) uses CLOCK second-chance eviction under the shard lock:**

* An entry already holding the same node is left alone.
* An entry for the same page with a different node is unlinked first, and its bytes are released.
* A node larger than the shard's whole budget isn't cached.
* While the new node doesn't fit in the budget, the hand advances, clearing `referenced` bits, until it reaches an unreferenced slot. That victim is unlinked and removed from the map, conditionally on the identity of its `Slot`.
* The new slot is linked just behind the hand, so it gets a full sweep before it can be evicted.

This approximates LRU. Readers never contend with each other, and they contend with writers only on the map's internal bins.

`NodeCache.hits()` and `misses()` feed `EngineStats.cacheHitRate`, which appears in `STATS`, the Studio dashboard and the benchmarks.

### `IoTrace` (`page/IoTrace.java`)

A per-query I/O meter bound with `ScopedValue.where(CURRENT, trace)`. It is therefore visible on the query's own thread and on any thread that inherits the scope, without thread-locals. It counts:

* `pagesRead`: decodes on cache misses;
* `cacheHits`.

When `pagesRead + cacheHits` exceeds the budget, it throws `HStoreException.limit("query exceeded its budget of N page visits")`. The budget is `query_page_budget`, capped per tenant by its quota. `EXPLAIN` and traced results report both counters.

## Write-ahead logging of pages

Most pages are never logged. A checkpoint writes the trees of a generation whose commits are already in the WAL as key changes, and it fsyncs the pages and the directory before it publishes the catalog image that points at them ([wal-and-recovery.md](../transactions/wal-and-recovery.md#9-checkpoints)). If it crashes first, recovery replays those commits again from the previous checkpoint.

Only pages written before a commit point are logged: pages a transaction spills, and the pages of a tree a commit logs by reference because it changed in more than 4,096 places. Each such image gets one WAL record under the transaction's id ([wal-and-recovery.md](../transactions/wal-and-recovery.md)):

* `PAGE_REFERENCES` (the default) appends `PageRef(txnId, pageId, address, length, crc32c(image))`, 47 or 48 bytes. The image itself is written only to its segment, and it must be durable before the WAL record that refers to it (the data-before-log ordering in `TransactionManager.makeDurable`). Recovery puts the address back into the directory, then accepts a committed transaction only if every referenced image reads back with a valid header and matching CRC (`Recovery.intact`). Replay stops at the first commit that fails this check.
* `PAGE_IMAGES` appends `Page(txnId, pageId, address, image bytes)`. Recovery puts the address back into the directory and rewrites the image there, and the data file needs no sync before the WAL.

Because images are never overwritten in place, a torn write can only damage an image that neither a durable commit nor a published checkpoint references yet. Reference mode is safe without full-page images.

## Format versioning

| Version | Where | Meaning |
|---|---|---|
| `format=10` in `<data>/FORMAT` | `StorageEngine.verifyFormat` | Memory documents: revision, section and document records in slots 57 to 59 with the `$Revision` and `$Section` system types, vector records and their owner index in slots 60 and 61, the BM25 text index and its statistics in slots 62, 63 and 71, region hierarchies in slots 64 to 66, 68 to 70, and the payload erasure journal in slot 67. Also reverse-index postings that carry each membership's validity interval, with time bounds and role bits in their summaries, and the retrieval indexes in slots 47 to 49 (hyperedge signatures), 53 (validity endpoints) and 56 (child hyperedges). Also leaf values of codecs with independent values framed by their length, commits logged as key changes (`Changes` WAL records), tree pages written at checkpoints, no history in catalog images, tagged references with small member and order trees inline in their edge record, reference counts in `pages.ref`, page numbers resolved through `pages.dir`, images packed in 64-byte units, image sizes in every stored reference. Directories written by older builds (`format=1` fixed page slots, `format=2` references without sizes, `format=3` references by address, `format=4` no reference counts, `format=5` untagged references with every nested tree on its own page, `format=6` commits that logged their pages, `format=7` unframed leaf values, `format=8` postings without validity and no retrieval indexes, `format=9` no memory documents) fail to open: `uses storage format 9; this build reads format 10 (memory documents); export and reload it`. |
| `page-size=N` in `<data>/FORMAT` | same | Fixed at creation. A mismatching `--page_size` is rejected. |
| header byte 4 = `1` | `PageHeader.FORMAT` | Node image layout; unchanged by packing and by page numbers. |
| `pages.dir` header | `PageDirectory` | `i32 magic 0x52494450`, `i32 format 1`. |
| `pages.ref` header | `ReferenceCounts` | `i32 magic 0x53464552`, `i32 format 1`. |
| `pages.ref.journal` header | `ReferenceJournal` | `i32 magic 0x4C4E524A`, `i32 format 1`. |
| `SegmentInfo` in catalog images | `CatalogImage` | Now `(id, u8 state, pages, units, retiredAt)` |

## Inspecting images by hand

The layout is simple enough to inspect with standard tools. To print the header of page `#1234`, read its
directory entry first, then the image it points at:

```bash
xxd -s $((64 + 1234 * 8)) -l 8 -g 8 -e data/segments/pages.dir
# 000026d0: 0000010004d20003   segment 1, unit 1234, 3 units
xxd -s $((1234 * 64)) -l 80 data/segments/00000001.seg
# 00013480: 4853 5047 0102 0400 0000 d204 0000 0000  HSPG, format 1, LEAF, schema 4, flags 0, pageId…
```

`hstore check <dir>` decodes and verifies every reachable image, including checksums, identity, ordering, balance and summaries. See [maintenance.md](maintenance.md#verification-hstore-check).

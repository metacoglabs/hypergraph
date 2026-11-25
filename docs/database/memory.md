# Memory documents

HStore keeps long-lived memory as Markdown documents with a full revision history. Any node can hold revisions: a
note, a project page, a meeting summary. Each revision is immutable, is split into sections at its headings, and
stays readable for as long as its node exists, independent of the engine's 64-generation history window. The code is
`memory/Documents.java`, `memory/Markdown.java` and the revision methods of `Reader` and `Writer`. The statements are
listed in the [HQL reference](hql.md#memory-documents).

## Revisions

`Writer.appendRevision(node, markdown, spec)` stores the Markdown in the payload store and writes a revision record:

| Field | Meaning |
|---|---|
| `number` | 1 for the first revision of the node, then 2, 3, … |
| `parent` | the revision it follows, or 0 |
| `contentHash` | the first 8 bytes of the SHA-256 of the Markdown |
| `validFrom`, `validTo` | when the content is true in the world; defaults to the recording time and open-ended |
| `recordedAt` | when the database stored it, in milliseconds; strictly increasing per node |
| `author`, `note` | the user who wrote it and an optional note of up to 200 characters |
| `sources` | up to 16 evidence records or readable atoms the revision came from |

Each revision is an atom of the system type `$Revision`, keyed `<node>@<number>`, so a hyperedge can name the
revision a fact was taken from. Revisions cannot be changed or deleted; a correction is a new revision.

### Time

There are two clocks. Valid time says when the content applies; recorded time says when the database learned it.
`Reader.documentAt(node, validTime, recordedAsOf)` looks at the revisions recorded at or before `recordedAsOf` whose
valid interval contains `validTime`, and returns the one with the latest `validFrom`, the latest recorded one on a
tie. A correction recorded today with `VALID FROM` last month therefore replaces the older text for last month,
while a question asked as of yesterday still gets the text the database had yesterday.

`Reader.latestRevision(node)` is the most recently recorded revision.

## Sections

A revision is split at its Markdown headings: ATX headings (`#` to `######`) and setext headings (a paragraph
underlined with `===` or `---`). Lines inside fenced code blocks (also when the fence opens on a list item or in a
quote), HTML comments, indented code and a leading YAML front matter block are never headings, and neither are list
items or quoted lines followed by `---`, which is a thematic break there. Tabs count to the next multiple of four
columns. Each section runs from its heading to the next heading of any level; text before the first
heading is section 0 with an empty path when it is not blank.

| Field | Meaning |
|---|---|
| `ordinal` | position in the revision, from 0 |
| `level` | heading level, 0 for the text before the first heading |
| `title` | the heading text, up to 120 bytes |
| `anchor` | the slugs of the heading path joined with `/`, for example `claimsbay/decisions`, at most 160 bytes; a repeated anchor in the same revision gets `~2`, `~3` |
| `start`, `end` | byte offsets into the revision's UTF-8 Markdown |
| `contentHash` | SHA-256 prefix of the section's bytes |

Anchors stay the same across revisions while the headings do, so `claimsbay/decisions` in revision 3 and in
revision 7 is the same part of the document, and equal content hashes show it did not change. Each section is an
atom of the system type `$Section`, keyed `<node>@<number>#<ordinal>`. A hyperedge can link a person, a decision and
the section that records it, and every retrieval statement can reach the section through that hyperedge.

## Full-text search

Every section is indexed for BM25 when it is written, in the same transaction, so the index always matches the
committed data and can be read at any retained generation or branch. `Reader.searchText(query, options)` and
`SEARCH TEXT` rank sections; the code is `memory/Tokenizer.java` and `memory/TextIndex.java`.

Text is normalised with NFKD and lower-cased; accents are dropped from Latin and Greek letters only, so `naïve`
matches `naive` while combining marks in other scripts stay part of their word. A term is a run of letters, digits
and combining marks. Runs joined by `.`, `-` or `_` are indexed both separately and as one term, so `Reader.retrieve`
gives `reader`, `retrieve` and `reader.retrieve`. Chinese, Japanese, Thai, Lao, Khmer and Burmese text has no spaces
between words, so it is indexed as overlapping pairs of characters. Terms are cut at 64 characters. The heading line
belongs to its section's text.

The score of a section is the sum over the query's terms of
`idf · tf · (k1 + 1) / (tf + k1 · (1 − b + b · length / average length))`, with `k1 = 1.2`, `b = 0.75` and
`idf = ln(1 + (N − df + 0.5) / (df + 0.5))`. `N` and the average length count the sections of each node's latest
revision in the tenant, or of every revision with `HISTORY`; `df` counts the same set. A repeated query term counts
once per repetition. These statistics are kept per tenant, so one tenant's writing never changes another tenant's
scores. Results the user may not read are removed after scoring, and ties go to the smaller section id.

Postings are keyed by a 64-bit hash of tenant and term and carry a further 32-bit fingerprint of the term, which is
checked on every posting. Each term has two posting lists: one for every revision and one for the sections of latest
revisions, which moves when a node gets a new revision. A search without `HISTORY` reads only the second list, so its
cost does not grow with the number of old revisions. Each posting read counts against the statement's row budget.

`REBUILD TEXT INDEX`, or `Writer.rebuildTextIndex()`, throws away the postings and statistics of the current branch
and indexes every stored section again, for all tenants, in one transaction. Use it after an upgrade that changes how
text is split into terms. It needs an administrator of the default tenant. If a stored revision cannot be read, the
rebuild fails, names the payload, and leaves the index as it was.

The new index is built in memory and written at commit, so the heap must hold it and the transaction page budget must
allow it. If another transaction indexes text while the rebuild runs, the rebuild is redone at commit on top of that
change, and other commits wait until it finishes. Run it when writes are quiet.

Because `N` and `df` count every section of the tenant, a user limited by type grants can, by watching the score of a
section they may read, learn roughly how many sections they may not read contain a term.

## Vectors

`Writer.vector(owner, view, model, version, vector)` and `EMBED … VIEW` store a vector record for any atom the user may
change, or for a revision or section of such a node. An owner can hold several views (`people`, `decisions`,
`content`, …) and several models. The record's id is derived from owner, view and model, so storing the same view
and model again replaces the vector, and two transactions storing it at once conflict instead of leaving two. A
replaced vector's bytes stay in the payload files, because older generations and branches may still read them, but
they no longer count against the tenant's quota; deleting the owner gives them back too. Views are 1 to 64
letters, digits or `_ . : / -`. Each model keeps one HNSW index of vector records, beside the per-atom index used by
`SIMILAR TO`, persisted and caught up from the change feed in the same way. `Reader.nearestVectors` searches it and
falls back to an exact scan inside a transaction or on a branch. Vectors of revisions and sections are only returned
for a node's latest revision unless `HISTORY` is given, so unchanged sections of old revisions do not crowd the
results. Every vector examined counts against the statement's row budget.

`REVISE … EMBED` encodes every section with the configured encoder and stores it as the section's `content` view.
Deleting an atom drops its vectors; purging a node erases the vectors of the node, its revisions and its sections,
including versions still in the history window. `MERGE BRANCH` copies the vectors of the revisions and sections it
copies, and drops the vectors of atoms the branch deleted.

## Fusion

`Rrf.combine(lists)` in `retrieve/Rrf.java` fuses ranked lists by reciprocal rank: an item scores `1 / (60 + rank)`
in every list it appears in. `FUSE` uses the same terms.

## Visibility

Revisions and sections belong to the tenant of their node and are visible exactly when the node is: a user who may
read the node's type sees them, and nobody else does, whatever grants exist on `$Revision` or `$Section`. Changing
them needs write access to the node. `DELETE` refuses a node that has revisions. Whole-graph queries such as
`MATCH NODE n` do not list revisions and sections; they are reached through their node or through hyperedges.

A revision is checked in full before its Markdown is written: valid Unicode, the section limit, the valid interval,
and whether every record fits in a stored value at the database's page size. If the transaction then rolls back,
fails or is retried, the Markdown it wrote is erased again. Its bytes still count against the tenant's payload quota,
as with every payload written by a transaction that rolls back, because the payload files never shrink. A
crash in the middle of such a transaction can still leave unreferenced bytes in the payload files.

A first revision written while another transaction deletes the node fails with a conflict, so revisions never outlive
their node.

## Branches

Revisions can be written on a branch. `MERGE BRANCH` copies the branch's new revisions and sections to the target.
When both sides added revisions to the same node since the fork, the merge is refused under every conflict policy,
because the two histories would claim the same revision numbers.

## Purge

`Writer.purge(node)` (`PURGE`) removes the node, all its revisions and sections, and every hyperedge that is left
with no members. A user without WRITE on every hyperedge type that holds the node, a revision or a section is
refused, as with `DELETE`. The Markdown of every revision is erased in the payload files, together with the large
text values, JSON documents and embeddings the node has now or had in any generation still in the history window:
their bytes are overwritten with zeros, and reading them afterwards, also with `AT GENERATION`, reports that the
payload was purged. Values the node replaced before the history window are not reachable through the database, but
their bytes stay in the payload files.

The erasure is recorded in the `erasures` slot in the same transaction and carried out after the commit, so it never
runs for a purge that did not commit. With `ASYNC` durability a commit can return before it is on disk, so the
erasure waits for the next checkpoint and covers only purges that checkpoint made durable. If the process stops in
between, the next open finishes it. Payload space is not reclaimed, since the payload files are
append-only; the bytes are zero, not gone. Heading titles live in the section records, which leave the live trees in
the purge commit; pages of older generations that still hold them are freed once the history window and the WAL
move past them.

Purge runs on the main branch only. A branch that forked before the purge keeps its records, but their content now
reads as purged.

## Limits

| Limit | Value |
|---|---|
| Markdown per revision | 16 MiB |
| Sections per revision | 10,000 |
| Sources per revision | 16 |
| Heading title stored | 120 bytes |
| Anchor segment | 40 characters |
| Anchor | 160 bytes |

With small pages, a long note or many sources may not fit in one stored value (about 400 bytes with 1 KiB pages);
such a revision is refused before anything is written.

Each revision and section counts as one atom against the tenant's quota, and the Markdown counts toward its payload
bytes. A purge gives both back.

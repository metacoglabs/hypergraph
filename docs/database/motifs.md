# Hypergraph motifs (`MOTIFS`, `MOTIF`)

Three hyperedges that overlap form one of a small number of shapes. `MOTIFS` counts how often each shape occurs,
`MOTIF PROFILE` counts the shapes around one hyperedge, and `MOTIF <class>` lists the triples of one shape, optionally
with conditions on who sits where. The model is the h-motif of Lee, Ko and Shin (*Hypergraph Motifs: Concepts,
Algorithms, and Discoveries*, PVLDB 13(11), 2020). The code is in `retrieve/MotifClasses.java`,
`retrieve/Projection.java`, `retrieve/MotifCensus.java` and `retrieve/MotifMatcher.java`.

## Classes

Three distinct hyperedges are connected when one of them shares a member with each of the other two. Their members
fall into seven regions, numbered by which of the three hyperedges contain them. HQL writes a region as three bits for
`e3`, `e2` and `e1`, so `'011'` holds the members of `e1` and `e2` that are not in `e3`, and `'111'` the members of all
three.

A class records which regions are non-empty, nothing else: not their sizes, the member types, or the hyperedge sizes.
Its code sets bit `r - 1` for every non-empty region `r`. Renaming the three hyperedges gives up to six codes for the
same shape, and the class is the smallest of them. There are 30 classes:

| Shape | Classes |
|---|---|
| open: one pair of the three shares no member | 20, 21, 22, 23, 30, 31 |
| closed: every pair overlaps | 52, 53, 55, 63, 67, 69, 71, 75, 77, 79, 84, 85, 86, 87, 94, 95, 116, 117, 119, 127 |
| duplicate: two or three of the hyperedges have the same members | 64, 65, 68, 76 |

The paper counts 26 classes because it removed duplicate hyperedges from its data first. HStore keeps them, since two
meetings with the same attendees are ordinary data.

An example with `e1 = {a, b, c, d}`, `e2 = {c, d, e}`, `e3 = {d, e, f}` and `e4 = {f, g}`:

| Triple | Shape | Class |
|---|---|---|
| `e1, e2, e3` | closed, `d` in all three | 94 |
| `e1, e3, e4` | open around `e3` | 31 |
| `e2, e3, e4` | open around `e3`, which has no member of its own | 30 |
| `e1, e2, e4` | not connected | |

Adding `g` to `e2` turns these into 95, 31 and 53, and makes `e1, e2, e4` a second 31.

## How counting works

Nothing is stored for motifs on the write path. One membership change can change the class of every triple that
contains the hyperedge, which on real data is tens of thousands of triples or more, so the counts are computed when
asked for.

The neighbours of a hyperedge and its overlap with each of them come from one pass over the reverse index of its
members. Region sizes follow from the three hyperedge sizes, the three pairwise overlaps and the triple overlap. The
triple overlap needs member reads only when all three pairs overlap. Neighbourhoods and member lists are kept for the
rest of the statement.

`MOTIFS EXACT` visits every hyperedge in scope as a centre and every pair of its neighbours. A triple is counted from
the centre that overlaps both others; a closed triple is counted only from its smallest id. That is the paper's
MoCHy-E. `MOTIFS SAMPLE n` draws `n` hyperedges uniformly with replacement, counts every triple containing each draw
and scales by `population / (3n)`. That is MoCHy-A, and its estimate is unbiased. The standard error column is the
sample standard error of the `n` scaled counts. Draws are repeatable: the same `SEED` on the same data gives the same
estimate as long as the row budget, not the time limit, is what ends the draws. `MOTIFS` without a mode tries the
exact count with half of the statement's row budget. If that runs out, a short pilot run with a different seed fixes
the number of draws, and the sample then makes exactly that many, so the choice between exact and sampled and the
number of draws depend on the data and the seed but not on how busy the machine is. Each part also has a five minute
safety limit.

The paper's more accurate estimator, MoCHy-A+, samples overlapping pairs uniformly. HStore has no structure that
lists them, so it is not offered.

`MOTIF PROFILE @e` counts, per class, the triples that contain `e`. Comparing a hyperedge's profile with the counts of
its type is one way to spot an unusual interaction; HStore provides the numbers, not a verdict.

## Occurrences

`MOTIF <code>` takes a code as written with positions: `e1`, `e2` and `e3` in the code are the three columns of the
result. A triple matches when some assignment of its hyperedges to the positions gives exactly that code and, with
`TYPES`, the right type in each position. Each triple is returned once. Without `AROUND`, the search runs over the same
centres as the exact count; with `AROUND @e`, only triples containing `e` are visited, and a region condition with a
fixed member only visits triples whose hyperedges contain that member.

`REGION 'bits' HAS …` asks for a member in that region. The member can be a fixed atom, any atom, or any atom of a
type. `ROLES (…)` lists one entry per hyperedge of the region in the order `e1`, `e2`, `e3`, which is the reverse of
the bit string: in `REGION '011' HAS ANY ROLES (host, guest)` the member is `host` in `e1` and `guest` in `e2`. An entry
is a role, several roles joined with `OR`, or `ANY`. The roles come from the member's incidence in each hyperedge. `NESTED` keeps triples in
which one hyperedge is a member of another.

`RANK BY` orders triples by a weight from Niu, Amburg, Aksoy and Sarıyüce (*Retrieving Top-k Hyperedge Triplets*,
IEEE BigData 2024): `COMMON` is the size of region `111`; `DISJOINT` is the smallest of the three pairwise-only regions
divided by one plus `|111|`; `INDEPENDENT` is the smallest of the three private regions divided by one plus the shared
members. A triple whose smallest pairwise overlap is below the current `k`-th weight is skipped for `COMMON` and
`DISJOINT` before its triple overlap is read.

## Census view

`CREATE VIEW v AS MOTIFS [OF Type]` keeps one cell per class, keyed by the class code. It is built with the same
automatic count as `MOTIFS` and rebuilt in full when a refresh finds changes to the tenant's memberships; an exact cell
shows the count, a sampled one `estimate ± standard error`. Because every refresh is a rebuild, the view is refreshed
on demand only and cannot be `CONTINUOUS`. See [views](views-signals-stats.md#materialized-views).

## Tenants and budget

Only the reader's hyperedges of readable types are in scope, and only members the reader can see count toward sizes
and overlaps, so a membership left over from another tenant changes nothing. An anchor, a fixed region member or a
profile target from another tenant is rejected as unknown. Every projected hyperedge, every member read and every
classified triple counts against the statement's row budget. `MOTIFS EXACT` fails when the budget runs out; a sampled
count reports how many draws it finished; `MOTIF` returns what it found and says the list may be incomplete.

## Not supported

Motifs of four or more hyperedges (1,853 classes for four), temporal motifs, classes that include roles or member
types, validity at an instant, and the hyperwedge sampler mentioned above.

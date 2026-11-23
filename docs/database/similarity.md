# Similarity search (`SIMILAR TO`)

`SIMILAR TO` finds groups of hyperedges that look like a query: the same shape with any participants, or, with
`IDENTITY`, the same participants. The query is a pattern written like a [`HYPERMATCH`](structural-matching.md)
pattern, a set of stored hyperedges, or the child hyperedges of a container. The code is
`retrieve/EditDistance.java`, `retrieve/Assignment.java` and `retrieve/Similarity.java`.

## Candidates

Searching every subhypergraph is not possible, so the statement names where candidates come from:

| Space | Candidates |
|---|---|
| `IN CHILDREN OF Type` | for every hyperedge of `Type`, the hyperedges among its members. An episode modelled as a container of its interactions is the usual case |
| `IN AROUND @e [SIZE m] [THROUGH T, …]` | every connected set of at most `m` hyperedges (default 3, at most 6) that contains `@e`, optionally only of the listed types |
| `IN SETS ((@a, @b), (@c), …)` | the listed sets |

A candidate is its hyperedges with all of their members. A hyperedge that is a member of another hyperedge in the
same candidate counts as a node labelled with its type.

## Distance

The distance is the hypergraph edit distance with incidence edits. Inserting or deleting a node or a hyperedge costs
1, changing a node's or a hyperedge's type costs 1, and adding or removing one membership costs 1. The distance is the
cheapest way to turn the query into the candidate. A meeting with one attendee missing is therefore one or two edits
away, not a different meeting.

For a fixed mapping of the query's hyperedges onto the candidate's, the best node mapping is one assignment problem,
solved exactly with the Hungarian method. The search tries every mapping of hyperedges and stops a branch once its
hyperedge cost alone reaches the best distance found. This is exact for up to 5 hyperedges on each side. A larger
candidate gets an interval: a lower bound and the cost of one good mapping.

Before that, a lower bound rejects most candidates cheaply. It adds the nodes and hyperedges that cannot be matched by
type to the larger of two sorted differences: hyperedge sizes and node degrees. It never exceeds the distance.

With `IDENTITY` the query's atoms must map to themselves, so only hyperedges are assigned and the distance is exact
for any size: the atoms in one side only, plus the cheapest pairing of hyperedges, where a pair costs its type
difference plus the members in one of the two only.

## Answers

`WITHIN d` returns every candidate whose distance is at most `d`. `TOP k` returns the `k` closest: candidates are
visited in order of their lower bound, and the search stops when the next lower bound is above the `k`-th distance
found. Columns: `candidate` (the container, the anchor, or the first hyperedge of a listed set), `edges`, `lower`,
`upper`. When `lower` equals `upper` the distance is exact. Ties are broken by the smallest atom id in the candidate,
then by its hyperedges.

Example: the query `(q1:Meeting(a:Person, b:Person, d:Doc), q2:Meeting(b, c:Person))` against three episodes:

| Episode | Contents | Distance |
|---|---|---|
| X | `Meeting {P1, P2, D1}`, `Meeting {P2, P3, P4}` | 2: one extra person and their membership |
| Y | `Meeting {P5, D2}`, `Call {P6, P7}` | 2: the call is a different type, and nobody is in both |
| Z | three calls in a chain | 7 |

## Tenants and budget

Containers, anchors and listed hyperedges must belong to the reader's tenant, and only members the reader can see are
part of a candidate. Every candidate and every hyperedge mapping tried counts against the statement's row budget. When
it runs out, the answer contains what was verified and says the list may be incomplete.

## Not supported

Attribute, role and embedding costs, candidates seeded from text, the motif and star lower bounds, and graded type
costs (a call counting as half a meeting). Positions in ordered hyperedges are ignored, and membership validity is
not part of the distance.

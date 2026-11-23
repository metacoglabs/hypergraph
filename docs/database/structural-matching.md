# Structural matching (`HYPERMATCH`)

`HYPERMATCH` ([HQL](hql.md#hypermatch)) finds every place a small pattern of hyperedges occurs: an injective map from
the query's vertices to atoms that keeps types and sends each query hyperedge to a stored hyperedge with exactly that
member set. It follows HGMatch (Yang et al., ICDE 2023), which binds one whole hyperedge per search level instead of
one vertex, with MaCH's candidate filtering (arXiv 2512.10621) as a mode. The code is `retrieve/HyperQuery.java` and
`retrieve/HyperMatch.java`.

## Signatures

A hyperedge's signature is its edge type plus the multiset of its members' types. A stored hyperedge can only match a
query hyperedge with the same signature, and slot 48 keeps, per tenant, the hyperedges of each signature. The count
of a signature (`Card`) is read from its name list in slot 49 without visiting the hyperedges.

## Order and candidates

The matching order starts with the query hyperedge of smallest `Card`, then repeatedly takes the unmatched hyperedge
that touches the matched part and minimises `Card / shared vertices`, breaking ties by smaller arity and then by
position in the statement. Anchors (`WHERE u = @…`) come first as one-vertex steps.

Each query vertex gets a mask with bit `k` set when it belongs to step `k`. When step `k` is reached, the shared
vertices of its hyperedge fall into classes `(type, mask of earlier steps)`. A stored hyperedge `c` is a candidate if
it has the right signature and contains, for every class, some matched atom of that class:

```
C = E_signature ∩ ⋂ over classes ( ⋃ over matched atoms v of the class  incident(v) )
```

It is then accepted when it is not already used by the embedding and its matched members fall into the classes with
exactly the query's multiplicities. Equal classes mean a type- and incidence-preserving bijection exists, so no
assignment of vertices is ever tried. New members must be visible to the reader; a candidate with a member of another
tenant is rejected.

## Modes

| Mode | When `AUTO` picks it | What it adds |
|---|---|---|
| `HGMATCH` | the query has an anchor, has at most two hyperedges, or its signatures hold more than 2^20 hyperedges in total | candidates computed when the search reaches each step |
| `MACH` | otherwise | before searching, each query hyperedge's candidate set is cut to hyperedges that share members of the right types with some candidate of every adjacent query hyperedge, repeated until nothing changes; the search then only reads the remaining candidates |

The filter in `MACH` mode only removes hyperedges that cannot take part in any embedding, so both modes return the
same rows. An explicit `MODE MACH` on a pattern whose signatures hold more than 2^20 hyperedges is refused rather than
read in full. `EXPLAIN HYPERMATCH` shows the order without running the filter. MaCH's in-search forward checking and
its dynamic order are not implemented.

## Bindings and parallel search

A row is one tuple of stored hyperedges, one per query hyperedge. With `BINDINGS`, each tuple expands into every
vertex assignment: query vertices with the same type and the same incident query hyperedges are interchangeable, so a
tuple has the product of the factorials of those class sizes as bindings. Bindings are generated one at a time, so
`LIMIT` stops the expansion early, and each one counts against the row budget.

When the first step has more than 64 candidates and the statement reads a snapshot, the search is split over a
fork/join pool with one worker per processor, shared by all statements; each worker runs under the statement's page
budget. Rows are sorted by
the tuple of hyperedge ids, so the result does not depend on scheduling.

## Limits

| Not supported | Instead |
|---|---|
| disconnected patterns, more than 64 hyperedges and anchors | split the pattern, or use `PATTERN` |
| untyped vertices or hyperedges | name a type for each |
| a query hyperedge as a member of another | `PATTERN` |
| roles, weights, validity, properties | filter the rows, or use `PATTERN` / `MATCH` |
| ordered positions | ordered hyperedges match as sets |

Every candidate checked, every supporter probed by the `MACH` filter and every binding produced counts as one row
against the statement budget, and page visits count against the query page budget.

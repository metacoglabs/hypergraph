# HQL reference

HQL is the statement language of the database. It is implemented by
`database/src/main/java/io/hstore/db/query/` — `Lexer` (tokens), `Parser` (recursive descent, one method per
production), `Ast` (sealed statement/expression records), `Session` (session state, transactions, branches,
time travel), `Executor` (statement execution), `Planner`/`AccessPath`/`Evaluator` (`MATCH`, see
[planner](planner.md)).

Every example on this page was executed with the native binary against the demo dataset
`examples/clinical-claims.hql`:

```sh
hstore init /tmp/demo
hstore exec /tmp/demo examples/clinical-claims.hql
hstore exec /tmp/demo -c "MATCH NODE p:Patient WHERE p.age >= 80 RETURN p.name AS who, p.age AS years;"
```

Atom ids in outputs depend on insertion order. `$variables` live in a session, so examples that bind and reuse a
variable must run in one `-c` script, one `shell` session or one wire-protocol connection.

## Lexical structure

`Lexer.tokenize` produces `WORD`, `VARIABLE`, `NUMBER`, `STRING`, `SYMBOL` and `END` tokens.

| Element | Rule |
|---|---|
| Whitespace | any `Character.isWhitespace` |
| Comment | `--` to end of line |
| Identifier / keyword | `[\p{L}_][\p{L}\p{N}_]*`; keywords are case-insensitive identifiers, there are no reserved words |
| String | `'…'` or `"…"`; the quote character is escaped by doubling (`'it''s'`); no backslash escapes |
| Number | digits with an optional fraction (a `.` belongs to the number only when a digit follows it), exponent `e`/`E` (with optional `-`), `_` separators removed (`1_000`); a leading `-` is part of the number only after a symbol other than `)`/`]` |
| Variable | `$` followed by letters, digits, `_` |
| Symbols | `( ) { } [ ] , . : ; = < > + - * / @ \| &` and the pairs `<= >= != <>` |

Statements are separated by `;`. `Parser.script` parses the whole script before anything executes, so a syntax
error anywhere aborts the entire script. `Parser.sourced` additionally returns each statement's own source text
(`Parser.Sourced(statement, text)`), which the wire protocol and Studio use for per-statement logging.
`hstore exec <dir> -c <script>` always takes the next argument as the script, so scripts may start with a `--`
comment. Execution then proceeds statement by statement and stops at the first
failing statement (`Endpoint.render`, `Server.execute`).

### Atom references

```ebnf
ref        = "@" integer                 (* atom id *)
           | "@" identifier ":" key      (* canonical key; key = string | identifier *)
           | "$" name ;                  (* session variable *)
```

`@Type:'key'` resolves through the canonical-key index for the session's tenant (`Reader.resolve`). Variables
are bound with `AS $name` by `INSERT`, `QUALIFY`, `EVIDENCE` and set algebra `INTO`, and remain bound for the
session (`Session.bind`).

Because a `.` is part of a number only when a digit follows it, `@103.status` lexes as `@`, `103`, `.`, `status`,
so `SET @103.status = 'flagged'` addresses property `status` of atom 103.

### Literals

```ebnf
literal    = "TRUE" | "FALSE" | "NULL"
           | "TIMESTAMP" string          (* ISO-8601 instant or date *)
           | "DECIMAL" (string | number)
           | string
           | ["-"] number ;              (* integral and in range -> INT, otherwise FLOAT *)
instant    = string | integer ;          (* ISO-8601 or epoch milliseconds *)
interval   = "[" (instant | "*") "," (instant | "*") ")" ;   (* half-open; * = unbounded *)
properties = "{" [ (identifier | string) ":" literal { "," … } ] "}" ;
```

Values and their storage are described in [data model](data-model.md#values).

## Statement index

| Group | Statements |
|---|---|
| [Schema](#schema-ddl) | `CREATE NODE TYPE`, `CREATE SET EDGE TYPE`, `CREATE ORDERED EDGE TYPE`, `CREATE INDEX`, `CREATE JSON INDEX`, `SHOW TYPES` |
| [Data](#data-manipulation) | `INSERT NODE`, `INSERT EDGE`, `ADD`, `REMOVE`, `REMOVE AT`, `SET`, `UNSET`, `DOCUMENT`, `DELETE` |
| [Queries](#queries) | `MATCH`, `EXPLAIN`, `MEMBERS OF`, `INCIDENT TO`, `DESCRIBE`, set algebra |
| [HORA](#hora-operators) | `GATHER`, `REDUCE`, `SCATTER`, `PROPAGATE`, `NEIGHBORS`, `OVERLAP JOIN`, `CLOSURE`, `EXPAND`, `PATTERN`, `SAMPLE` |
| [Semantic](#semantic) | `EMBED … TEXT`, `EMBED … VECTOR`, `SIMILAR TO` |
| [Evidence and state](#evidence-state-and-signals) | `EVIDENCE`, `QUALIFY`, `TRACE`, `STATE`, `SIGNAL`, `SIGNALS FOR` |
| [Transactions, branches, time](#transactions-branches-and-time-travel) | `BEGIN`, `COMMIT`, `ROLLBACK`, `USE BRANCH`, `USE MAIN`, `CREATE BRANCH`, `DROP BRANCH`, `MERGE BRANCH`, `DIFF`, `AT GENERATION`, `AS OF`, `HISTORY`, `SHOW BRANCHES` |
| [Views](#materialized-views) | `CREATE VIEW`, `REFRESH VIEW`, `VIEW`, `SHOW VIEWS` |
| [Tenancy](#tenancy-and-users) | `CREATE TENANT`, `ALTER TENANT`, `SHOW TENANTS`, `CREATE USER`, `ALTER USER`, `DROP USER`, `SHOW USERS`, `USE TENANT`, `AUTHENTICATE` |
| [Session](#session-and-output) | `WHOAMI`, `FORMAT` |
| [Maintenance](#maintenance) | `CHECKPOINT`, `COMPACT`, `STATS` |

Statements marked **ADMIN** are rejected for non-admin principals before execution
(`Executor.administrative`). Write statements additionally require the WRITER role (`Principal.requireWrite`).
Results are tables (`QueryResult.table`) or messages (`QueryResult.message`); `MATCH` results carry a trace line.

## Schema DDL

### CREATE TYPE (ADMIN)

```ebnf
create_type = "CREATE" ( "NODE" | "SET" "EDGE" | "ORDERED" "EDGE" ) "TYPE" identifier
              [ "(" property_def { "," property_def } ")" ]
              [ "ROLES" "(" identifier { "," identifier } ")" ] ;
property_def = identifier type_name { "INDEXED" | "REQUIRED" } ;
type_name    = "BOOL" | "BOOLEAN" | "INT" | "INTEGER" | "LONG" | "FLOAT" | "DOUBLE" | "REAL"
             | "STRING" | "TEXT" | "TIMESTAMP" | "TIME" | "DECIMAL" | "PAYLOAD" | "BLOB" | "JSON" | "NULL" ;
```

`ROLES` is accepted for node types by the parser but ignored (`Writer.defineNode`). Result: message
`created <KIND> type <name> #<id>`.

```sql
CREATE NODE TYPE Lab (name STRING INDEXED REQUIRED, accredited BOOL, founded TIMESTAMP, budget DECIMAL);
-- created NODE type Lab #9
CREATE SET EDGE TYPE Panel (score FLOAT INDEXED) ROLES (chair, member);
-- created SET_EDGE type Panel #10
CREATE ORDERED EDGE TYPE Workflow;
-- created ORDERED_EDGE type Workflow #11
```

### CREATE INDEX / CREATE JSON INDEX (ADMIN)

```ebnf
create_index      = "CREATE" "INDEX" "ON" identifier "(" identifier ")" ;
create_json_index = "CREATE" "JSON" "INDEX" "ON" identifier "(" string ")" "AS" type_name ;
```

Both bump the type version and backfill all tenants in the same transaction ([data model](data-model.md#backfill)).

```sql
CREATE INDEX ON Patient (city);
-- indexed Patient.city
CREATE JSON INDEX ON Patient ('$.insurance.plan') AS STRING;
-- indexed Patient documents at $.insurance.plan as STRING
```

### SHOW TYPES

Columns: `id, name, kind, properties, roles, version`.

```
| 6  | Claim         | SET_EDGE     | amount FLOAT INDEXED, status STRING INDEXED       | patient, provider, drug, pharmacy   | 1 |
| 7  | CarePathway   | ORDERED_EDGE | condition STRING INDEXED                          |                                     | 1 |
```

## Data manipulation

### INSERT NODE

```ebnf
insert_node = "INSERT" "NODE" identifier [ string ] [ properties ] [ "AS" variable ] ;
```

The optional string is the canonical key. Missing `REQUIRED` properties fail with
`<Type> requires property '<p>'`. Result column `id` with an atom cell.

```sql
INSERT NODE Lab 'lab-a' {name: 'Lab A', accredited: true, founded: TIMESTAMP '2001-05-01', budget: DECIMAL '1250000.50'} AS $a;
-- | @123 Lab:'lab-a' |
```

### INSERT EDGE

```ebnf
insert_edge   = "INSERT" "EDGE" identifier [ properties ]
                [ "MEMBERS" "(" [ member_clause { "," member_clause } ] ")" ] [ "AS" variable ] ;
member_clause = ref [ "AS" identifier { "|" identifier } ] [ "WEIGHT" number ] [ "VALID" interval ] ;
```

Roles are joined with `|`; each role must be declared by the edge type when the type declares roles. For ordered
types members are appended in clause order.

```sql
INSERT NODE Lab 'lab-b' {name: 'Lab B'} AS $b;
INSERT EDGE Panel {score: 0.8} MEMBERS ($a AS chair WEIGHT 0.9, $b AS member VALID ['2026-01-01', *)) AS $p;
-- | @125 Panel |
```

### ADD, REMOVE, REMOVE AT

```ebnf
add       = "ADD" member_clause "TO" ref [ "AT" integer ] ;
remove    = "REMOVE" ref "FROM" ref ;
remove_at = "REMOVE" "AT" integer "FROM" ref ;
```

`ADD … AT i` inserts at zero-based position `i` of an ordered edge; without `AT`, an ordered edge appends a new
member and a set edge upserts (an existing member's roles/weight/validity are replaced). A member may occur at
most once per hyperedge: `ADD … AT` of an existing member fails with
`atom <m> already participates in hyperedge <e>`.

```sql
INSERT NODE Lab 'lab-d' {name: 'Lab D'} AS $d;
ADD $d TO @127 AT 1;
-- @128 is a member of @127
MEMBERS OF @127;
-- | 0 | @123 Lab:'lab-a' | … | 1 | @128 Lab:'lab-d' | … | 2 | @124 Lab:'lab-b' | … | 3 | @126 Lab:'lab-c' |
REMOVE AT 0 FROM @127;
-- removed position 0
REMOVE @Lab:'lab-c' FROM @125;
-- removed
```

### SET, UNSET

```ebnf
set   = "SET" ref "." identifier "=" literal [ "VALID" interval ] ;
unset = "UNSET" ref "." identifier ;
```

`SET` replaces the property value and its validity interval (it does not keep older values; see
[data model](data-model.md#validity-intervals)).

```sql
SET @Lab:'lab-b'.accredited = false;
SET @Lab:'lab-b'.name = 'Lab B (north)' VALID ['2026-01-01', '2027-01-01');
UNSET @Lab:'lab-a'.budget;
```

### DOCUMENT

```ebnf
document = "DOCUMENT" ref string ;
```

```sql
DOCUMENT @Patient:'asha-rao' '{"insurance": {"plan": "gold", "id": 77}}';
-- document stored
```

### DELETE

```ebnf
delete = "DELETE" ref ;
```

Removes the catalog record, properties and embedding of the atom.

```sql
INSERT NODE Lab 'tmp' {name: 'Temp'} AS $t;
DELETE $t;
-- deleted @129
```

## Queries

### MATCH

```ebnf
match      = "MATCH" ( "NODE" | "NODES" | "EDGE" | "EDGES" ) identifier [ ":" identifier ]
             [ "WHERE" expr ]
             [ "RETURN" expr [ "AS" identifier ] { "," expr [ "AS" identifier ] } ]
             [ "ORDER" "BY" expr [ "ASC" | "DESC" ] ]
             [ "LIMIT" integer ] ;
expr       = conj { "OR" conj } ;
conj       = neg { "AND" neg } ;
neg        = "NOT" neg | predicate ;
predicate  = identifier "CONTAINS" "(" ref { "," ref } ")"
           | identifier "HAS" ref [ "AS" identifier ]
           | identifier "IN" ref
           | identifier "SIMILAR" "TO" string [ "TOP" integer ] [ "SNAPSHOT" | "FRESH" ]
           | identifier "VALID" "AT" additive
           | additive "BETWEEN" additive "AND" additive
           | additive [ ( "=" | "!=" | "<>" | "<" | "<=" | ">" | ">=" ) additive ] ;
additive   = term { ( "+" | "-" ) term } ;
term       = primary { ( "*" | "/" ) primary } ;
primary    = "(" expr ")" | ref | literal | "*"
           | identifier "(" [ expr { "," expr } ] ")"      (* function call *)
           | identifier "." identifier                     (* property *)
           | identifier ;                                  (* the match variable *)
```

Without `RETURN` the match variable is returned. Column names default to the rendered expression
(`p.name`, `card(c)`) and to `expr` for arithmetic/comparisons; use `AS`. A `RETURN` containing any aggregate
(`count`, `sum`, `avg`, `min`, `max`) produces exactly one row; `LIMIT` is then ignored.

Predicate semantics (`Evaluator.test`):

| Predicate | True when |
|---|---|
| `e CONTAINS (r1, …, rn)` | `e` is a hyperedge containing every `ri` |
| `e HAS r [AS role]` | `r` is a member of `e` (with `role` in its role set) |
| `x IN r` | `x` is a member of hyperedge `r` |
| `x SIMILAR TO 'text' TOP k` | `x` is among the global top-`k` neighbours of the text embedding |
| `x VALID AT t` | edge: some member valid at `t`; node: some membership valid at `t` |
| comparisons | `Value.compareTo`; any comparison with `NULL` is false except `a != NULL` with non-null `a` |

Functions (`Evaluator.call`):

| Function | Result |
|---|---|
| `id(x)` | atom id |
| `type(x)`, `key(x)` | type name, canonical key or `NULL` |
| `card(e)`, `cardinality(e)` | member count |
| `degree(x)` | number of incident hyperedges |
| `weight(e)`, `weightsum(e)` | sum of member weights (from the membership summary) |
| `overlap(a, b)`, `jaccard(a, b)` | shared members, Jaccard similarity |
| `members(e)` | list cell of member atoms |
| `state(x)`, `state(x, t)` | current state binding / binding valid at `t` |
| `json(x, '$.path')` | first value selected by the JSON path from `x`'s document, `NULL` if there is no document or no match |
| `lower(s)`, `upper(s)`, `length(s)` | text functions |
| `now()` | current wall time as `TIMESTAMP` |
| `count(*)`, `count(e)`, `sum(e)`, `avg(e)`, `min(e)`, `max(e)` | aggregates; `NULL` inputs ignored |

Arithmetic on two `INT`s (except `/`) stays exact (`Math.addExact` etc.); everything else is `FLOAT`.

Result: a table plus `QueryResult.Trace` (`generation, pages read, cache hits, ms`).

```sql
MATCH EDGE c:Claim WHERE c CONTAINS (@Patient:'ines-duarte', @Provider:'dr-ada-park') RETURN c, c.amount, card(c);
```
```
+------------+----------+---------+
| c          | c.amount | card(c) |
+------------+----------+---------+
| @101 Claim | 8036.86  | 3       |
| @103 Claim | 922.63   | 4       |
| @106 Claim | 7457.51  | 2       |
+------------+----------+---------+
3 rows
generation 188, pages read 0, cache hits 8, 1.530334 ms
```

```sql
MATCH NODE p:Patient WHERE p.age BETWEEN 30 AND 40 RETURN count(*), avg(p.age), min(p.name), max(p.age), sum(p.age);
-- | 5 | 32.4 | Dara Okafor | 36 | 162 |
MATCH NODE p:Patient WHERE p.city = 'Boston' AND NOT p.age > 50 RETURN p.name, p.age ORDER BY p.age;
MATCH NODE p:Patient WHERE p IN @101 RETURN p.name, p.age, degree(p);
MATCH EDGE c:Claim WHERE c VALID AT TIMESTAMP '2026-01-01' AND card(c) >= 4 RETURN c LIMIT 3;
MATCH NODE p WHERE p = @Patient:'asha-rao' RETURN type(p), key(p), degree(p);
MATCH EDGE e WHERE e.amount > 9000 RETURN e;
MATCH EDGE c:Claim WHERE c CONTAINS (@Provider:'dr-gil-moran') RETURN count(*);
```

JSON paths are addressed with `json(x, '$.path')`. A comparison or `BETWEEN` of that call with literals is planned
as an `IndexRange` when the match type has a JSON index on exactly that path
([planner](planner.md#access-paths)); otherwise it is evaluated per atom by parsing its document. From
`QueryLanguageTest.jsonPathPredicatesUseJsonIndexes`:

```sql
CREATE JSON INDEX ON Person ('$.address.zip') AS INT;
DOCUMENT $alice '{"address": {"zip": 94110}}';
DOCUMENT $bob '{"address": {"zip": 10001}}';
DOCUMENT $carol '{}';
EXPLAIN MATCH NODE p:Person WHERE json(p, '$.address.zip') = 94110;   -- IndexRange(Person.$.address.zip [94110, 94110]) …
MATCH NODE p:Person WHERE json(p, '$.address.zip') = 94110 RETURN p.name;   -- Alice
MATCH NODE p:Person WHERE json(p, '$.address.zip') < 50000 RETURN p.name;   -- Bob
MATCH NODE p:Person WHERE p.name = 'Bob' RETURN json(p, '$.address.zip');   -- 10001
```

### EXPLAIN

```ebnf
explain = "EXPLAIN" match ;
```

Returns the plan as a message: the chosen access path with estimated rows and cost, the residual `Verify`
filters in evaluation order, `Project`, `Sort`, `Limit`, and every rejected candidate. See
[planner](planner.md#explain-output).

```
IncidentIntersection[@9, @25]  rows≈1  cost≈12
  -> Verify(c.amount > 100)
  -> Project(c)
  rejected CatalogScan(edges) rows≈128 cost≈49
  rejected TypeScan(Claim) rows≈28 cost≈19
  rejected IndexRange(Claim.amount (100, +inf]) rows≈28 cost≈17
```

### MEMBERS OF

```ebnf
members = "MEMBERS" "OF" ref [ "ROLE" identifier ] [ "WEIGHT" "BETWEEN" number "AND" number ]
          [ "VALID" "AT" additive ] [ "POLICY" policy ] [ "LIMIT" integer ] ;
policy  = "OBSERVED" | "ANY" | "SUPPORTED" [ number ] ;     (* SUPPORTED default 0.5 *)
```

Columns: `position, member, roles, weight, valid, qualifier`. `position` is `-1` for set edges. Role, weight and
validity filters use the membership summaries for pruning (`Hyperedge.withRoleSets`, `weightedBetween`,
`validAt`). `POLICY` filters by the member's qualifier ([evidence](evidence-and-temporal.md#evidence-policies));
the default is `ANY`.

```sql
MEMBERS OF @125 ROLE chair;
-- | -1 | @123 Lab:'lab-a' | chair | 0.9 | [*, *) | null |
MEMBERS OF @125 WEIGHT BETWEEN 0.5 AND 1.0 LIMIT 5;
MEMBERS OF @125 VALID AT TIMESTAMP '2025-06-01';
```

### INCIDENT TO

```ebnf
incident = "INCIDENT" "TO" ref [ "VALID" "AT" additive ] ;
```

Columns: `edge, roles, position` — the hyperedges the atom belongs to, its roles in each and its position in
ordered ones. With `VALID AT t` only memberships whose validity contains `t` are listed
(`Temporal.activeEdges`).

```sql
INCIDENT TO @Lab:'lab-b' VALID AT TIMESTAMP '2025-06-01';
-- | @127 Workflow |  | 1 |
```

### DESCRIBE

```ebnf
describe = "DESCRIBE" ref ;
```

Columns `attribute, value`: `id, type, kind, key`, for edges `cardinality, weight sum, fingerprint, height`
(membership tree height), then `degree`, all properties, `document` and `state`.

### Set algebra

```ebnf
set_algebra = ( "INTERSECT" | "UNION" | "DIFFERENCE" | "SUBSET" | "EQUAL" | "JACCARD" | "CONTAINMENT" | "OVERLAP" )
              ref [ "," ] ref [ "INTO" variable ] ;
```

Operates on the membership trees of two hyperedges (`TreeAlgebra`). `SUBSET`, `EQUAL` return a boolean,
`JACCARD`, `CONTAINMENT` (|A∩B|/min(|A|,|B|)) a float, `OVERLAP` the intersection size, `INTERSECT`/`UNION`/
`DIFFERENCE` a `member` list. With `INTO $v`, `UNION`, `INTERSECT` and `DIFFERENCE` of two **set** hyperedges
create a new hyperedge of the left operand's type, built from the result incidences (left incidence wins on
`UNION`), and bind it; the new edge has no properties.

```sql
INTERSECT @101, @103;      -- member: @9, @25
JACCARD @101, @103;        -- 0.4
SUBSET @106, @103;         -- true
OVERLAP @101 @103;         -- 2
CONTAINMENT @106, @101;    -- 1.0
UNION @101, @106 INTO $u;  -- | @130 Claim | 3 |
```

## HORA operators

Grammar here, semantics and complexity in [HORA](hora.md). A `field` is `weight`, `state`, `degree` or a
property name (`Field.parse`).

```ebnf
gather    = "GATHER" field "FROM" ref ;
reduce    = "REDUCE" ( "SUM" | "MIN" | "MAX" | "MEAN" | "AVG" | "COUNT" ) "(" field ")" "OVER" ref [ "WEIGHTED" ] ;
scatter   = "SCATTER" field "FROM" "EDGES" identifier [ "WEIGHTED" ] ;
propagate = "PROPAGATE" field "OVER" "EDGES" identifier [ "WEIGHTED" ] ;
neighbors = "NEIGHBORS" "OF" ref [ "THRESHOLD" integer ] [ "LIMIT" integer ] ;
overlap   = "OVERLAP" "JOIN" identifier "THRESHOLD" integer [ "LIMIT" integer ] ;
closure   = "CLOSURE" ref "DIM" integer [ "LIMIT" integer ] ;
expand    = "EXPAND" ref [ "DEPTH" integer ] [ "LIMIT" integer ] ;     (* DEPTH default 4 *)
pattern   = "PATTERN" "(" pvar { "," pvar } ")" [ "WHERE" pcons { "AND" pcons } ] [ "LIMIT" integer ] ;
pvar      = identifier ":" ( "EDGE" | "NODE" ) [ identifier ] ;
pcons     = "CARD" "(" identifier ")" bounds
          | "SHARED" "(" identifier "," identifier ")" bounds
          | identifier "CONTAINS" identifier
          | identifier "HAS" identifier "AS" identifier
          | identifier "SUBSET" identifier
          | identifier "VALID" "AT" instant
          | identifier ( "!=" | "<>" ) identifier
          | identifier "=" ref ;
bounds    = "BETWEEN" integer "AND" integer | ( ">=" | ">" | "<=" | "<" | "=" ) integer ;
sample    = "SAMPLE" integer "SWAPS" "ON" identifier [ "SEED" integer ] "INTO" "BRANCH" identifier ;   (* ADMIN *)
```

| Statement | Columns |
|---|---|
| `GATHER` | `member, value, weight, roles` |
| `REDUCE` | one column named after the reducer (`sum`, `min`, `max`, `mean`, `count`) |
| `SCATTER`, `PROPAGATE` | `node, value` |
| `NEIGHBORS` | `neighbor` |
| `OVERLAP JOIN` | `left, right, overlap` |
| `CLOSURE` | `simplex` |
| `EXPAND` | `path, atom, status` |
| `PATTERN` | one column per pattern variable |
| `SAMPLE` | message `branch <b> holds <a> accepted and <r> rejected swaps (seed <s>)` |

```sql
GATHER age FROM @103;
REDUCE sum(weight) OVER @103;          -- 3.88
REDUCE avg(age) OVER @103;             -- mean: 49
REDUCE max(degree) OVER @103 WEIGHTED; -- 12.32
SCATTER amount FROM EDGES Claim WEIGHTED;
PROPAGATE age OVER EDGES CarePathway;
NEIGHBORS OF @Patient:'ines-duarte' THRESHOLD 2;
NEIGHBORS OF @103 THRESHOLD 2 LIMIT 5;
OVERLAP JOIN Claim THRESHOLD 3 LIMIT 5;
CLOSURE @106 DIM 1;                    -- [@9], [@25], [@9, @25]
EXPAND @120 DEPTH 2 LIMIT 20;
PATTERN (c: EDGE Claim, p: NODE Patient, d: NODE Provider)
  WHERE c HAS p AS patient AND c HAS d AS provider AND p = @Patient:'ines-duarte' AND d = @Provider:'dr-ada-park'
  LIMIT 10;
PATTERN (a: EDGE Claim, b: EDGE Prescription) WHERE SHARED(a, b) >= 3 AND CARD(a) >= 3 LIMIT 5;
PATTERN (s: EDGE Claim, i: EDGE Investigation) WHERE i CONTAINS s AND i = @120;
PATTERN (inner: EDGE Claim, outer: EDGE Claim) WHERE inner SUBSET outer AND inner != outer LIMIT 5;
SAMPLE 200 SWAPS ON Claim SEED 42 INTO BRANCH null_model;
-- branch null_model holds 170 accepted and 30 rejected swaps (seed 42)
```

## Semantic

```ebnf
embed_text   = "EMBED" ref "TEXT" string ;
embed_vector = "EMBED" ref "VECTOR" "[" number { "," number } "]" "MODEL" ( identifier | string ) ;
```

`EMBED … TEXT` encodes with the configured encoder (default `hashing-256`); `EMBED … VECTOR` stores a
caller-supplied vector under the named model (version 1). Similarity search is the `SIMILAR TO` predicate of
`MATCH`. See [semantic](semantic.md).

```sql
EMBED @Drug:'warfarin' TEXT 'anticoagulant blood thinner that prevents clots';
-- embedded with hashing-256
EMBED @Drug:'sertraline' VECTOR [0.1, 0.9, 0.2] MODEL 'custom-3d';
-- embedded 3-dimensional vector with custom-3d
MATCH NODE d:Drug WHERE d SIMILAR TO 'blood clot prevention' TOP 2 RETURN d.name;
```

## Evidence, state and signals

```ebnf
evidence = "EVIDENCE" string [ "SOURCE" ref ] [ "ATTRIBUTES" properties ] [ "AS" variable ] ;
qualify  = "QUALIFY" ref "IN" ref "AS" assertion_type [ "CONFIDENCE" number ]
           [ "EVIDENCE" "(" ref { "," ref } ")" ] [ "AS" variable ] ;
assertion_type = "OBSERVED" | "INFERRED" | "HYPOTHESIZED" | "PROJECTED" | "SIMULATED" | "REJECTED" ;
trace    = "TRACE" ref [ "DEPTH" integer ] ;                                   (* DEPTH default 8 *)
state    = "STATE" ref "=" literal [ "SCHEMA" ( identifier | string ) ] [ "VALID" interval ] ;
signal   = "SIGNAL" "ON" ref "CLOCK" ( identifier | string ) "FROM" instant "TO" instant
           [ "RESOLUTION" integer ] [ "SCHEMA" ( identifier | string ) ] ;
signals  = "SIGNALS" "FOR" ref { "," ref } "DURING" interval ;
```

`TRACE` columns: `depth, step, id, detail`; `SIGNALS FOR` columns: `atom, signal, clock, from, to`. Semantics in
[evidence and temporal](evidence-and-temporal.md) and [views, signals and statistics](views-signals-stats.md).

```sql
EVIDENCE 'claims-audit-2026' SOURCE @Pharmacy:'harbor-rx' ATTRIBUTES {method: 'manual review', batch: 7} AS $e;
QUALIFY @Provider:'dr-ada-park' IN @103 AS INFERRED CONFIDENCE 0.6 EVIDENCE ($e) AS $q;
MEMBERS OF @103 POLICY OBSERVED;        -- the INFERRED member is excluded
MEMBERS OF @103 POLICY SUPPORTED 0.5;   -- included (0.6 >= 0.5)
TRACE $q DEPTH 4;
```
```
+-------+-----------+--------------------------+------------------------------------------------------+
| depth | step      | id                       | detail                                               |
+-------+-----------+--------------------------+------------------------------------------------------+
| 0     | assertion | @133                     | INFERRED confidence 0.6                              |
| 1     | evidence  | @132                     | by claims-audit-2026 {method=manual review, batch=7} |
| 2     | source    | @44 Pharmacy:'harbor-rx' |                                                      |
+-------+-----------+--------------------------+------------------------------------------------------+
```

```sql
STATE @Patient:'asha-rao' = 'admitted' SCHEMA 'care' VALID ['2026-03-01', '2026-03-09');
MATCH NODE p:Patient WHERE p.name = 'Asha Rao'
  RETURN state(p), state(p, TIMESTAMP '2026-03-05'), state(p, TIMESTAMP '2026-04-01');
-- | admitted | admitted | null |
SIGNAL ON @Patient:'ines-duarte' CLOCK 'icu-monitor' FROM '2026-03-01T00:00:00Z' TO '2026-03-02T00:00:00Z'
  RESOLUTION 1000 SCHEMA 'heart-rate';
SIGNALS FOR @103, @101 DURING ['2026-03-01T12:00:00Z', '2026-03-01T13:00:00Z');
-- | @9 Patient:'ines-duarte' | @134 | icu-monitor | 1772323200000 | 1772409600000 |
```

## Transactions, branches and time travel

```ebnf
begin    = "BEGIN" [ "SERIALIZABLE" ] ;
commit   = "COMMIT" ;
rollback = "ROLLBACK" | "ABORT" ;
use      = "USE" ( "MAIN" | "BRANCH" identifier | "TENANT" identifier ) ;
at       = "AT" "GENERATION" integer statement ;
as_of    = "AS" "OF" instant statement ;
create_branch = "CREATE" "BRANCH" identifier [ "FROM" identifier ] ;                         (* ADMIN *)
drop_branch   = "DROP" "BRANCH" identifier ;                                                (* ADMIN *)
merge_branch  = "MERGE" "BRANCH" identifier [ "INTO" identifier ]
                [ "ON" "CONFLICT" ( "FAIL" | "SOURCE" | "TARGET" ) ] ;                      (* ADMIN *)
diff     = "DIFF" ( "GENERATION" integer "AND" integer | "BRANCH" identifier "AND" identifier ) ;   (* ADMIN *)
history  = "HISTORY" [ "LIMIT" integer ] ;                                                  (* ADMIN *)
show_branches = "SHOW" "BRANCHES" ;
```

Session semantics (`Session`):

- Without `BEGIN`, every statement runs in its own transaction (`HypergraphDatabase.write`, retried up to 8 times
  on conflict) or snapshot.
- `BEGIN` opens a session transaction with `SNAPSHOT` isolation (`SERIALIZABLE` adds read-set validation, see
  [transactions](../transactions/transactions.md)); subsequent reads see its writes. `COMMIT` publishes it,
  `ROLLBACK` aborts it; closing a session aborts an open transaction.
- `USE BRANCH b` / `USE MAIN` / `USE TENANT t` are rejected inside a transaction.
- `AT GENERATION g s` and `AS OF t s` run the single statement `s` against a retained historical generation
  (`Database.readAt`); writes inside are rejected (`historical generations are read-only`). `AS OF` resolves the
  newest retained generation whose wall time is at or before `t`; the number of retained generations is the
  engine `history_limit`.
- `MERGE BRANCH s` defaults the target to the branch's parent; merging a branch marks it merged and it disappears
  from `SHOW BRANCHES`. `DIFF` lists topology changes only (`created`, `deleted`, `added`, `removed`, `updated`
  members), not property changes.
- `HISTORY LIMIT n` replays the change feed from generation `last - n` (default 20), so it shows the commits among
  the last `n` generations; commits that changed nothing are not in the feed. Columns:
  `generation, txn, time, branch, members, slots`.
- `SHOW BRANCHES` columns: `id, name, parent, base, created`.

```sql
BEGIN; INSERT NODE Lab 'lab-x' {name: 'X'}; ROLLBACK;
MATCH NODE l:Lab WHERE key(l) = 'lab-x' RETURN count(*);   -- 0
BEGIN SERIALIZABLE; SET @Lab:'lab-a'.accredited = true; COMMIT;
-- committed transaction 192 at generation 192
AT GENERATION 185 MATCH NODE l:Lab RETURN count(*);         -- 3  (current: 4)
AS OF '2026-10-04T01:06:00Z' MATCH NODE l:Lab RETURN count(*);
AT GENERATION 185 SET @Lab:'lab-a'.accredited = true;      -- error: historical generations are read-only
DIFF GENERATION 186 AND 190;
-- | @127 Workflow | removed | @123 |
-- | @127 Workflow | added   | @128 |
HISTORY LIMIT 3;

CREATE BRANCH whatif; USE BRANCH whatif;
SET @Lab:'lab-b'.accredited = true;
INSERT NODE Lab 'lab-w' {name: 'What-if lab'};
USE MAIN;
DIFF BRANCH main AND whatif;          -- | @131 Lab:'lab-w' | created | |
MERGE BRANCH whatif;
-- merged whatif: 1 atoms created, 0 deleted, 0 membership and 2 property changes

CREATE BRANCH experiment; USE BRANCH experiment; SET @Lab:'lab-a'.name = 'Lab A (experiment)';
USE MAIN; SET @Lab:'lab-a'.name = 'Lab A (main)';
MERGE BRANCH experiment;
-- error [RETRYABLE_CONFLICT, retryable]: merging experiment conflicts on 1 keys: property @123/1
MERGE BRANCH experiment INTO main ON CONFLICT target;
-- merged experiment: 0 atoms created, 0 deleted, 0 membership and 0 property changes, 1 conflicts resolved for TARGET
CREATE BRANCH b1; CREATE BRANCH b2 FROM b1; DROP BRANCH b2; DROP BRANCH b1;
```

## Materialized views

```ebnf
create_view  = "CREATE" "VIEW" identifier "AS"
               ( "DEGREE" | "CARDINALITY" | "ACTIVITY" [ "BUCKET" integer ] | "OVERLAP" "TOP" integer )
               [ "CONTINUOUS" ] ;                                        (* ADMIN; BUCKET default 60000 ms *)
refresh_view = "REFRESH" "VIEW" identifier ;                              (* ADMIN *)
view         = "VIEW" identifier [ "KEY" ( ref | integer ) ] [ "LIMIT" integer ] ;   (* ADMIN *)
show_views   = "SHOW" "VIEWS" ;
```

`VIEW v KEY k` columns: `key, value, staleness`; `VIEW v` columns: `key, value`; `SHOW VIEWS` columns:
`id, name, kind, refresh, parameter, generation`. See [views](views-signals-stats.md#materialized-views).

```sql
CREATE VIEW provider_degree AS DEGREE CONTINUOUS;
CREATE VIEW claim_overlap AS OVERLAP TOP 3;
CREATE VIEW activity AS ACTIVITY BUCKET 3600000;
VIEW provider_degree KEY @Provider:'dr-ada-park';   -- | 25 | 14 | 0 |
VIEW claim_overlap KEY @103;                         -- | 103 | @50×2, @101×2, @106×2 | 1 |
REFRESH VIEW claim_overlap;                          -- view claim_overlap refreshed to generation 234
VIEW activity LIMIT 3;                               -- | 497521 | +299 -2 |
```

## Tenancy and users

```ebnf
create_tenant = "CREATE" "TENANT" identifier [ "QUOTA" quota ] ;       (* ADMIN *)
alter_tenant  = "ALTER" "TENANT" identifier "QUOTA" quota ;            (* ADMIN *)
quota         = "(" quota_key number { "," quota_key number } ")" ;
quota_key     = "atoms" | "edges" | "payload_bytes" | "transaction_pages" | "query_pages" ;
create_user   = "CREATE" "USER" ( identifier | string ) "PASSWORD" string
                [ "TENANT" identifier ] [ "ROLE" ( "admin" | "writer" | "reader" ) ] ;   (* ADMIN; ROLE default writer *)
alter_user    = "ALTER" "USER" ( identifier | string ) "PASSWORD" string ;            (* ADMIN *)
drop_user     = "DROP" "USER" ( identifier | string ) ;                               (* ADMIN *)
use_tenant    = "USE" "TENANT" identifier ;
authenticate  = "AUTHENTICATE" ( identifier | string ) "PASSWORD" string ;
show          = "SHOW" ( "TENANTS" | "USERS" ) ;                                      (* ADMIN *)
```

`SHOW TENANTS` columns: `id, name, atoms, edges, payload bytes, max atoms, max edges`; `SHOW USERS`: `user,
tenant, role` (tenant id). The tenancy model is specified in [security](security.md).

```sql
CREATE TENANT clinic_b QUOTA (atoms 3, edges 1);
ALTER TENANT clinic_b QUOTA (payload_bytes 1048576, query_pages 100000);
CREATE USER analyst PASSWORD 's3cret' TENANT clinic_b ROLE writer;
CREATE USER auditor PASSWORD 'pw' ROLE reader;
ALTER USER auditor PASSWORD 'pw2';
DROP USER auditor;
USE TENANT clinic_b;
INSERT NODE Lab 'b1' {name: 'B1'}; INSERT NODE Lab 'b2' {name: 'B2'}; INSERT NODE Lab 'b3' {name: 'B3'};
INSERT NODE Lab 'b4' {name: 'B4'};
-- error [ABORTED_RESOURCE_LIMIT]: tenant #1 would exceed its quota: usage Usage[atoms=4, edges=0, payloadBytes=0] against Quota[…]
AUTHENTICATE analyst PASSWORD 's3cret';
CREATE NODE TYPE Y;
-- error [INVALID_SCHEMA]: user analyst lacks the ADMIN role required for this statement
```

## Session and output

```ebnf
whoami = "WHOAMI" ;
format = "FORMAT" ( "table" | "json" ) ;
```

`WHOAMI` columns: `user, tenant, role, branch`. `FORMAT JSON` switches the session renderer to
`QueryResult.toJson()`:

```json
{"columns":["d","d.name"],"rows":[[{"label":"Drug:'atorvastatin'","id":35},"Atorvastatin"]],"message":"1 row",
 "trace":{"generation":246,"plan":"IndexRange(Drug.class ['statin', 'statin'])","estimatedRows":1,"pagesRead":1,"cacheHits":7,"elapsedMicros":4819}}
```

Atom cells render as `{"id", "label"}`, list cells as arrays, `NULL` as `null`, non-finite floats as strings.

## Maintenance

```ebnf
checkpoint = "CHECKPOINT" ;   (* ADMIN *)
compact    = "COMPACT" ;      (* ADMIN *)
stats      = "STATS" ;        (* ADMIN *)
```

```sql
CHECKPOINT;   -- checkpoint at generation 246, lsn 222166
COMPACT;      -- compacted segments [] at generation 246
STATS;
```

`STATS` columns `metric, value`: catalog statistics (see [statistics](views-signals-stats.md#statistics)),
engine counters (`commits`, `rebased commits`, `conflicts`, `pages read`, `pages written`, `data bytes written`,
`wal bytes`, `wal segments`, `cache hit rate`, `data segments`, `feed bytes`), the `semantic index generation`
and one `planner feedback <path>` row per access-path kind with a learned correction factor.

## Errors

Errors render as `error [<CODE>(, retryable)]: <message>` (`HStoreException.Code`):

| Code | Retryable | Raised for |
|---|---|---|
| `RETRYABLE_CONFLICT` | yes | OCC conflicts, merge conflicts under `FAIL` |
| `RETRYABLE_IO` | yes | transient I/O failures |
| `ABORTED_RESOURCE_LIMIT` | no | quotas, query page budgets, HORA budgets |
| `INVALID_SCHEMA` | no | syntax errors, unknown names, role violations, type mismatches |
| `CORRUPT_PAGE` | no | page checksum failures |
| `CORRUPT_LOG` | no | write-ahead log corruption detected at a given LSN (see [WAL and recovery](../transactions/wal-and-recovery.md)) |

# Wire protocol

The HQL wire protocol is a line-oriented UTF-8 text protocol over TCP (default port `7432`). It is
implemented by [`WireProtocol.java`](../../server/src/main/java/io/hstore/server/WireProtocol.java) (framing)
and [`Server.java`](../../server/src/main/java/io/hstore/server/Server.java) (conversation). It has no binary
framing and no length prefixes. With [`tls = on`](configuration.md) the same protocol runs inside TLS on the
same port; otherwise it is plaintext and belongs on a trusted network.

## Framing

A *message* is any UTF-8 text. It is transmitted as its lines, each terminated by `\n`, followed by a line
containing exactly one dot. A line of the message that begins with `.` is sent with one extra leading `.`
(dot-stuffing, as in SMTP's DATA section).

```
encode(message):
    for line in message.split("\n"):            # every line, including empty ones
        if line starts with ".": line = "." + line
        write(line + "\n")
    write(".\n")

decode(stream):
    lines = []
    for line in stream.lines():                 # "\n" or "\r\n"
        if line == ".": return join(lines, "\n")
        if line starts with "..": line = line[1:]
        lines.append(line)
    return EOF                                  # peer closed
```

Byte-level example. The message `SHOW TYPES;` followed by a line `.hidden` is sent as:

```
53 48 4f 57 20 54 59 50 45 53 3b 0a     S H O W   T Y P E S ; \n
2e 2e 68 69 64 64 65 6e 0a              . . h i d d e n \n
2e 0a                                   . \n
```

The empty message is the single line `.`.

## Conversation

Every exchange is strictly request–response on one connection. The server sends exactly one message per
client message, so clients do not need to pipeline.

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Server
    C->>S: TCP connect
    alt max_connections reached
        S-->>C: "error [ABORTED_RESOURCE_LIMIT]: too many connections"
        S-->>C: close
    else accepted
        S-->>C: banner "hstore connection 6 at generation 159; authentication required"
        loop until authenticated
            C->>S: "AUTHENTICATE admin PASSWORD 'pw'"
            S-->>C: "authenticated as admin" or "error [AUTHENTICATION_FAILED]: authentication failed"
        end
        Note over C,S: the 5th rejected request closes the connection
        loop requests
            C->>S: HQL script (one or more statements)
            S-->>C: rendered results, separated by blank lines
        end
        C->>S: close (or idle_timeout_seconds elapses)
    end
```

### Banner

```
hstore connection <n> at generation <g>[; authentication required]
```

`<n>` is the server-wide connection counter and `<g>` the latest durable generation. The suffix
`; authentication required` appears when [`authentication`](configuration.md#network-and-sessions) is `on`,
or when it is `auto` and at least one user exists.

### Authentication

While a connection is unauthenticated, each request must contain exactly one `AUTHENTICATE` statement:

```
AUTHENTICATE <user> PASSWORD '<password>'
```

`<user>` is an identifier or a quoted string. Inside quoted strings, `'` is escaped as `''`. Possible
replies:

| Reply | Meaning |
|---|---|
| `authenticated as <user>` | The session now runs as that user's principal (tenant and role). |
| `error [AUTHENTICATION_FAILED]: authentication failed` | Unknown user or wrong password. |
| `error [AUTHENTICATION_REQUIRED]: authenticate first: AUTHENTICATE <user> PASSWORD '<password>'` | Any other statement. |
| `error [INVALID_SCHEMA]: syntax error …` | The request did not parse. |

Every rejected request counts as a failed attempt. After `MAX_AUTHENTICATION_ATTEMPTS` = 5 failures, the
server logs a WARNING and closes the connection without replying further. Clients must reconnect to try
again. `AUTHENTICATION_FAILED` and `AUTHENTICATION_REQUIRED` occur only in this handshake. They are protocol
codes, not `HStoreException` codes.

Passwords are verified with PBKDF2-HMAC-SHA256 (120,000 iterations), so each attempt costs roughly 200 ms of
server CPU. Together with the five-attempt limit, this bounds online guessing to about five guesses per
connection. Every failure is logged as a WARNING with the peer address; see
[logging](logging.md#connections-log_connections--on).

Without `tls = on` the password travels in clear text. With TLS the server completes the handshake before it
sends the banner, and gives the client 10 seconds to do so. A client that fails or stalls the handshake (for
example a plaintext client on the TLS port) is logged at LOG level and disconnected. `hstore connect` and
`hstore ping` likewise give up after 10 seconds without a banner.

### Requests and responses

A request is an HQL script: one or more statements, each terminated by `;` (the final `;` is optional). The
server:

1. parses the whole script. On a parse error it replies with a single `error [CODE]: message` and runs nothing;
2. executes statements in order in the connection's session;
3. renders each result in the session's output format (`TABLE` by default, `JSON` after `FORMAT JSON;`);
4. stops at the first failing statement and appends its error. Statements after it do not run;
5. joins the rendered results with one empty line (`\n\n`) and sends them as one message.

Error lines have the form

```
error [<CODE>]: <message>
error [<CODE>, retryable]: <message>
error: <exception>                      (unexpected server fault; logged at ERROR)
```

| Code | Retryable | Raised for |
|---|---|---|
| `RETRYABLE_CONFLICT` | yes | Optimistic concurrency conflict at commit (a write-write conflict that cannot be rebased, or a SERIALIZABLE read-set violation). Re-run the transaction. |
| `RETRYABLE_IO` | yes | Transient I/O failure, for example the embedding service being unreachable. |
| `ABORTED_RESOURCE_LIMIT` | no | Query or transaction page budget, tenant quota, or `max_connections`. |
| `INVALID_SCHEMA` | no | Syntax errors, unknown types or properties, constraint violations, authorisation failures. |
| `CORRUPT_PAGE` | no | A page failed checksum or structural verification. Run `hstore check`. |
| `CORRUPT_LOG` | no | A write-ahead log segment that is not the last one is damaged. Raised at open, so it is normally seen by `hstore` commands rather than clients. |

A failing statement inside an explicit transaction (`BEGIN … COMMIT`) leaves the transaction open. The
client must send `ROLLBACK;` (or close the connection, which aborts it).

### Session state

Each connection owns one `Session`. The following state persists across requests on that connection and is
discarded when it closes:

* the open transaction (`BEGIN`, `COMMIT`, `ROLLBACK`). An open transaction is aborted on disconnect;
* `$variables` bound with `AS $name`;
* the current branch (`USE BRANCH name`, `USE MAIN`);
* the current tenant (`USE TENANT name`, ADMIN only);
* the output format (`FORMAT TABLE`, `FORMAT JSON`).

### Output formats

`TABLE` renders an ASCII grid, a row-count line and, for traced queries, a footer
`generation G, pages read P, cache hits H, T ms`. `JSON` renders one object per result:

```json
{"columns":["d.name"],"rows":[["Atorvastatin"]],"message":"1 row",
 "trace":{"generation":159,"plan":"IndexRange(Drug.class ['statin', 'statin'])","estimatedRows":1,
          "pagesRead":2,"cacheHits":8,"elapsedMicros":3198}}
```

An atom-valued cell becomes `{"id": <atom id>, "label": "<display label>"}`. Message-only results have empty
`columns` and `rows`.

## Example transcript

Captured against a server with one user. `C:` and `S:` mark direction; the terminating `.` lines are shown.

```
S: hstore connection 6 at generation 159; authentication required
S: .
C: SHOW TYPES;
C: .
S: error [AUTHENTICATION_REQUIRED]: authenticate first: AUTHENTICATE <user> PASSWORD '<password>'
S: .
C: AUTHENTICATE admin PASSWORD 'pw'
C: .
S: authenticated as admin
S: .
C: FORMAT JSON; MATCH NODE d:Drug WHERE d.class = 'statin' RETURN d.name;
C: .
S: {"columns":[],"rows":[],"message":"output format json"}
S:
S: {"columns":["d.name"],"rows":[["Atorvastatin"]],"message":"1 row","trace":{…}}
S: .
C: BEGIN; INSERT NODE Drug 'x' {name: 'X'}; INSERT NODE Drug 'x' {name: 'X'}; COMMIT;
C: .
S: {"columns":[],"rows":[],"message":"transaction 160 started at generation 159"}
S:
S: {"columns":["id"],"rows":[[{"label":"Drug:'x'","id":123}]],"message":"1 row"}
S:
S: error [INVALID_SCHEMA]: canonical key 'x' of type 3 already names atom 123
S: .
C: ROLLBACK;
C: .
S: {"columns":[],"rows":[],"message":"rolled back transaction 160"}
S: .
```

## Writing a client

A complete client needs only a socket and the two framing functions. In Python:

```python
import socket

class HStore:
    def __init__(self, host="127.0.0.1", port=7432, user=None, password=None):
        self.stream = socket.create_connection((host, port)).makefile("rwb")
        self.banner = self._receive()
        if self.banner.startswith("error"):
            raise ConnectionError(self.banner)
        if self.banner.endswith("authentication required"):
            quote = lambda s: "'" + s.replace("'", "''") + "'"
            reply = self.execute(f"AUTHENTICATE {quote(user)} PASSWORD {quote(password)}")
            if reply.startswith("error"):
                raise PermissionError(reply)
        self.execute("FORMAT JSON;")

    def execute(self, script):
        for line in script.split("\n"):
            self.stream.write((("." + line) if line.startswith(".") else line).encode() + b"\n")
        self.stream.write(b".\n")
        self.stream.flush()
        return self._receive()

    def _receive(self):
        lines = []
        while True:
            raw = self.stream.readline()
            if not raw:
                raise ConnectionError("server closed the connection")
            line = raw.decode().rstrip("\r\n")
            if line == ".":
                return "\n".join(lines)
            lines.append(line[1:] if line.startswith("..") else line)
```

Guidelines for client authors:

* Send `FORMAT JSON;` once after connecting and parse each result with a JSON parser. Results are separated by
  an empty line, and no rendered JSON result contains a raw newline.
* A result that starts with `error` is a failure. Retry the whole transaction when the code is followed by
  `, retryable`.
* Quote string literals by doubling `'`. Do not interpolate untrusted input into identifiers.
* Keep one connection per concurrent unit of work. Session state (transactions, variables, branch) is
  per-connection, and the server serves each connection on its own virtual thread.
* Reconnect after the socket closes. Idle connections are closed silently when `idle_timeout_seconds` is set.

The Java reference client is
[`RemoteEndpoint.java`](../../server/src/main/java/io/hstore/server/RemoteEndpoint.java), used by
`hstore connect` and `hstore ping`.

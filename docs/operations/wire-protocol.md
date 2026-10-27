# Wire protocol

The HQL wire protocol is a line-oriented UTF-8 text protocol over TCP (default port `7432`). It is
implemented by [`WireProtocol.java`](../../server/src/main/java/io/hstore/server/WireProtocol.java) (framing)
and [`Server.java`](../../server/src/main/java/io/hstore/server/Server.java) (conversation). It has no binary
framing and no length prefixes. With [`tls = on`](configuration.md) the same protocol runs inside TLS on the
same port; otherwise it is plaintext and belongs on a trusted network.

## Framing

A *message* is any UTF-8 text. It is transmitted as its lines, each terminated by `\n`, followed by a line
containing exactly one dot. A line of the message that begins with `.` is sent with one extra leading `.`
(dot-stuffing, as in SMTP's DATA section). Only `\n` ends a line: a `\r` inside a line stays part of it, and a
terminator may be sent as `.\r\n`. The server breaks its responses at `\r` as well as `\n`, so a client that
treats a bare `\r` as a line end still sees every line dot-stuffed.

```
encode(message):
    for line in message.split("\n"):            # every line, including empty ones
        if line starts with ".": line = "." + line
        write(line + "\n")
    write(".\n")

decode(stream):
    lines = []
    for line in stream.split("\n"):            # a "\r" stays in the line
        if line == "." or line == ".\r": return join(lines, "\n")
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
            C->>S: "SASL SCRAM-SHA-256 <base64 client-first-message>"
            S-->>C: "sasl <base64 server-first-message>"
            C->>S: "SASL <base64 client-final-message>"
            S-->>C: "authenticated as admin" and "sasl <base64 server-final-message>", or "error [AUTHENTICATION_FAILED]: authentication failed"
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

Clients sign in with SCRAM-SHA-256 ([RFC 5802](https://www.rfc-editor.org/rfc/rfc5802),
[RFC 7677](https://www.rfc-editor.org/rfc/rfc7677)), the same mechanism PostgreSQL uses. The password never
crosses the connection: the client proves that it knows it, and the server proves in turn that it holds the
user's verifier. A client that follows the rules below therefore detects a server that only pretends to be the
database, unless that server has a copy of the user's verifier. The exchange takes two requests. Each SCRAM message travels base64-encoded on one line:

| Step | Client sends | Server answers |
|---|---|---|
| 1 | `SASL <mechanism> <base64 client-first-message>` | `sasl <base64 server-first-message>` |
| 2 | `SASL <base64 client-final-message>` | `authenticated as <user>` and, on a second line, `sasl <base64 server-final-message>` |

`<mechanism>` is `SCRAM-SHA-256`, or `SCRAM-SHA-256-PLUS` on a TLS connection. With `-PLUS` the client uses
the `tls-server-end-point` channel binding ([RFC 5929](https://www.rfc-editor.org/rfc/rfc5929)): it hashes the
server's certificate, with SHA-256 unless the certificate is signed with SHA-384 or SHA-512, and mixes the hash
into its proof. A proof relayed through a man in the middle, which presents a different certificate, then fails.
The server rejects `-PLUS` without TLS, and on a TLS connection it rejects a client that announces channel binding
support (gs2 flag `y`) without using it, since that means someone stripped it.

A client that has a password to send must not trust the connection until the exchange has finished, and
`hstore connect` disconnects when any of these fails:

- the banner ends with `; authentication required`. A server that does not ask for a password cannot prove that
  it knows it, so a client with credentials refuses to go on rather than send statements to it;
- the answer to step 1 is `sasl …` (or an `error`), and its nonce extends the client's nonce;
- the iteration count is between 4,096 and 10,000,000;
- the answer to step 2 is exactly `authenticated as <the user it signed in as>` followed by `sasl v=<server
  signature>`, and the signature is the one the client computes from the password.

When the last check fails the server already considers the session signed in, so the client must close the
connection. Passwords go through SASLprep ([RFC 4013](https://www.rfc-editor.org/rfc/rfc4013)) and are then hashed
as UTF-8, as in PostgreSQL: an all-ASCII password is used as it is, and a password that SASLprep rejects is used
unchanged. Authorization identities and SCRAM extensions are not supported, and the iteration count is 600,000
for passwords set by this version, so a client spends about half a second deriving the key.

A user that does not exist gets a well-formed `server-first-message` with a salt derived from the user name and a
secret kept in the database, the same every time, and fails only at the second step, exactly like a wrong
password. A user whose password was stored by HStore 0.1 is answered the same way until it signs in once through
Studio or gets a new password ([security](../database/security.md#users)); the server log
says which case it was.

Possible replies:

| Reply | Meaning |
|---|---|
| `sasl …` | Continue with the next step. |
| `authenticated as <user>` / `sasl …` | The session now runs as that user's principal (tenant and role). |
| `error [AUTHENTICATION_FAILED]: authentication failed` | Unknown user or wrong password, answered after `authentication_failure_delay_ms`. |
| `error [AUTHENTICATION_FAILED]: malformed SCRAM message: …`, `unsupported SASL mechanism …` | The client sent something the server cannot use. |
| `error [AUTHENTICATION_FAILED]: this server no longer accepts passwords in clear text; upgrade your HStore client to version 0.2.0 or later, which signs in with SCRAM-SHA-256` | The client sent `AUTHENTICATE <user> PASSWORD '…'`, which older clients used. The password is not checked. |
| `error [AUTHENTICATION_REQUIRED]: authenticate first with SASL SCRAM-SHA-256; see the wire protocol documentation` | Any other request. |
| `error [ABORTED_RESOURCE_LIMIT]: too many failed sign-in attempts; …` | The throttle described below. |

An `AUTHENTICATE` statement is refused with the same upgrade message on an authenticated connection too; send a
new SASL exchange to switch users. `hstore connect` turns an `AUTHENTICATE` typed in its shell into that
exchange when it is the only statement it sends; any other script that contains `AUTHENTICATE` is refused in the
shell without sending anything, so a mistyped sign-in cannot put the password on the wire. A server's `error`
reply to a SASL message leaves the connection as it was; when the server's proof is missing or wrong the shell
disconnects. `CREATE USER` and `ALTER USER … PASSWORD` still carry the password in the statement; use TLS for them,
or pass a SCRAM verifier made on the client (`hstore verifier`, the format is
`SCRAM-SHA-256$<iterations>:<salt>$<StoredKey>:<ServerKey>` as in PostgreSQL) in place of the password.

Every rejected request before sign-in counts as a failed attempt. After `MAX_AUTHENTICATION_ATTEMPTS` = 5 failures, the
server logs a WARNING and closes the connection without replying further. Clients must reconnect to try
again. `AUTHENTICATION_FAILED` and `AUTHENTICATION_REQUIRED` occur only in this handshake. They are protocol
codes, not `HStoreException` codes.

The server checks before every request whether the connection still needs to authenticate. With `auto`, a
connection opened before the first user existed runs as `Principal.SYSTEM` only until a user is created; its next
request gets `AUTHENTICATION_REQUIRED`. An authenticated session may send `AUTHENTICATE` again to switch users,
outside a transaction only. That clears its variables and returns it to main. A session that fails 5 such attempts
refuses further `AUTHENTICATE` statements.

A failed attempt is answered after `authentication_failure_delay_ms` (default 500). Every sign-in path shares one
throttle: after 10 failures for a user name from one address, or 20 from one address over all names, within 15
minutes, further attempts from that address get `error [ABORTED_RESOURCE_LIMIT]: too many failed sign-in
attempts; try again in N seconds` without the password being checked. Reconnecting does not reset it, and other
addresses are not affected. Every failure is logged as a WARNING with the peer address; see
[logging](logging.md#connections-log_connections--on).

While the database has no users and `authentication = auto`, only clients on the server's own host (loopback
addresses) are served. A client on another host receives

```
error [AUTHENTICATION_REQUIRED]: this database has no users, so it only accepts connections from its own host; create a user with CREATE USER, or set authentication = off to let other hosts in without authentication
```

instead of the banner, and is disconnected. The same answer ends a connection from another host whenever the last
user is dropped.

Until it authenticates, a connection may send at most 64 KiB per request and must authenticate within
`authentication_timeout_seconds` (default 30), or it is closed. At most 16 connections from one address may be waiting to authenticate at
once; the next gets `error [ABORTED_RESOURCE_LIMIT]: too many unauthenticated connections from this address`. After that, requests may be up to
`max_message_mb` (default 64). A request over the limit gets `error [ABORTED_RESOURCE_LIMIT]: message exceeds N
bytes` and the connection is closed. A connection whose first request starts like an HTTP request line is closed
without a reply, so a web page cannot use a browser to post HQL to the port.

If a user is dropped or its password changes, the next statement on each of its open connections fails with
`user NAME was dropped, or its password or role changed; authenticate again`, and so does every statement after it until the
connection signs in again with a SASL exchange. An exchange that started before the change fails at its last step,
since the server only accepts it while the user's record is the one it took the verifier from.

Signing in never sends the password, with or without TLS. Without `tls = on`, everything else travels in clear
text, including the passwords in `CREATE USER` and `ALTER USER … PASSWORD`. With TLS the server completes the handshake before it
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
error [INTERNAL]: the statement failed; the server log has the details   (unexpected fault, logged at ERROR)
```

| Code | Retryable | Raised for |
|---|---|---|
| `RETRYABLE_CONFLICT` | yes | Optimistic concurrency conflict at commit (a write-write conflict that cannot be rebased, or a SERIALIZABLE read-set violation). Re-run the transaction. |
| `RETRYABLE_IO` | yes | Transient I/O failure, for example the embedding service being unreachable. |
| `ABORTED_RESOURCE_LIMIT` | no | Query or transaction page budget, tenant quota, `max_connections`, request size, or the sign-in throttle. |
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
S: error [AUTHENTICATION_REQUIRED]: authenticate first with SASL SCRAM-SHA-256; see the wire protocol documentation
S: .
C: SASL SCRAM-SHA-256 biwsbj1hZG1pbixyPWVwSmszWWlmWFVzSHVOaG51a2llMlExSw==
C: .
S: sasl cj1lcEprM1lpZlhVc0h1TmhudWtpZTJRMUtEZ2xDdGNNOFpLUTNpc3dtaGh1M2Fkdysscz1wZklhOHhpVVBFQUFlUnUreW9tMElBPT0saT02MDAwMDA=
S: .
C: SASL Yz1iaXdzLHI9ZXBKazNZaWZYVXNIdU5obnVraWUyUTFLRGdsQ3RjTThaS1EzaXN3bWhodTNhZHcrLHA9STNPN1V0alNHNlFFSXRJYURYalFzY1pXd25RUTNLMFphMm9VMUdJbk1KVT0=
C: .
S: authenticated as admin
S: sasl dj1TbG1KOXMrVmVpZWVjMGprUjg0ZUU3ZHAxclVlbmNIVFVjUTJTb1l5eUpJPQ==
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

A complete client needs only a socket, the two framing functions and SCRAM, all from the Python standard
library. Pass `tls_ca` (a CA or the server's own certificate) when the server runs with `tls = on`; the client
then uses channel binding. The certificate hash below is SHA-256, which is right for certificates signed with
SHA-256 (the usual case).

```python
import base64
import hashlib
import hmac
import os
import socket
import ssl
import stringprep
import unicodedata


def b64(text):
    return base64.b64encode(text.encode()).decode()


def unb64(text):
    return base64.b64decode(text).decode()


def saslprep(password):
    if password.isascii():
        return password
    mapped = "".join(" " if stringprep.in_table_c12(c) else "" if stringprep.in_table_b1(c) else c for c in password)
    if not mapped or any(map(stringprep.in_table_a1, mapped)):
        return password
    prepared = unicodedata.normalize("NFKC", mapped)
    prohibited = (stringprep.in_table_a1, stringprep.in_table_c12, stringprep.in_table_c21_c22, stringprep.in_table_c3,
                  stringprep.in_table_c4, stringprep.in_table_c5, stringprep.in_table_c6, stringprep.in_table_c7,
                  stringprep.in_table_c8, stringprep.in_table_c9)
    if any(test(c) for c in prepared for test in prohibited):
        return password
    if any(map(stringprep.in_table_d1, prepared)) and (any(map(stringprep.in_table_d2, prepared))
                                                       or not stringprep.in_table_d1(prepared[0])
                                                       or not stringprep.in_table_d1(prepared[-1])):
        return password
    return prepared


class HStore:
    def __init__(self, host="127.0.0.1", port=7432, user=None, password=None, tls_ca=None):
        self.sock = socket.create_connection((host, port))
        if tls_ca:
            context = ssl.create_default_context(cafile=tls_ca)
            self.sock = context.wrap_socket(self.sock, server_hostname=host)
        self.stream = self.sock.makefile("rwb")
        self.banner = self._receive()
        if self.banner.startswith("error"):
            raise ConnectionError(self.banner)
        if user is not None:
            if not self.banner.endswith("authentication required"):
                self.sock.close()
                raise PermissionError("the server did not ask for a password, so it cannot prove who it is")
            try:
                self._sign_in(user, password)
            except Exception:
                self.sock.close()
                raise
        self.execute("FORMAT JSON;")

    def _sign_in(self, user, password):
        binding = None
        if isinstance(self.sock, ssl.SSLSocket):
            binding = hashlib.sha256(self.sock.getpeercert(binary_form=True)).digest()
        header = "p=tls-server-end-point,," if binding else "n,,"
        nonce = base64.b64encode(os.urandom(18)).decode()
        first_bare = "n=" + user.replace("=", "=3D").replace(",", "=2C") + ",r=" + nonce
        mechanism = "SCRAM-SHA-256-PLUS" if binding else "SCRAM-SHA-256"
        reply = self.execute(f"SASL {mechanism} {b64(header + first_bare)}")
        if not reply.startswith("sasl "):
            raise PermissionError(reply)
        server_first = unb64(reply[5:])
        fields = dict(part.split("=", 1) for part in server_first.split(","))
        if not fields["r"].startswith(nonce) or not 4096 <= int(fields["i"]) <= 10_000_000:
            raise PermissionError("unexpected server-first-message")
        salted = hashlib.pbkdf2_hmac("sha256", saslprep(password).encode(), base64.b64decode(fields["s"]), int(fields["i"]))
        client_key = hmac.digest(salted, b"Client Key", "sha256")
        without_proof = "c=" + base64.b64encode(header.encode() + (binding or b"")).decode() + ",r=" + fields["r"]
        auth_message = f"{first_bare},{server_first},{without_proof}".encode()
        signature = hmac.digest(hashlib.sha256(client_key).digest(), auth_message, "sha256")
        proof = base64.b64encode(bytes(a ^ b for a, b in zip(client_key, signature))).decode()
        reply = self.execute(f"SASL {b64(without_proof + ',p=' + proof)}")
        if reply.startswith("error"):
            raise PermissionError(reply)
        status, _, final = reply.partition("\n")
        server_key = hmac.digest(salted, b"Server Key", "sha256")
        expected = "v=" + base64.b64encode(hmac.digest(server_key, auth_message, "sha256")).decode()
        if (status != "authenticated as " + user or not final.startswith("sasl ")
                or not hmac.compare_digest(unb64(final[5:]).encode(), expected.encode())):
            raise PermissionError("the server could not prove that it knows the password")

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

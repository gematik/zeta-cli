# `zeta serve` — warm-session HTTP daemon (experimental)

> **⚠️ Experimental.** `zeta serve` and its HTTP API are under active development. Endpoint
> paths, headers, request/response shapes, and options **may change without notice** between
> releases. Don't build anything load-bearing on it yet.

`zeta serve` runs a small local HTTP daemon that holds **warm** Zeta sessions for one TI
environment. At startup it logs into the known Zeta-protected endpoints (the service-discovery
catalog's VSDM instances plus the PoPP service) so later requests skip
discover/register/authenticate — and, with `--auth-method connector`, the one-time Konnektor
SDS load and card enumeration. A background job proactively re-authenticates each session ~5s
before its access token expires, so even an idle daemon answers the next request without paying
a token round-trip.

The point is **latency for scripted, back-to-back reads**: a shell loop that reads many VSDM
bundles pays the auth/ASL setup once (at daemon start) instead of once per invocation. With
`--cache-db` it also stops re-transferring bundles that have not changed — see
[Caching](#caching).

| Endpoint | Purpose |
| --- | --- |
| `GET /api/health` | Liveness, warm sessions, connector state, cache counters. |
| `GET /api/zeta/status` | The profile's cached registration and token state. |
| `GET /api/vsdm/read` | Read a VSD bundle with a PoPP token you supply. |
| `GET /api/vsdm/popp-then-read` | Mint a PoPP token and read in one call. |
| `GET /api/popp/token` | Mint a PoPP token and nothing else. |

## Why a unix socket by default

`zeta serve` listens on a **unix domain socket** unless you pass `--port`. That default is
deliberate:

- **Not on the network.** A unix socket is a filesystem object, reachable only by processes on
  the same host. There is no port to bind, no interface to accidentally expose, nothing for a
  firewall to get wrong. A TCP listener — even on `127.0.0.1` — is one misconfiguration
  (`--host 0.0.0.0`, a port-forward, a container `-p`) away from being reachable off-box.
- **Filesystem permissions are the access control.** The socket is created `0600` (owner
  read/write only), so only your user can talk to the daemon. The API is **unauthenticated** —
  anyone who can open the socket can mint PoPP tokens and read VSD data — so keeping that
  surface to a single local user matters.
- **No transport crypto needed.** Loopback IPC doesn't need TLS; there's no certificate to
  provision, and no plaintext-over-the-wire concern because there is no wire.
- **Self-cleaning, well-located.** The default path is `$XDG_RUNTIME_DIR/zeta/zeta.sock` where
  a runtime dir exists (Linux — the spec's home for short-lived sockets, wiped on logout),
  falling back to `~/.cache/telematik/zeta/zeta.sock`.

Use `--port` only when a socket genuinely won't do (e.g. a client that can't dial `AF_UNIX`, or
a container boundary). If you do, it binds `127.0.0.1` (loopback) by default; moving it past
loopback with `--host` logs a warning, and you own the exposure.

## Starting the daemon

```sh
# Default: unix socket, dev environment, connector auth + connector PoPP card
zeta serve --auth-method connector --auth-connector-telematik-id 5-2-1234567

# One-liner status appears once warm-up finishes, e.g.:
#   zeta serve ready (3/4 warm, 1 lazy) — listening on /run/user/1000/zeta/zeta.sock (env dev, profile default) — Ctrl-C to stop
```

Common variations:

```sh
# A specific socket path
zeta serve --socket /tmp/zeta.sock --auth-method connector

# TCP instead (loopback) — only if you can't use a socket
zeta serve --port 8787 --auth-method p12 --auth-p12-file smcb.p12

# Serve the REF environment
zeta serve --env ref --auth-method connector

# PoPP minting from a local PC/SC reader instead of the Konnektor
zeta serve --popp-card standard --popp-reader 'Cherry' --auth-method p12 --auth-p12-file smcb.p12
```

Key options (see `zeta serve --help` for the full list, including the shared auth options):

| Option | Purpose |
| --- | --- |
| `--socket PATH` | Unix socket to listen on (default; mutually exclusive with `--port`). |
| `--port PORT` | Listen on TCP instead. Binds `127.0.0.1` unless `--host` says otherwise. |
| `--host ADDR` | Bind address for `--port` (default `127.0.0.1`). |
| `--env {dev,ref,test,prod}` | TI environment to warm and serve (default `dev`). |
| `--popp-card {connector,standard}` | Card transport for `/api/vsdm/popp-then-read` (default `connector`; `connector` requires `--auth-method connector`). |
| `--popp-connection {contact,contactless}` | Card connection for `--popp-card connector` (default `contact`). |
| `--popp-reader NAME` / `--popp-wait SECONDS` | PC/SC reader selection for `--popp-card standard`. Set `--popp-reader` explicitly when the host has several reader slots — the daemon otherwise takes the first slot that answers on each mint. `zeta popp readers` lists them. |
| `--cache-db FILE` | Shared SQLite cache file; VSDM reads keep bundles in it so unchanged records are revalidated instead of re-transferred. Off when unset. See [Caching](#caching). |
| `--cache-max-entries N` | Entries to keep per cached kind (default `10000`); the least recently revalidated go first. |
| `--cache-max-age-days DAYS` | Drop entries nobody revalidated in this long (default `180`). |

The daemon serves **one** environment and **one** auth identity — start a second daemon (on a
second socket) for a different env or SMC-B.

## Endpoints

All examples use the default socket. Replace the `--unix-socket` argument + `http://localhost`
with `http://127.0.0.1:<port>` if you started the daemon with `--port`.

```sh
SOCK=~/.cache/telematik/zeta/zeta.sock      # or $XDG_RUNTIME_DIR/zeta/zeta.sock on Linux
```

### `GET /api/health` — liveness + runtime state

Always `200`. Reports the open warm sessions and the connector-session state (never
credentials).

```sh
curl -s --unix-socket "$SOCK" http://localhost/api/health | jq
```

```json
{
  "status": "ok",
  "env": "dev",
  "poppCard": "connector",
  "sessions": [
    { "resource": "https://vsdm-dev.tk.de/", "scopes": ["vsdservice"] },
    { "resource": "https://popp.dev.poppservice.de/", "scopes": ["popp"] }
  ],
  "connector": { "configured": true, "connected": true, "url": "https://kon:443",
                 "mandantId": "m1", "workplaceId": "w1", "clientSystemId": "c1" },
  "cache": { "entries": 42, "oldestRevalidation": 1788243600, "fileBytes": 262144 }
}
```

`cache` is absent when the daemon runs without `--cache-db`.

### `GET /api/zeta/status` — detailed profile status

The full cached SDK state for every resource in the profile (same content as `zeta status -o
json`).

```sh
curl -s --unix-socket "$SOCK" http://localhost/api/zeta/status | jq
```

### `GET /api/vsdm/read` — read a VSD bundle (you supply the PoPP token)

A transparent proxy in front of the VSDM read. You pass a **pre-minted** PoPP token in the
`PoPP` request header; the daemon derives the insurer's endpoint from the token, reads over the
warm session, and returns the upstream response **verbatim**. Your query string and request
headers (`Accept`, `If-None-Match`, …) are forwarded as-is.

```sh
export POPP_TOKEN='eyJraWQi...'    # a dev PoPP token

curl -s --unix-socket "$SOCK" \
  -H "PoPP: $POPP_TOKEN" \
  -H "Accept: application/fhir+xml" \
  -H 'If-None-Match: "0000000000000000000000000000000000000000000000000000000000000000"' \
  "http://localhost/api/vsdm/read?profileVersion=1.1" -D -
```

`If-None-Match` is **required** by the VSDM service — omit it and you get the upstream `428`
(`VSDSERVICE_MISSING_PATIENT_RECORD_VERSION`). Use the all-zero etag for "no known version", or
a real etag from a prior read to get a `304`. With `--cache-db` the daemon supplies the header
for you when you leave it out — see [Caching](#caching).

The response carries `middleware-popp` (the token used), `middleware-insurer-id` and
`middleware-insurant-id` (the IKNR and KVNR from that token, so you need not decode it).

### `GET /api/vsdm/popp-then-read` — mint a PoPP token *and* read (one call)

The daemon mints a fresh PoPP token via the startup-configured card transport (`--popp-card`),
then does the same read. No `PoPP` header needed — the minted token is returned in the
`middleware-popp` response header.

```sh
curl -s --unix-socket "$SOCK" \
  -H "Accept: application/fhir+xml" \
  -H 'If-None-Match: "0000000000000000000000000000000000000000000000000000000000000000"' \
  "http://localhost/api/vsdm/popp-then-read?profileVersion=1.1" -D -
```

With more than one eGK visible to the Konnektor, pick one with a `middleware-egk` header (its
value is the eGK card handle); without it the daemon auto-selects the single visible eGK, or
returns `409` listing the handles:

```sh
curl -s --unix-socket "$SOCK" \
  -H 'middleware-egk: EGK-235' \
  -H "Accept: application/fhir+xml" \
  -H 'If-None-Match: "0000000000000000000000000000000000000000000000000000000000000000"' \
  "http://localhost/api/vsdm/popp-then-read?profileVersion=1.1" -D -
```

### `GET /api/popp/token` — mint a PoPP token, nothing else

The counterpart to `popp-then-read` when you want one proof of presence and then several reads:
minting touches the card and dominates the cost of a read, so tying the two together hides what
the bundle cache saves. Needs `--popp-card` just like `popp-then-read`, takes the same optional
`middleware-egk` header, and returns the same errors.

```sh
curl -s --unix-socket "$SOCK" http://localhost/api/popp/token -D -
```

```json
{
  "popp": "eyJraWQi…",
  "actorId": "5-2-KHAUS-…",
  "insurerId": "101575519",
  "insurantId": "X110411675",
  "patientProofTime": 1788243600,
  "iat": 1788243600
}
```

The token is also in `middleware-popp`, and the two identifiers in `middleware-insurer-id` /
`middleware-insurant-id`.

## Caching

Without `--cache-db` nothing is cached and the daemon behaves exactly as it always has, `428`
included. With it, the daemon becomes a shared cache in front of the VSDM service.

**What is cached.** One bundle per patient record: the key is the environment, the VSDM endpoint,
the insurer (IKNR) and insurant (KVNR) from the PoPP token, the `profileVersion`, and the media
type. The value is the service's `ETag` plus the body. Who read the record is deliberately *not*
part of the key — the bundle version does not depend on the reader, and every hit still costs a
live upstream call carrying the caller's own PoPP token. Do not share one cache file across
tenants.

**Nothing expires.** There is no TTL: every request is revalidated conditionally, so the service
always decides whether the copy still holds. `--cache-max-entries` and `--cache-max-age-days` are
hygiene, not freshness.

**What the client sees.** What you asked for decides what you get:

| Your `If-None-Match` | Sent upstream | On upstream `304` | `middleware-cache` |
| --- | --- | --- | --- |
| absent, cache holds this record | the cached etag | a synthesized `200` with the cached body | `hit` |
| absent, nothing cached | the all-zero etag | — | `miss` |
| the same etag the cache holds | yours | the `304`, verbatim | `revalidated` |
| a different etag (all-zero included) | yours | the `304`, verbatim | `bypass` |
| any, plus `Cache-Control: no-cache` | the all-zero etag | — | `bypass` |

A client that asked no conditional question gets a full `200`, because a `304` would refer to a
version it never named. A client that did ask gets its own answer, untouched — so the all-zero
etag remains the guaranteed way to force a full read. `middleware-upstream-status` always carries
what the service really answered, which is the only visible difference on a hit.

**The data.** `--cache-db` names one cache file that every cached kind shares — VSDM bundles are
the first, others can be added without a new flag or a second file. Today it therefore holds
Versichertenstammdaten and the PoPP tokens they were read with, **unencrypted**, mode `0600`, at a
path you choose. Deleting it is always safe. `zeta vsdm cache
stats --cache-db FILE` reports what it holds, `zeta vsdm cache purge --cache-db FILE
[--insurer IKNR] [--patient KVNR] [--tokens] [--all]` clears it selectively. `GET /api/health`
shows the counters.

## Header conventions

The daemon uses a `middleware-*` namespace for its own metadata, distinct from the standard
headers passed through to/from the VSDM service:

- **Request** — `middleware-*` headers are **daemon control inputs**, consumed and *not*
  forwarded upstream. Currently: `middleware-egk` (eGK card-handle selection for
  `popp-then-read`).
- **Response** — `middleware-*` headers are daemon metadata added on top of the verbatim
  upstream response:
  - `middleware-popp` — the PoPP token used or minted for the read.
  - `middleware-insurer-id` / `middleware-insurant-id` — the IKNR and KVNR from that token, so a
    client need not decode it.
  - `middleware-cache` — `hit`, `revalidated`, `miss`, `bypass`, or `off`. See [Caching](#caching).
  - `middleware-upstream-status` — the status the VSDM service actually returned. Equal to the
    response status except on a cache hit, where you get `200` and the service said `304`.
  - `middleware-error-source` — on any error (status ≥ 400), `middleware` (the daemon failed:
    config, card, PoPP, routing) or `upstream` (a forwarded VSDM-service error). Lets you tell
    *which layer* a `4xx`/`5xx` came from, since upstream statuses pass through unchanged.

## Errors

Every **daemon-originated** error is JSON — `{ "error": "…" }` — and carries
`middleware-error-source: middleware`. A **forwarded upstream** error keeps the VSDM service's
verbatim status/body and carries `middleware-error-source: upstream`.

| Status | Meaning |
| --- | --- |
| `400` | Bad/absent PoPP token, unparseable, no `iss`, or an eGK handle the Konnektor rejects. |
| `404` | The token's insurer isn't routed in this environment's catalog (or unknown endpoint). |
| `409` | Env mismatch (a token for a different TI env than the daemon), or no / ambiguous eGK for minting. |
| `501` | Minting requested but not enabled (start with `--popp-card`). |
| `502` | PoPP (mint) failure, or the VSD read failed at the daemon. |
| upstream | Any other status is the VSDM service's, forwarded verbatim (e.g. `428`, `304`). |

## Notes

- **Transient `406 application/cbor`.** After an idle stretch the server's ASL session can lapse
  and the first read may briefly get a `406`; the daemon retries transparently, so callers
  normally don't see it. A persistent `406` is surfaced honestly.
- **Environment ↔ token.** `/api/vsdm/read` rejects a PoPP token minted for a different TI
  environment than the daemon serves (`409`). `popp-then-read` always mints for the daemon's own
  environment.
- **Shutdown.** `Ctrl-C` (SIGINT/SIGTERM) stops the server, cancels the background refresh, and
  removes the socket.

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
bundles pays the auth/ASL setup once (at daemon start) instead of once per invocation.

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
| `--popp-connection {contact,contactless}` | Connector card connection (default `contact`). |
| `--popp-reader NAME` / `--popp-wait SECONDS` | PC/SC reader selection for `--popp-card standard`. |

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
                 "mandantId": "m1", "workplaceId": "w1", "clientSystemId": "c1" }
}
```

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
a real etag from a prior read to get a `304`. The response carries a `middleware-popp` header
echoing the token used.

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

## Header conventions

The daemon uses a `middleware-*` namespace for its own metadata, distinct from the standard
headers passed through to/from the VSDM service:

- **Request** — `middleware-*` headers are **daemon control inputs**, consumed and *not*
  forwarded upstream. Currently: `middleware-egk` (eGK card-handle selection for
  `popp-then-read`).
- **Response** — `middleware-*` headers are daemon metadata added on top of the verbatim
  upstream response:
  - `middleware-popp` — the PoPP token used or minted for the read.
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

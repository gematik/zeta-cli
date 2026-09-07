<img align="right" width="250" height="47" src="images/gematik-logo.png"/> <br/>    
 
# Release Notes ZETA CLI
## Release 0.14.0
### changes
- Add `--cache-db FILE` to `zeta vsdm get` and `zeta serve` — a shared SQLite cache that turns the mandatory `If-None-Match` into a real conditional request: the ETag the service last gave for a patient record is sent back, and an unchanged record answers `304` and is served from the cache instead of being re-transferred. Nothing expires — every read is revalidated against the service, so the cache can never hand out a stale record. Entries are keyed by the reading SMC-B (`actorId`), endpoint, insurer (IKNR), insurant (KVNR), `profileVersion` and media type, so several identities can share one file and be counted and purged apart — attribution, **not** isolation: anyone who can read the file reads every bundle in it. Off unless the flag is given, and the file stores Versichertenstammdaten and PoPP tokens **unencrypted**, mode `0600` (its `-wal`/`-shm` sidecars included) — `--cache-max-entries` (10000) and `--cache-max-age-days` (180) bound it, and `zeta serve` now applies those bounds hourly rather than only at startup
- `Cache-Control: no-store` now keeps the record off the disk as well as out of the answer, and `zeta vsdm get --no-cache` does the same from the command line: read in full, keep nothing
- The cache file carries its schema version in the SQLite header and is checked on open: a file written by a schema this build does not speak is discarded and recreated rather than silently failing every write. A file that merely cannot be opened right now (locked, no permission) is no longer mistaken for a corrupt one and deleted
- Add `zeta vsdm cache stats` and `zeta vsdm cache purge` — inspect the cache, or clear it by reader (`--actor`), insurer, insurant, drop only the stored PoPP tokens (`--tokens`, which now honours those same filters), or wipe it (`--all`, which asks first unless `--force`). `stats` on a path with no cache says so instead of creating an empty one
- The profile storage database and the `zeta stress` identity database are now created `0600` together with their WAL sidecars, and stamped with an application id so a file belonging to something else is refused rather than written into. `zeta stress popp export` writes its `.jwt` files `0600` too
- Add [docs/storage.md](docs/storage.md) — every persistent thing the CLI writes, what is in it, how sensitive it is, and how to clear it
- Add `GET /api/popp/token` to `zeta serve` — mint a PoPP token without reading anything, for callers that want one proof of presence and then several reads
- `zeta serve` responses now carry `middleware-insurer-id` and `middleware-insurant-id` (the IKNR and KVNR from the PoPP token, so a client need not decode it), plus `middleware-cache` (`hit` / `revalidated` / `miss` / `bypass` / `off`) and `middleware-upstream-status` (what the VSDM service actually answered — it differs from the response status on a cache hit)
- In `zeta serve` the cache only substitutes an answer for a client that asked no conditional question of its own: a client that sends `If-None-Match` gets its own question asked upstream and its own answer back, and the all-zero etag remains the guaranteed way to force a full read. Without `--cache-db` the daemon behaves exactly as before, upstream `428` included
- `zeta vsdm get` no longer treats `304 Not Modified` as a failure: it is the successful answer to a conditional request, not an error
- Fix `--asl-prod` being silently overridden by `--env` on `zeta http`, `zeta ws` and `zeta serve` — the environment option reset the flag to non-prod, so a prod request kept sending the `zeta-asl-nonpu-tracing` header and was rejected with ASL error 103
- Bump `zeta-sdk` dependency to 1.3.0

## Release 0.12.0
### changes
- `zeta popp standard` can now read a **contactless** eGK: `--connection contactless --can <digits>` opens a PACE channel with the card access number printed on the card, and every scenario APDU runs inside secure messaging. Readers that advertise PC/SC `FEATURE_EXECUTE_PACE` (class-3 "comfort" readers) do it in firmware — the CAN then never leaves the reader — otherwise the CLI runs PACE-ECDH-GM-AES-CBC-CMAC-128 itself
- Add `zeta popp readers` — list the local PC/SC reader slots, whether each holds a card (probed by connecting, not by the unreliable `isCardPresent`), the card's ATR, and whether the reader runs PACE in firmware or leaves it to the CLI
- Fix `zeta popp standard` failing with `SCARD_E_NO_SMARTCARD` while a card was in the reader: a dual-interface reader publishes one slot per interface, and some drivers report a phantom card on the idle one. Slot selection now trusts a successful connection instead of the driver's card-present flag, and warns when a second slot also holds a card (`--reader` pins one)
- One card configuration and one flow entry point now back every PoPP path: `zeta popp connector|kartos|standard` and `zeta serve` describe the card the same way, and `--reader` / `--wait` / `--connection` are declared once

## Release 0.11.1
### changes
- Add `--env {dev|ref|test|prod}` to `zeta http` and `zeta ws` (like `zeta serve`) to select the ASL trust-anchor environment — `--env prod` uses the production TSL, so requests to a prod resource no longer download the ref trust list by default
- Add `--env {dev|ref|test|prod}` to `zeta popp connector`/`standard`/`kartos` to pick the popp service by environment (e.g. `--env prod` → `wss://popp.prod.poppservice.de/…`) instead of spelling out `--service-url`; `--service-url` still overrides it
- `zeta serve` now binds its socket and reports `ready` immediately, warming sessions in the background instead of blocking startup on every endpoint login — the daemon accepts requests at once, and `GET /api/health` reports warm-up progress (`warmup: { complete, warmed, total }`)
- Forward `--ca-cert` to the SDK's own discovery/registration/auth/ASL calls (not just the `zeta http`/`ws` client), so a private/internal or staging CA is trusted end-to-end

## Release 0.11.0
### changes
- Add `zeta serve` (**experimental**) — a warm-session local HTTP daemon for one TI environment. Listens on a unix domain socket by default (or `--port` for TCP), keeps Zeta sessions warm and proactively refreshes them before expiry, and exposes `GET /api/health`, `GET /api/zeta/status`, `GET /api/vsdm/read` (read a VSD bundle from a supplied PoPP token) and `GET /api/vsdm/popp-then-read` (mint a PoPP token via the Konnektor or a PC/SC reader, then read). The API surface may change without notice — see [docs/serve.md](docs/serve.md)
- Cache the TI service-discovery catalog in the profile's storage database, honouring the response's `Cache-Control: max-age` — `zeta vsdm get` reuses a fresh catalog without a network call, and when service-discovery is unavailable it warns and falls back to the cached copy instead of failing
- Surface the Konnektor's SOAP `faultstring` in error messages (e.g. after a failed `SecureSendAPDU`) instead of a generic "reported a SOAP fault"
- When the ASL session expires on the server, the SDK returns a wrong `406 application/cbor` error even though the request was fine. `zeta vsdm get` and `zeta serve` now just try the read again — which sets up a new ASL session — so you get the real response instead of the error

## Release 0.10.0
### changes
- Add `zeta popp standard` — retrieve a PoPP token from a physical eGK the client reads directly, via a contact PC/SC card reader (`javax.smartcardio`); contactless/PACE and remote terminals not yet supported
- Expected command failures now print just their message (no stack trace); the full trace stays available at `-vv`
- Add `-i` / `--include` to `zeta vsdm get` — print the response as an HTTP message (status line, headers, a blank line, then the body) so a pipeline can read `ETag` and the VSDM `PZ` (Prüfziffer) alongside the bundle
- Log the decrypted inner ASL response at `-vv` for `zeta vsdm get` and `zeta http` — the wire log previously showed the inner ASL request but only the encrypted (undecryptable) response envelope

## Release 0.9.3
### changes
- `zeta vsdm get` now fails (non-zero exit) with the server's reason on a non-2xx response, instead of silently printing the body and exiting 0
- Render the VSDM response by its `content-type`: pretty-print JSON, show text bodies as text, and describe non-text bodies (e.g. `application/cbor`) instead of dumping mangled binary
- Add `-H` / `--header` to `zeta vsdm get` to override or add inner-request headers (replaces the built-in `Accept` / `If-None-Match` / `PoPP` defaults by name)
- Pretty-print `application/fhir+xml` VSDM responses (indented, syntax-highlighted on a TTY)

## Release 0.9.2
### changes
- Fix `.kon` `expectedHost` TLS verification: the option delegated to a deny-all JDK verifier and never matched
- Verify the hostname against subjectAltNames, falling back to the subject CN for SAN-less self-signed Konnektor certs (e.g. `CN=server`)

## Release 0.9.1
### changes
- Bump `zeta-sdk` dependency to 1.2.5
- Add `--profile-version` option to `zeta vsdm get` (defaults to 1.1; the profile version was previously hard-coded to 1.0)

## Release 0.9.0
### changes
- Migrate CLI state storage to SQLite
- Add `zeta vsdm get` — read a patient's VSDM bundle from a PoPP token
- Add new stress storm scenarios and bound the live client set
- Track and report SDK-state expiry in stress runs

## Release 0.8.3
### changes
- Validate each SDK step's success via status checks in stress runs
- Document the `zeta vsdm` command and add the stress guide

## Release 0.8.2
### changes
- Release-flow and packaging retag (no functional changes over 0.8.1)

## Release 0.8.1
### changes
- Update the release flow (`just release` recipe)

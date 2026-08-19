<img align="right" width="250" height="47" src="images/gematik-logo.png"/> <br/>    
 
# Release Notes ZETA CLI
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

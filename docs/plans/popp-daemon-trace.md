# Plan: measure the PoPP path inside `zeta serve`

Status: not started, no code changed yet. Branch `popp-daemon-trace`, based on `main` (SDK 1.3.2, version 0.15.0), which builds with `./gradlew :cli:installDist`.

SDK 1.3.2 keeps the numbers comparable with the baseline below. SDK 2.0.1 needs one re-measurement afterwards.

## Goal

Answer where the time of a PoPP token goes, with JVM start-up out of scope and the SDK running in the daemon. Report in these groups:

1. **Preparation (once):** SDS, service discovery, SDK per service including ZETA discovery.
2. **Login:** ZETA login per service, periodic access-token refresh (warm sessions).
3. **Main path (per token):**
   - card readable (reader or Konnektor/TI-Gateway)
   - open the PoPP WebSocket with a warm access token
   - APDU exchange per scenario: network round trip, plus APDU or Konnektor time
   - start and stop card session, Konnektor scenario only

## Baseline

`zeta popp`, new process per run, cold JVM, against `popp.ref.poppservice.de`, 2026-11-04. Median in ms, token valid unless noted.

| Step | Card image (`kartos`) | PC/SC (Identive CLOUD 4700 F, contact) | TI-Gateway |
|---|---|---|---|
| Total | 2079 | 4018 | 3471 |
| `popp.flow` | 932 | 3040 | 2440 |
| SDS | – | – | 237 |
| `sdk.init` | 580 | 462 | 345 |
| First login, extra cost | 1799 | 2105 | about 3960 |
| Card readable | 14 | 25 | not measured |
| `startCardSession` | – | – | 193 |
| Open WebSocket with checks and DPoP | 659 (includes `kartos` start) | 640 | 462 |
| Card or `secureSendApdu` | 4.5 | 2076 | 1154 |
| Service and network | 218 | 294 | 245 |
| `stopCardSession` | – | – | 67 |

Scenario details:

| Scenario | APDUs (image / real card) | PC/SC card | TI-Gateway `secureSendApdu` |
|---|---|---|---|
| 1 | 2 / 2 | 86 | 136 |
| 2 | 3 / 3 | 668 | 243 |
| 3 | 10 / 2 | 194 | 208 |
| 4 | 3 / 3 | 1128 | 550 |

Open points from the baseline:

- PC/SC transfer costs about 0.98 ms per byte (derived, not measured). Unverified guess: the reader does not negotiate the rate announced by the ATR (`TA1=97`).
- Two TI-Gateway runs took 12.8 and 13.8 s, with single service responses up to 3.7 s. Cause unknown. Pauses of 60 to 300 s did not reproduce it.
- One run failed on an SDS timeout at the TI-Gateway after 10 s.
- Einbox-Konnektor not measured yet.

## Changes

### 1. Detached trace events and a JSON sink

`trace/Tracer.kt` only emits at process end, so a daemon produces nothing today, and a long-lived root would grow without bound.

- Add `Tracer.event(name, attrs) { span -> … }`: a suspend, parentless root. It restores the thread-local like `spanUnder` and hands the finished span to a sink.
- Add `Tracer.start(name, attrs): Span` for spans that cannot wrap a block.
- Add `Tracer.initDaemon(sink)`, and clear the sink in `reset()`.
- New `trace/TraceFile.kt`: serialize one span tree per line (`name`, start offset, duration, `ok`, `error`, `attrs`, `children`, plus an epoch timestamp). Appends under a lock. A write failure logs a warning and never breaks a request.
- Spans must only carry APDU headers, sizes and status words, never tokens or card data.

### 2. Spans for the new structure

| Group | Span | Where |
|---|---|---|
| Preparation | `prep.catalog` as an event | `ServeCommand.warmUpInBackground` |
| Preparation | `warm` event per endpoint, attrs `resource`, `scopes` | `ServeCommand.warmUpInBackground` |
| Preparation | `sdk.init`, `connector.connect` (SDS) | already there |
| Preparation | `sdk.discover`, `sdk.register` | `lifecycle/LoginFlow.kt` |
| Login | `login` around `ensureLoggedIn` | `DaemonContext.warmSessionFor` |
| Login | `refresh` event with `lock.wait` and `sdk.authenticate` | `DaemonContext.startRefreshJob` |
| Main path | `mint` event with `lock.wait` | `serve/PoppEndpoint.kt` |
| Main path | `session.warm` around `warmSessionFor` | `DaemonContext.mintPoppToken` |
| Main path | `card.ready` around `SmartCard.connect` or `KartosProcess.spawn` | `popp/PoppFlow.kt` |
| Main path | `ws.open`, from the `sdk.ws` call to block entry | `PoppFlow.poppConnect` |
| Main path | `scenario`, attrs `round`, `apdus`, `respBytes` | `popp/PoppClient.kt`, both scenario loops |
| Main path | `card.apdu`, attrs `hdr` (first 4 bytes), `cmdBytes`, `respBytes`, `sw` | `popp/PoppFlow.kt` |

Notes:

- `handlePoppToken` returns non-locally from inside `withLock`. `Tracer.event` takes a non-inline lambda, so compute the `MintResult` inside the event and respond after it.
- Only `/api/popp/token` gets the `mint` event; `popp-then-read` stays as it is.
- `connector.secureSendApdu` should become a child of `scenario`: use `session.traced` in `ConnectorFlow.kt`. That leaves `tracedUnder` and the `Span?` parameter of the `poppConnect` callback unused, so remove them.
- `ws.open` includes the discover, register, token and DPoP checks inside SDK `ws()`. The SDK has no hook to split them, the `-vv` timing logs do (about 140 ms).
- The Konnektor time stays opaque inside `secureSendApdu`.

### 3. `zeta serve` options

In `serve/ServeCommand.kt`, documented in `docs/serve.md`:

- `--trace-file PATH` (env `ZETA_TRACE_FILE`): first thing in `runCommand`, call `Tracer.initDaemon`.
- `--popp-card kartos` with `--popp-kartos-image PATH` (env `ZETA_POPP_KARTOS_IMAGE`, required then) and `--popp-kartos-bin` (env `ZETA_KARTOS_BIN`).

### 4. Measurement driver

A script outside the CLI, no new command:

- Wait for `warmupComplete` on `/api/health`.
- Call `/api/popp/token` N times, optionally with pauses, and discard the response body because it holds the token.
- Turn the JSON lines into tables for the groups above.

### 5. Tests

- `Tracer.event`: delivers to the sink, no-op when disabled, marks failures, leaves the thread-local clean.
- `TraceFile`: JSON shape including children and attrs.
- `--popp-card kartos` without an image is a usage error.

## Measurements to run

Against REF, each with the daemon warm and with pauses, in this order: card image, PC/SC, TI-Gateway, Einbox-Konnektor.

- The warm sweep logs into every VSDM instance in the catalog, so it registers additional clients on REF, and logins run in parallel. Take the login cost from `warm` events and compare with the sequential baseline.
- Force at least one refresh to get its duration and `lock.wait`.

## Decide after measuring

| Place | Observation | Possible change |
|---|---|---|
| `DaemonContext.startRefreshJob` | Refresh runs under `requestMutex`, so a mint can wait for it | Refresh outside the lock, if the wait is real |
| SDK `ws()` | New client per call, not closed as far as read in 2.0.1, unchecked in 1.3.2 | Reuse it (up to about 500 ms, possible leak in a daemon); needs an SDK change |
| SDK `ws()` | Discover, register and token checks on every call, about 140 ms including 40 ms DPoP | Small gain, DPoP stays |
| `popp/ConnectorFlow.kt` | `startCardSession` runs before the WebSocket opens | Run in parallel, about 190 ms |
| SDS at daemon start | Not checked whether it loads up front | Load it up front so the main path cannot hit an SDS timeout |

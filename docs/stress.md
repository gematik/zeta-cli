# Load testing ZETA Guard: `zeta stress`

`zeta stress` drives a ZETA-Guard-protected resource with a fleet of **SMC-B-backed OAuth clients** and
measures how the guard behaves under load — login storms, refresh churn, and authenticated VSDM reads. It
builds a realistic client population once, keeps it in a single SQLite file, and replays it at controllable
rates while recording per-attempt latency and outcome into a text summary and an HTML report.

It is part of the `zeta` binary — every command below is `zeta stress …`, not a separate tool. A run is
described entirely by **one YAML profile**; the CLI adds only `-v` and `--db`.

```
zeta stress import-identities ./smcb-bundles   # 1. build the SMC-B identity corpus (once)
zeta stress preflight  profile.yaml            # 2. register the OAuth client population (DCR)
zeta stress popp get   profile.yaml            # 3. (VSDM only) obtain PoPP tokens via kartos
zeta stress verify     profile.yaml            # 4. prove one client runs the scenario end-to-end
zeta stress run        profile.yaml            # 5. drive the load, write the report
```

---

## 1. The state database

Every subcommand reads and writes one SQLite file — `--db FILE` (default `stress.db`). It holds three
tables, populated in order by the workflow:

| Table | Populated by | Contents |
| --- | --- | --- |
| **identities** | `import-identities` | SMC-B signing identities — private key + certificate + Telematik-ID, parsed from card bundles |
| **clients** | `preflight` | the OAuth clients registered (DCR) per identity, per resource |
| **popp tokens** | `popp get` / `popp import` | PoPP tokens bound to identities, for VSDM scenarios |

The client population models the field: **N institutions** (each an SMC-B identity / Telematik-ID) each
running **1..many OAuth clients** (workstations / PVS). Subject tokens are signed straight from the DB with
the identity's SMC-B key (brainpoolP256r1) via the SDK's `CustomSmcbTokenProvider` seam — no smartcard or
connector in the loop, so a large fleet can re-login as fast as the guard allows.

`zeta stress db info` prints the totals, broken down per resource endpoint:

```
identities  : 2000
clients     : 4137
popp tokens : 2000

per endpoint:
  https://zeta-plain.t20r.cloud   clients 4137   institutions 2000
```

---

## 2. Commands

| Command | Purpose |
| --- | --- |
| `zeta stress import-identities DIR` | Import a directory of `*.tar.gz` SMC-B card bundles into the corpus. |
| `zeta stress preflight PROFILE` | Register the run's client population (DCR) from the profile's `cohort:`. **Idempotent** — tops each identity up to its seeded target, skipping clients already registered. |
| `zeta stress popp get PROFILE` | Obtain PoPP tokens for the registered roster via the kartos eGK flow (needs a `popp:` block). Idempotent; `--force` re-fetches to the target. |
| `zeta stress popp import DIR` | Import off-band PoPP tokens (compact JWTs, one per line) and bind them to identities by their `actorId`. |
| `zeta stress popp export OUT_DIR` | Export stored PoPP tokens as `<insurant>-<telematik-id>.jwt` files. |
| `zeta stress verify PROFILE` | Drive **one** client through the profile's scenario end-to-end and assert the observable facts (see [§6](#6-verify--proving-the-scenario)). A smoke test before a full run. |
| `zeta stress run PROFILE` | Run the load test; print a summary and write `report.html`. |
| `zeta stress db info` | Show identity / client / PoPP-token totals, per endpoint. |

Shared options on every subcommand: `--db FILE` (default `stress.db`) and `-v` / `-vv` / `-vvv` (INFO /
DEBUG / TRACE). Commands that take a `PROFILE` accept `--db` as an override of the profile's own `db:`.

---

## 3. The run profile

One YAML file is the single source of truth for a run — fleet, connection, scenario, and load shape. A
fully-commented example lives at [`stress-profile.yaml`](../stress-profile.yaml) in the repo root. Every
key is optional except the ones a scenario needs at run time (`resource:`, and `request.url:` for
`login-and-vsdm-storm`).

### Target, identity, load

| Key | Default | Meaning |
| --- | --- | --- |
| `resource` | — (**required**) | Resource origin (base URL) the fleet authenticates against. |
| `scope` / `scopes` | — | Access-token scope the SDK requests. A string, or a list `scopes: [a, b]`. Required for `preflight`. |
| `db` | `stress.db` | SQLite state file (overridable with `--db`). |
| `concurrency` | `100` | Max in-flight attempts and DB pool size. Coerced to `4..128`. |
| `duration` | warm-up + one cycle | Total run time. Accepts `ms` / `s` / `m` / `h`; a bare number is **seconds**. |
| `attempt-timeout` | `30` | Seconds; a per-attempt wall-clock cap. A stalled attempt is recorded as a failure and releases its permit promptly, so a collapsing guard can't pin in-flight work for minutes. |
| `max-live-clients` | `concurrency × 4` | Cap on the **working set** of distinct clients driven at once. Each live client holds several HTTP engines that aren't shared across the fleet, so driving a huge roster exhausts host threads; only `concurrency` clients run at a time, so a bounded warm set drives identical load without the sprawl. Rate/latency are unaffected; register-storm still mints a fresh server-side client per attempt. Raise it to exercise more distinct identities. |
| `abort-on-fail-pct` | `90` | Abort a waveform run once the trailing window's failure rate exceeds this percent — a collapsed guard ends the run instead of being hammered for the full duration. Set to `100` to disable. (Ramps use their own `max-fail-pct`.) |
| `random-clients` | `false` | Ignore the fixed working set: shuffle the **whole** fleet (seeded by `cohort.seed`) and build/close one client per attempt, so a long run iterates the entire population in random order while only in-flight clients stay resident. Trades per-attempt HTTP-engine rebuild CPU for full client diversity — use it when the test cares about exercising many distinct registered clients, not just steady-state throughput. |

### Cohort — the client population

```yaml
cohort:
  institutions: 2000            # N SMC-B identities
  clients-per-institution: 1..8 # each registers a random count in this range
  seed: 42                      # optional — makes the fan-out reproducible
```

`clients-per-institution` accepts `N`, `"a..b"`, or `[a, b]`. `cohort:` may also be a bare number (that
many institutions, one client each). Default: 100 institutions, one client each. Total clients ≈
institutions × mean(clients-per-institution).

**Cohort vs. working set — two independent dials.** The cohort is the client *population*: how many OAuth
clients `preflight` registers (server-side rows, index size, the table every token exchange looks a client
up in). The **working set** (`max-live-clients`, default `concurrency × 4`) is how many distinct clients a
`run` actually drives at once. They measure different things and needn't match:

- Size the **cohort** to the *registration scale* you want to test — production realism (tens of thousands
  of registered PVS) → large; a pure throughput number → a few thousand is plenty.
- Size the **working set** to the *concurrent active* load — only `concurrency` clients run at any instant,
  so a bounded set gives ample rotation while keeping resident HTTP engines (and host threads) in check.

Driving, say, 800 of 20 000 registered clients is realistic rather than wasteful: in production most
registered clients are idle at any instant, and the 800 active ones still query against the full-size
tables, so the registration scale *is* exercised. It's only overkill when you're measuring steady-state
throughput on a crypto-bound guard, where the population barely moves the number — then `cohort ≈ working
set` is the efficient choice. The HTML report's *cohort size* reflects the **driven working set**.

When the test genuinely needs to exercise the *whole* population (many distinct clients over a long run,
not just a warm subset), set `random-clients: true`: it shuffles the full fleet and builds/closes a client
per attempt, so live engines stay bounded to the in-flight count while coverage spans the entire cohort.
The cost is rebuilding each client's HTTP engines per attempt (the `random-clients` row above).

### TLS / environment (applied to every leg, including the VSDM read)

| Key | Default | Meaning |
| --- | --- | --- |
| `insecure` | `false` | Disable TLS verification (dev / self-signed only). |
| `ca-cert` | — | Extra PEM CA file(s) on top of the JVM roots — a string or a list. |
| `connect-timeout` | — | Seconds. |
| `request-timeout` | — | Seconds. |
| `asl-prod` | `false` | Use the ASL **production** environment (default is the non-prod ASL env). |

---

## 4. Scenarios

Set `scenario:` (default `login-storm`). Each scenario decides **which credential state is wiped before an
attempt** and **what each attempt does**:

| Scenario | Wiped per attempt | Each attempt | Measures |
| --- | --- | --- | --- |
| **`login-storm`** | all tokens + ASL state | a bare `authenticate()` (cold re-login), verified via `status()` | the pure auth path: nonce → SMC-B sign → token exchange |
| **`login-and-vsdm-storm`** | all tokens + ASL state | one authenticated read from the `request:` block, driving the whole cold chain (token exchange + ASL handshake + read), PoPP token attached | the full end-to-end read path |
| **`refresh-storm`** | access token only | `authenticate()` from a surviving refresh token | the refresh path (cheaper — no subject-token verify / ASL) |
| **`register-storm`** | **all state** — registration, tokens, ASL, discovery cache | the full cold cycle: discovery → nonce → **fresh DCR** → token exchange, verified via `status()` | registration + token under load; reproduces a client-rotation long-term test |
| **`discover-storm`** | **all state** (forces a real fetch) | only `discover()` — fetch + validate the well-known metadata, verified by asserting the config actually persisted (`discover()` swallows fetch failures) | the lightest guard interaction; a **harness self-test** for rate control / concurrency / reporting with minimal server load |

> **`register-storm` re-registers every attempt.** Each attempt creates a **new server-side OAuth
> client** (fresh DCR), so it puts the auth server's registration path *and* the DB under sustained load —
> the way to reproduce registration-pool exhaustion. It also **accumulates clients in Keycloak**; clean
> them up afterwards. Run `preflight` first to seed the client slots the run iterates.

### `request:` — the read for `login-and-vsdm-storm` (required for that scenario)

```yaml
request:
  url: https://vsdm-dev.tk.de/vsdservice/v1/vsdmbundle?profileVersion=1.0   # required
  method: GET                                   # default GET
  headers:                                      # optional extra request headers
    Accept: application/fhir+json
  popp: true                                    # attach the identity's cached PoPP token as the PoPP header
  expect-status: [200, 304]                     # success set — a single int or a list
  op: vsdm                                      # reporter label for the attempt
```

With `popp: true` the attempt is **strict** — a roster identity with no stored PoPP token is a recorded
failure. PoPP tokens come from the DB (minted by `popp get` / `popp import`), not from the `popp:` block,
which only configures how `popp get` mints them.

### `popp:` — how `popp get` mints tokens

```yaml
popp:
  egk-dir: ./egk-images     # directory of eGK XML card images fed to the kartos simulator
  per-identity: 1           # tokens to hold per roster identity (top-up target)
  concurrency: 8            # parallel identities (each spawns a kartos subprocess per token)
  service-url: wss://popp.dev.poppservice.de/popp/practitioner/api/v1/token-generation-ehc
  kartos-bin: kartos        # kartos executable (default: on PATH)
  scope: popp               # scope requested for the PoPP service
```

---

## 5. Load shapes (modes)

The run's rate over time comes from **one of two** blocks. If a `ramp:` is present it **takes over** and
`warmup:` / `cycle:` are ignored. Rates are **requests per minute**.

### Waveform — hold or oscillate a rate

A `warmup:` phase (optional, runs once) followed by a `cycle:` of phases that **repeats until `duration`**.
Use it to hold a steady rate, or oscillate to watch recovery. Each phase is one of two kinds:

**Hold** — a constant rate for the phase:

```yaml
warmup: { name: warmup, rate: 300, duration: 30s }
cycle:
  - { name: burst, rate: 5000, duration: 1m }
  - { name: calm,  rate: 300,  duration: 1m }
```

**Spike** — a baseline that jumps to a peak in random time-slots (a phase with `peak:`):

```yaml
cycle:
  - name: bursty
    base: 300         # baseline req/min
    peak: 4000        # spike req/min
    probability: 0.2  # chance each slot spikes
    duration: 2m
    bucket: 2s        # slot width (default 2s)
```

The spike choice is a deterministic hash of the slot index, so the same profile **replays identically**
run to run. Durations accept `ms` / `s` / `m` / `h` (bare = seconds).

### Ramp — find the breaking point

Start at `start` req/min and add `step` every `step-interval` seconds, up to `ceiling`. The ramp **stops
early** the moment a trailing window's failure rate exceeds `max-fail-pct` **or** its p99 latency exceeds
`max-p99`, and reports the **peak healthy rate** — the highest sustained rate that stayed within both
limits.

```yaml
ramp:
  start: 500          # initial rate (req/min)
  step: 500           # added each step
  step-interval: 20   # seconds held per step
  ceiling: 5000       # never climb past this, even if still healthy
  max-fail-pct: 10    # break if a window's failure rate exceeds this (%)
  max-p99: 5000       # break if a window's p99 latency exceeds this (ms)
  duration: 5m        # optional hard cap on total ramp time
```

| `ramp:` key | Default |
| --- | --- |
| `start` | `500` |
| `step` | `500` |
| `step-interval` | `20` |
| `ceiling` | `5000` |
| `max-fail-pct` | `10` |
| `max-p99` | `5000` |
| `duration` | derived from the step schedule |

The run's last line is the verdict:

```
Breaking point reached — peak healthy rate ≈ 2440 req/min
# …or, if it reached the ceiling without ever tripping a limit:
Ramp finished without breaking — peak healthy rate ≈ 5000 req/min
```

---

## 6. `verify` — proving the scenario

`zeta stress run` reports HTTP-level success, but a green status alone doesn't prove the *crypto chain*
ran. `zeta stress verify` takes **one** registered client, wipes the same state the scenario would, runs
the cold chain once, and asserts the facts a status code can't:

- **login** (every scenario): SDK state transitions `REGISTERED_NO_VALID_TOKENS → HAS_ACCESS_AND_REFRESH_TOKEN`
  (an access token can't exist unless nonce → SMC-B sign → token exchange all ran). `refresh-storm` starts
  from `HAS_REFRESH_TOKEN`; `register-storm` starts cold (`NOT_REGISTERED`) and re-runs the full DCR cycle.
- **VSDM** (`login-and-vsdm-storm`): the read establishes an ASL session (storage populated) and returns a
  FHIR bundle **carrying the insurant (KVNR) from the PoPP token that was sent**.

```
verify [login-and-vsdm-storm] — client ws-01 (identity 5-2-…, insurant X1102…)
resource https://vsdm-dev.tk.de → https://vsdm-dev.tk.de/vsdservice/v1/vsdmbundle

  ✓ login: pre-state is REGISTERED_NO_VALID_TOKENS
  ✓ login: storage cleared (no tokens, no ASL)
  ✓ login: access + refresh minted (nonce → sign → token exchange)
  ✓ vsdm: ASL session established (storage populated)
  ✓ vsdm: HTTP 200 accepted
  ✓ popp: FHIR body carries insurant X1102…

PASS — 6/6 checks passed in 812 ms.
```

It exits non-zero on any failed check. Run with `-vvv` to also see every HTTP leg on the wire.

---

## 7. How success and failure are counted

Because the SDK's `discover()` / `register()` / `authenticate()` return `Result<Unit>` but can report
success even when the underlying flow failed, the harness does **not** trust those Results alone. After
each step it **re-reads `status()`** — the one call that reflects real state — and treats a step as a
failure unless the status actually advanced (registration present; an access **and** refresh token minted).
For `login-and-vsdm-storm` the authenticated read's own HTTP status is checked against `expect-status`.
This is why the reported failure count is trustworthy even though the SDK's step results are not (tracked
in the SDK bug report *"`Result` swallows capability errors"*).

A failure is recorded with a short label (SDK error, `status=…`, `status=<code>`, or `timed out`) that is
aggregated into the summary and report histograms.

---

## 8. Output and reports

**Live progress.** On a TTY, `run` shows a k9s-style panel — current rate vs target, active phase, ok/fail
counts, throughput, p50/p95/p99, and a throughput sparkline. When piped it prints one plain progress line
per second, so logs and redirected output stay clean.

**Text summary** (stdout at the end):

```
── Stress summary ──────────────────────────
attempts     : 720
succeeded    : 720
failed       : 0
wall time    : 60.8 s
throughput   : 11.8 req/s (711 req/min)
latency (ok) : p50=285ms  p95=2206ms  p99=3038ms
failures     :
  3×  SdkStepFailure: authenticate reported success but status=HAS_REFRESH_TOKEN
```

Latency percentiles are over **successful** attempts; the failure histogram lists each distinct error by
count.

**HTML report + CSV.** Each run writes a timestamped folder `reports/<yyyyMMdd-HHmmss>/` containing:

- **`report.html`** — a self-contained, theme-aware page (path echoed on the last line):
  - **stat cards** — attempts, succeeded, failed, throughput, p50 / p95 / p99;
  - **Run details** — host, resource, scenario, cohort size, concurrency, TLS mode, start time, planned
    vs wall duration, and % ok;
  - **Phases** (waveform runs) — each phase's rate spec and duration, plus the cycle length and how many
    loops ran;
  - **Throughput over time** — req/s bucketed across the run, with coloured phase bands;
  - **Latency percentiles over time** — p50 / p95 / p99, same bands;
  - **Latency distribution** — a histogram over successful attempts;
  - **Failures** — count per distinct error (only if any failed).
- **`results.csv`** — every attempt, raw: relative timestamp, op, latency, ok, error, client ref,
  Telematik-ID, HTTP status, scenario, and active phase — for your own analysis.

---

## 9. Notes

- **kartos** — `popp get` and the `login-and-vsdm-storm` PoPP flow shell out to the kartos smartcard
  simulator (`popp.kartos-bin`, default `kartos` on `PATH`) against the eGK XML images in `popp.egk-dir`.
- **Dev TLS** — set `insecure: true` for self-signed dev endpoints; prefer `ca-cert:` for a
  corporate/self-signed CA in anything lasting. TLS settings apply to every leg, including the VSDM read.
- **Reproducible cohorts** — set `cohort.seed:` so the per-institution client fan-out is deterministic
  across `preflight` runs, and use spike phases (deterministic by design) for repeatable load shapes.
- **Surviving a collapsing target** — the harness is built not to fall over when the guard does. Only the
  working set of clients is kept resident (`max-live-clients`), so a huge roster can't exhaust host threads
  with per-client HTTP engines; a short `attempt-timeout` (30 s) releases a stalled attempt's concurrency
  permit promptly; and `abort-on-fail-pct` (default 90 %) ends a waveform run once the target has clearly
  collapsed rather than hammering it — and your driver — for the full duration. The failures that remain
  stay attributed to the guard, not the client.
- **Measure the guard, not the harness** — front proxies or test recorders in the request path can become
  the bottleneck before the guard does; confirm what you're measuring server-side.

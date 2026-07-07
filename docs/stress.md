# Load testing ZETA Guard: `zeta stress`

`zeta stress` drives a ZETA-Guard-protected resource with a fleet of SMC-B-backed OAuth clients to
measure how it behaves under load — login storms, refresh churn, and authenticated VSDM reads. It
keeps all its state (the SMC-B identities, their registered OAuth clients, and any PoPP tokens) in a
single SQLite file so a large client population can be built once and reused across runs.

Everything is described by one **run profile** (a YAML file). A fully-commented example lives at
[`stress-profile.yaml`](../stress-profile.yaml) in the repo root — that file is the canonical
parameter reference; this page is the workflow around it.

## State database

All subcommands read and write one SQLite file, `--db FILE` (default `stress.db`). It holds three
things, populated in order by the workflow below:

| Table | Populated by | Contents |
| --- | --- | --- |
| identities | `import-identities` | the SMC-B signing identities (keys + Telematik-IDs) |
| clients | `preflight` | OAuth clients registered (DCR) per identity, per resource |
| popp tokens | `popp get` / `popp import` | PoPP tokens bound to identities (for VSDM scenarios) |

`zeta stress db info` prints the totals, broken down per resource endpoint.

## Commands

| Command | Purpose |
| --- | --- |
| `zeta stress import-identities DIR` | Import a directory of SMC-B card bundles (`*.tar.gz`) into the state DB. |
| `zeta stress preflight PROFILE` | Register the run's client population (DCR) from the profile's `cohort:`. Idempotent — tops up to each identity's seeded target. |
| `zeta stress popp get PROFILE` | Obtain PoPP tokens for the roster via the kartos eGK flow (needs a `popp:` block). `--force` re-fetches to target. |
| `zeta stress popp import DIR` | Import off-band PoPP tokens (compact JWTs, one per line) and bind them to identities. |
| `zeta stress popp export OUT_DIR` | Export stored PoPP tokens as `<insurant>-<telematik-id>.jwt` files. |
| `zeta stress verify PROFILE` | Drive **one** client through the profile's scenario end-to-end (login, and VSDM if configured) — a smoke test before a full run. |
| `zeta stress run PROFILE` | Run the load test and write an HTML report. |
| `zeta stress db info` | Show identity / client / PoPP-token totals, per endpoint. |

Shared options: `--db FILE` (default `stress.db`) and `-v`/`-vv`/`-vvv` for log level.

## Scenarios

Set `scenario:` in the profile:

- **`login-storm`** — expire every client's tokens + ASL state, then re-login (authenticate) the whole
  cohort. Measures the pure auth path.
- **`login-and-vsdm-storm`** — as above, then each client performs an authenticated read defined by the
  profile's `request:` block (typically a VSDM `vsdmbundle` GET), optionally attaching the identity's
  PoPP token as the `PoPP` header. Requires a `request:` block; with `popp: true` an identity without a
  token is a recorded failure.
- **`refresh-churn`** — expire only the access token, exercising the refresh-token path.

## End-to-end workflow

```sh
# 1. Build the identity population once (SMC-B bundles → DB)
zeta stress import-identities ./smcb-bundles

# 2. Register the OAuth clients for the run's cohort (DCR)
zeta stress preflight stress-profile.yaml

# 3. Only for login-and-vsdm-storm: obtain PoPP tokens for the roster (kartos + eGK images)
zeta stress popp get stress-profile.yaml

# 4. Smoke one client through the whole scenario before hammering
zeta stress verify stress-profile.yaml

# 5. Run the load test
zeta stress run stress-profile.yaml

# Inspect what's in the DB at any point
zeta stress db info
```

Steps 1–3 are one-time setup for a given cohort; iterate on 4–5 as you tune the profile.

## Load shape

The run's rate over time comes from **one** of two blocks in the profile (rates are requests/minute):

- **Waveform** — a `warmup:` phase followed by a repeating `cycle:` of phases, until `duration:`. Use
  this to hold or oscillate a target rate and watch the response.
- **`ramp:`** — step the rate up until the response degrades, to find the breaking point (below).

If a `ramp:` block is present it **takes over** — `warmup:` / `cycle:` are ignored. `concurrency:` caps
in-flight attempts (and the DB pool); `attempt-timeout:` fails an attempt that stalls. TLS/environment
keys (`insecure`, `ca-cert`, `connect-timeout`, `request-timeout`, `asl-prod`) apply to every leg,
including the VSDM read. See [`stress-profile.yaml`](../stress-profile.yaml) for the complete key
reference and defaults.

## Ramp: finding the breaking point

A ramp starts at `start` req/min and adds `step` req/min every `step-interval` seconds, up to `ceiling`.
It **stops early** the moment a step's failure rate exceeds `max-fail-pct` **or** its p99 latency exceeds
`max-p99`, and reports the **peak healthy rate** — the highest step that stayed within both limits. Use
it to answer "how much can this deployment take before it degrades?".

| `ramp:` key | Default | Meaning |
| --- | --- | --- |
| `start` | `500` | initial rate (req/min) |
| `step` | `500` | rate added each step (req/min) |
| `step-interval` | `20` | seconds held per step |
| `ceiling` | `5000` | max rate — stop climbing here even if still healthy |
| `max-fail-pct` | `10` | break if a step's failure rate exceeds this (%) |
| `max-p99` | `5000` | break if a step's p99 latency exceeds this (ms) |
| `duration` | — | optional hard cap on total ramp time (e.g. `5m`) |

A complete ramp profile — target, scope, cohort, TLS, and the ramp, all in one file:

```yaml
# stress-ramp.yaml
resource: https://zeta-plain.t20r.cloud
scope: zero:audience
scenario: login-storm        # login-storm | login-and-vsdm-storm | refresh-churn
concurrency: 50
insecure: true               # dev / self-signed endpoint

cohort:
  institutions: 200
  clients-per-institution: 1..3
  seed: 42

ramp:
  start: 300                 # begin at 300 req/min
  step: 300                  # +300 req/min …
  step-interval: 30          # … every 30 s
  ceiling: 4000              # never exceed 4000 req/min
  max-fail-pct: 2            # break when a step fails > 2 %
  max-p99: 1500              # … or p99 latency exceeds 1.5 s
```

Run it like any other profile — register the client cohort once, then run:

```sh
zeta stress preflight stress-ramp.yaml     # one-time (DCR); skips clients already registered
zeta stress run stress-ramp.yaml           # drives the ramp; live panel on a TTY
```

The tail of the run prints the verdict, and the full per-step latency/failure curve lands in the written
`report.html`:

```
Breaking point reached — peak healthy rate ≈ 1800 req/min
# …or, if it reached `ceiling` without ever tripping a limit:
Ramp finished without breaking — peak healthy rate ≈ 4000 req/min
```

## Output & reports

- On a TTY, `run` shows a live k9s-style panel; when piped it prints one plain progress line per second,
  so logs and reports stay clean.
- At the end it prints a text summary and writes a self-contained `report.html` (path echoed on the last
  line), with the per-phase timeline, latency percentiles, and failure breakdown.

## Notes

- **kartos** — `popp get` and the `login-and-vsdm-storm` PoPP flow shell out to the `kartos` smartcard
  simulator (`popp.kartos-bin`, default `kartos` on `PATH`) against the eGK card images in
  `popp.egk-dir`. Install kartos and point at a directory of eGK XML images.
- **Dev TLS** — set `insecure: true` (or `-k`) for self-signed dev endpoints; prefer `ca-cert:` for a
  corporate/self-signed CA in anything lasting.
- **Reproducible cohorts** — set `cohort.seed:` so the per-institution client fan-out is deterministic
  across `preflight` runs.

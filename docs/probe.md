# `zeta probe` — permanently running auth-flow prober

`zeta probe` runs forever and keeps asking one question about every Zeta-protected endpoint of a TI
environment: **can a client still get a token?** It does that two ways, each on its own schedule:

| Probe | What it forces | Default cadence |
| --- | --- | --- |
| `login` | A full token exchange: the SMC-B signs a subject token, the auth server issues access + refresh tokens. | every 3h per endpoint |
| `refresh` | The refresh grant: only the refresh token is presented, no SMC-B involvement. | every 5m per endpoint |

Results are exposed as **Prometheus metrics** on `:9464/metrics` (an external scraper reads them and
forwards to your OpenTelemetry collector) and, when an OTLP endpoint is configured, as **traces** —
one span per probe with a child span per step.

It is part of the `zeta` binary and shares its authentication options: pick an `--auth-method`
(`p12`, `db`, or `connector`) exactly as for `zeta login`.

```sh
zeta probe --env ref --auth-method p12 --auth-p12-file smcb.p12 --auth-p12-password …
# zeta probe ready — metrics on http://0.0.0.0:9464/metrics, traces off — env ref, profile default,
#   7 target(s), login every 3h, refresh every 5m — Ctrl-C to stop
```

## What one probe does

Every probe against an endpoint runs these steps, each timed on its own:

1. `status` — read the cached SDK state (no network). If the endpoint has never been registered,
   `register` runs once: discovery plus dynamic client registration. That is bootstrap, not a probe;
   it shows up as a `register` step on that one run and never again.
2. `clear` — **shape the stored state so the SDK has no choice.** With a valid access token stored,
   `authenticate()` returns without talking to anyone, so a `login` probe drops both tokens (forcing
   the full exchange) and a `refresh` probe drops only the access token (forcing the refresh grant).
3. `authenticate` — the SDK's `authenticate()`. For a `login` probe this includes the `subject_token`
   step: the time the SMC-B (Konnektor, PKCS#12, or identity DB) took to sign.
4. `verify` — the state must now be `HAS_ACCESS_AND_REFRESH_TOKEN`; the new token's expiry is read for
   the `zeta_probe_token_expiry_time_seconds` gauge.

### The `fallback` label

The SDK never reports a failed refresh grant: when the refresh token is rejected it silently performs a
full exchange instead, and `authenticate()` succeeds. The probe catches this through the one thing the
two flows do differently — only the full exchange asks for an SMC-B signature — and labels such a
`refresh` probe `fallback="true"`. Treat a rising `fallback="true"` count as "the refresh grant is
broken", even though the probe itself succeeded. The first `refresh` probe after a bootstrap is
always a fallback (there is no refresh token yet).

## Scheduling: spread, never burst

For each probe kind, the targets are visited round-robin, one every `interval / n`. With 20 endpoints
and a 60s refresh interval, one refresh probe starts every 3s; every endpoint is still probed once a
minute, but the auth servers never see all 20 at once. A slow endpoint does not delay the others: each
probe runs on its own, and only probes against the *same* endpoint are serialised (so `login` and
`refresh` never race on one token store). A probe slower than `--probe-timeout` (default 2m) is
recorded as `result="timeout"`.

The service-discovery catalog is re-read every `--catalog-interval` (default 1h); endpoints that
appear are picked up on the next tick, endpoints that vanish are dropped together with their metrics.

## Targets

- `--env dev|ref|test|prod` (env `ZETA_ENV`) — the environment's catalog: every VSDM instance (scope
  `vsdservice`) plus the PoPP service (scope `popp`). This is the same list `zeta serve` warms.
- `--endpoint URL` with `--endpoint-scope SCOPE` — extra endpoints. The lists are positional: the i-th
  scope belongs to the i-th endpoint, and the counts must match — there is no default scope. In the
  environment, both are whitespace- or comma-separated lists:

  ```sh
  ZETA_PROBE_ENDPOINTS="https://vsdm.example/ https://other.example/"
  ZETA_PROBE_ENDPOINT_SCOPES="vsdservice other-scope"
  ```
- `--no-catalog` — only the explicit list.

One process probes one environment; run one container per environment.

## Running in Docker

The repository ships a `Dockerfile` whose default command is `probe -v`. State — the profile database
with registrations and tokens — lives under `/data` (`XDG_CONFIG_HOME`), so mount a volume there:

```sh
docker build -t zeta .

docker run -d --name zeta-probe-ref \
  -p 9464:9464 \
  -v zeta-probe-ref:/data \
  -v ./secrets:/secrets:ro \
  -e ZETA_ENV=ref \
  -e ZETA_AUTH_METHOD=p12 \
  -e ZETA_AUTH_P12_FILE=/secrets/smcb.p12 \
  -e ZETA_AUTH_P12_PASSWORD=… \
  -e ZETA_PROBE_ENDPOINTS="https://vsdm.example/" \
  -e ZETA_PROBE_ENDPOINT_SCOPES="vsdservice" \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=https://collector.example:4318 \
  -e OTEL_EXPORTER_OTLP_HEADERS="Authorization=Bearer …" \
  zeta
```

The same as Compose, with the Prometheus scrape job next to it:

```yaml
services:
  zeta-probe:
    build: .
    ports: ["9464:9464"]
    volumes:
      - zeta-probe:/data
      - ./secrets:/secrets:ro
    environment:
      ZETA_ENV: ref
      ZETA_AUTH_METHOD: p12
      ZETA_AUTH_P12_FILE: /secrets/smcb.p12
      ZETA_AUTH_P12_PASSWORD: …
      ZETA_PROBE_LOGIN_INTERVAL: 3h
      ZETA_PROBE_REFRESH_INTERVAL: 5m
      OTEL_EXPORTER_OTLP_ENDPOINT: https://collector.example:4318
      OTEL_EXPORTER_OTLP_HEADERS: "Authorization=Bearer …"
    stop_grace_period: 20s
volumes:
  zeta-probe: {}
```

```yaml
# prometheus.yml
scrape_configs:
  - job_name: zeta-probe
    static_configs:
      - targets: ["zeta-probe:9464"]
```

Notes:
- The container runs as uid `10001`. A bind mount for `/data` must be writable by that user
  (`chown 10001 ./probe-data`).
- `GET /-/healthy` on the metrics port answers `200` while the process is up — use it for a health
  check or readiness probe.
- `docker stop` sends SIGTERM; the daemon stops scheduling, closes its SDK sessions, flushes pending
  spans and exits. Give it a grace period of ~20s.
- The SQLite driver unpacks a native library into `java.io.tmpdir`. With a read-only root filesystem
  mount a tmpfs at `/tmp`, or add `-Dorg.sqlite.tmpdir=/data/tmp` to `JAVA_OPTS`.
- For `--auth-method connector`, mount the `.kon` file and point `ZETA_CONNECTOR_CONFIG` at it; the
  Konnektor must be reachable from the container network.

## Metrics

All series carry `env`, `service` and `endpoint`. `service` is the slug dashboards should key on: the
catalog's instance name for a VSDM service (the name its routing table points at, e.g. `vsdm-tk`),
`popp` for the PoPP service, and the host name for an explicit `--endpoint`. `endpoint` is the resource
origin (`https://vsdm.example/`).

| Metric | Type | Labels | Meaning |
| --- | --- | --- | --- |
| `zeta_probe_duration_seconds` | histogram | `probe`, `step`, `result` | Wall time per step (`status`, `register`, `clear`, `authenticate`, `subject_token`, `verify`) and for the whole probe (`step="total"`). `result` is `ok`/`error` per step; `ok`/`error`/`timeout` for the total. |
| `zeta_probe_runs_total` | counter | `probe`, `result`, `error_type`, `fallback` | Completed probes. `error_type` is the SDK's flow code when it reports one (`AUTHENTICATION_ERROR`, `REGISTRATION_FAILED_ERROR`, …), else `timeout`, `no_tokens`, or the exception class (`SocketException`); empty on success. |
| `zeta_probe_up` | gauge | `probe` | `1` if the last probe of that kind against the endpoint succeeded, `0` otherwise. |
| `zeta_probe_last_success_time_seconds` | gauge | `probe` | Unix time of the last successful probe of that kind. |
| `zeta_probe_token_expiry_time_seconds` | gauge | — | Unix time at which the endpoint's current access token expires. |
| `zeta_probe_targets` | gauge | — | Endpoints currently in the schedule. |

Useful alerts: `zeta_probe_up == 0` for longer than two intervals; `increase(zeta_probe_runs_total{probe="refresh",fallback="true"}[1h]) > 0`;
`histogram_quantile(0.95, rate(zeta_probe_duration_seconds_bucket{step="subject_token"}[15m]))` for
the Konnektor's signing latency.

The endpoint is the OpenTelemetry Prometheus exporter, so `target_info` carries `service_name="zeta-probe"`,
`service_version` and `deployment_environment_name`. `--metrics-host`/`--metrics-port` (or
`OTEL_EXPORTER_PROMETHEUS_HOST`/`_PORT`) move it.

## Traces

Traces are off until an OTLP endpoint is configured — `--otlp-endpoint URL` or
`OTEL_EXPORTER_OTLP_ENDPOINT`. The default transport is `http/protobuf` (port 4318); `--otlp-protocol
grpc` or `OTEL_EXPORTER_OTLP_PROTOCOL=grpc` switches. Authentication is a header:
`--otlp-header "Authorization=Bearer …"` (repeatable) or `OTEL_EXPORTER_OTLP_HEADERS="k=v,k2=v2"`.
Everything else the OpenTelemetry Java SDK understands works unchanged: `OTEL_EXPORTER_OTLP_CERTIFICATE`,
`OTEL_EXPORTER_OTLP_CLIENT_KEY` / `_CLIENT_CERTIFICATE` for mTLS, `OTEL_EXPORTER_OTLP_COMPRESSION=gzip`,
`OTEL_RESOURCE_ATTRIBUTES`, `OTEL_TRACES_SAMPLER`.

Each probe is one trace: a root span `zeta.probe.login` / `zeta.probe.refresh` (attributes
`zeta.env`, `zeta.service`, `zeta.endpoint`, `zeta.scopes`, `zeta.result`, `zeta.fallback`, `zeta.registered`,
`zeta.status.before` / `zeta.status.after`) with children `probe.status`, `probe.register`,
`probe.clear`, `sdk.authenticate` → `sdk.subject_token`, and `probe.verify`. Failed steps carry the
exception.

The CLI's own `--trace` (the in-process span tree printed at exit) is refused by `zeta probe`: a
process that never exits would grow that tree forever.

## What the data looks like

Both samples below are from a run against the `dev` catalog with a PKCS#12 identity.

`GET :9464/metrics` (histogram buckets omitted):

```
target_info{deployment_environment_name="dev",service_name="zeta-probe",service_version="0.15.0",…} 1
zeta_probe_targets{env="dev"} 5.0
zeta_probe_up{env="dev",service="vsdm-3",endpoint="https://dienst.vsdd2.rudev.service-ti.de/",probe="login"} 1.0
zeta_probe_up{env="dev",service="vsdm-3",endpoint="https://dienst.vsdd2.rudev.service-ti.de/",probe="refresh"} 1.0
zeta_probe_up{env="dev",service="vsdm-1",endpoint="https://vsdm-dev.tk.de/",probe="login"} 0.0
zeta_probe_up{env="dev",service="popp",endpoint="https://popp.dev.poppservice.de/",probe="refresh"} 1.0
zeta_probe_runs_total{env="dev",service="vsdm-3",endpoint="…",probe="refresh",result="ok",error_type="",fallback="false"} 2.0
zeta_probe_runs_total{env="dev",service="vsdm-3",endpoint="…",probe="refresh",result="ok",error_type="",fallback="true"} 1.0
zeta_probe_runs_total{env="dev",service="vsdm-1",endpoint="…",probe="login",result="error",error_type="AUTHENTICATION_ERROR",fallback="false"} 2.0
zeta_probe_runs_total{env="dev",service="vsdm-4",endpoint="…",probe="refresh",result="error",error_type="SocketException",fallback="false"} 2.0
zeta_probe_duration_seconds_sum{env="dev",service="vsdm-3",endpoint="…",probe="login",step="subject_token",result="ok"} 0.013
zeta_probe_duration_seconds_count{env="dev",service="vsdm-3",endpoint="…",probe="login",step="subject_token",result="ok"} 1
zeta_probe_last_success_time_seconds{env="dev",service="vsdm-3",endpoint="…",probe="refresh"} 1.790350379E9
zeta_probe_token_expiry_time_seconds{env="dev",service="vsdm-3",endpoint="…"} 1.790350679E9
```

One `login` trace as the collector's debug exporter shows it (resource `service.name=zeta-probe`,
`service.version=0.15.0`, `deployment.environment.name=dev`; scope `de.gematik.zeta.cli.probe`):

```
zeta.probe.login        392 ms   zeta.service=vsdm-3  zeta.endpoint=https://dienst.vsdd2.rudev.service-ti.de/
                                 zeta.scopes=vsdservice  zeta.result=ok  zeta.fallback=false  zeta.registered=false
                                 zeta.status.before=REGISTERED_NO_VALID_TOKENS  zeta.status.after=HAS_ACCESS_AND_REFRESH_TOKEN
├─ probe.status          73 ms
├─ probe.clear           47 ms
├─ sdk.authenticate     260 ms
│  └─ sdk.subject_token   6 ms
└─ probe.verify          11 ms
```

A failed one carries the error on both the root and the failing step:

```
zeta.probe.login        Status=Error "[AUTHENTICATION_ERROR] Client hat keine Berechtigung auf angef. Resource"
                        zeta.service=vsdm-1  zeta.result=error  zeta.registered=true
├─ probe.status
├─ probe.register
├─ probe.clear
└─ sdk.authenticate     Status=Error  exception.type=java.lang.IllegalStateException  exception.message=[AUTHENTICATION_ERROR] …
   └─ sdk.subject_token
```

## Logging

Failures and fallbacks are logged at WARN, recoveries and bootstraps at INFO, and every successful probe
at DEBUG — so `-v` shows the interesting events and `-vv` every probe. `-vv` also shows the SDK's own
lines (`Access token expired, fetching refresh token` on a refresh probe, `No refresh token found,
getting new access token` on a login probe), which is the quickest way to confirm which flow ran.

## Options

See the [`zeta probe` options table](../README.md#zeta-probe) in the README. Everything is also
settable through `zeta.yaml`.

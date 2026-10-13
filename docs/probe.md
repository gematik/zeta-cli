# `zeta probe` — permanently running auth-flow prober

`zeta probe` runs forever and keeps asking one question about every Zeta-protected endpoint of one or
more TI environments: **can a client still get a token?** It does that two ways, each on its own schedule:

| Probe | What it forces | Default cadence |
| --- | --- | --- |
| `login` | A full token exchange: the SMC-B signs a subject token, the auth server issues access + refresh tokens. | every 3h per endpoint |
| `refresh` | The refresh grant: only the refresh token is presented, no SMC-B involvement. | every 5m per endpoint |

Metrics and traces are **pushed via OTLP** to an OpenTelemetry Collector — the same single ingestion path
inside and outside a cluster; there is no scrape endpoint. Every probe is also one trace, with a child
span per step. `/healthz` and `/readyz` on port 8080 serve the orchestrator.

It is part of the `zeta` binary and shares its authentication options: pick an `--auth-method`
(`p12`, `db`, or `connector`) exactly as for `zeta login`.

```sh
OTEL_EXPORTER_OTLP_ENDPOINT=http://collector:4318 \
  zeta probe --probe-env ref --auth-method p12 --auth-p12-file smcb.p12 --auth-p12-password …
# zeta probe ready — metrics and traces to http://collector:4318 every 30s, health on
#   http://0.0.0.0:8080/healthz — env ref, profile default, 7 target(s), login every 3h,
#   refresh every 5m, backoff up to 1h — Ctrl-C to stop
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
`zeta_probe_catalog_age_seconds{env}` is the age of the catalog copy the current target list was built
from — when service discovery is unreachable the prober keeps working from the last copy, and this is
how a stale target list shows.

### Backoff

A target that keeps failing is not hammered every interval. After `k` consecutive failures of one probe
kind, its next probe of that kind waits `min(interval × 2^k, --backoff-max)` (default cap 1h): a
`refresh` probe on a 5m interval retries after 10m, 20m, 40m, then hourly. The held-off target still
consumes its round-robin slot, so the other targets keep their exact cadence rather than being probed
more often. A success clears the hold-off. `zeta_probe_backoff_until_time_seconds` shows until when a
target is held off (absent otherwise); `zeta_probe_last_run_time_seconds` records every attempt.
`--backoff-max 0` disables it. Backoff only has an effect where `2 × interval` is below the cap: with
the defaults it applies to `refresh` (5m) but not to `login` (3h), whose next attempt is already further
away than the 1h cap.

## Targets

- `--probe-env dev,ref` (env `ZETA_PROBE_ENV`; one or more of `dev`, `ref`, `test`, `prod`) — each
  environment's catalog: every VSDM instance (scope `vsdservice`) plus the PoPP service (scope `popp`),
  the same list `zeta serve` warms. One auth identity serves every environment; each target gets its
  env's catalog, PoPP URL and ASL prod/non-prod setting, and every series carries `env`. This option
  replaces `--env` on `zeta probe` so the `ZETA_ENV` variable and the `zeta.yaml` `env:` key, which other
  commands share, stay single-valued. Probing `prod` next to non-prod environments works but shares the
  identity, HTTP client and profile database; the prober warns at startup.
- `--endpoint URL` with `--endpoint-scope SCOPE` — extra endpoints, assigned to the first `--probe-env`.
  The lists are positional: the i-th scope belongs to the i-th endpoint, and the counts must match —
  there is no default scope. `--endpoint-name SLUG` and `--endpoint-type TYPE` are positional too and
  optional as a whole; they set the `service` slug (default: the host name) and the service `type`
  (default: the slug). In the environment, all of them are whitespace- or comma-separated lists:

  ```sh
  ZETA_PROBE_ENDPOINTS="https://vsdm.example/ https://other.example/"
  ZETA_PROBE_ENDPOINT_SCOPES="vsdservice other-scope"
  ZETA_PROBE_ENDPOINT_NAMES="vsdm-test other"
  ZETA_PROBE_ENDPOINT_TYPES="vsdm other"
  ```
- `--no-catalog` — only the explicit list.
- `--popp-service-url` overrides the PoPP URL and therefore needs exactly one `--probe-env`.

### Labels

Every series and span carries `env`, `service` (the instance: `vsdm-1`, `popp`, an `--endpoint-name` or a
host name), `type` (the service kind: `vsdm`, `popp`, an `--endpoint-type`) and `endpoint` (the resource
origin). Callers add their own labels **per type** with `--type-label TYPE:KEY=VALUE` (env
`ZETA_PROBE_TYPE_LABELS`, a whitespace- or comma-separated list): criticality is a property of the
service, so one `vsdm:criticality=high` covers every VSDM instance the catalog lists, in every
environment, and alert rules can select on `criticality` instead of carrying a service mapping. Keys must
be Prometheus label names and must not be one of the built-in ones; a type without targets is warned
about once, not refused (it may appear on a later catalog refresh). Labels are meant for static metadata
such as ownership or criticality — a value that changes per run would create a new series each time.

## Sending the data

An OTLP endpoint is required: `--otlp-endpoint URL` or `OTEL_EXPORTER_OTLP_ENDPOINT` (or both
`OTEL_EXPORTER_OTLP_METRICS_ENDPOINT` and `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT`). Without one the prober
refuses to start. The transport is OTLP/HTTP with protobuf (`http/protobuf`, port 4318) over the JDK's
HTTP client; gRPC is not supported. Metrics are pushed every 30s (`OTEL_METRIC_EXPORT_INTERVAL`, in ms)
with cumulative temporality, which Prometheus-style backends need.

Authentication is a header: `--otlp-header "Authorization=Bearer …"` (repeatable) or
`OTEL_EXPORTER_OTLP_HEADERS="k=v,k2=v2"`. Everything else the OpenTelemetry Java SDK understands works
unchanged: `OTEL_EXPORTER_OTLP_CERTIFICATE`, `OTEL_EXPORTER_OTLP_CLIENT_KEY` / `_CLIENT_CERTIFICATE`
for mTLS, `OTEL_EXPORTER_OTLP_COMPRESSION=gzip`, `OTEL_RESOURCE_ATTRIBUTES`, `OTEL_TRACES_SAMPLER`.
`OTEL_METRICS_EXPORTER` / `OTEL_TRACES_EXPORTER` accept only `otlp` and `none`; anything else
(`prometheus`, `console`, …) is refused at startup.

The resource carries `service.name=zeta-probe`, `service.version`, `service.instance.id` (the `HOSTNAME`
variable, else the host name — the container or pod name) and, when exactly one environment is probed,
`deployment.environment.name`. `OTEL_RESOURCE_ATTRIBUTES` can add keys or override any of them.

A collector that is down does not stop the prober: probing continues, failed exports are logged at WARN
(throttled by the SDK), and exporting resumes when the collector is back. On shutdown the last metric
batch and all pending spans are flushed.

### Collector

The collector turns the OTLP metrics into Prometheus-style series. With `add_metric_suffixes` the unit
and type become part of the name (see the [mapping](#metrics)). `job` and `instance` come from
`service.name` and `service.instance.id` (`job="zeta-probe"`, `instance=<host or pod name>`), and
`resource_to_telemetry_conversion` copies the remaining resource attributes onto every series; the
`resource` processor drops the `telemetry.sdk.*` ones first:

```yaml
receivers:
  otlp:
    protocols:
      http:
        endpoint: 0.0.0.0:4318
processors:
  resource/drop-sdk:
    attributes:
      - { pattern: telemetry\.sdk\..*, action: delete }
  batch: {}
exporters:
  prometheusremotewrite:
    endpoint: http://victoriametrics:8428/api/v1/write
    add_metric_suffixes: true
    resource_to_telemetry_conversion:
      enabled: true
  otlp_grpc/traces:
    endpoint: tempo:4317
    tls:
      insecure: true
service:
  pipelines:
    metrics:
      receivers: [otlp]
      processors: [resource/drop-sdk, batch]
      exporters: [prometheusremotewrite]
    traces:
      receivers: [otlp]
      processors: [batch]
      exporters: [otlp_grpc/traces]
```

Validated with collector-contrib 0.161, where the gRPC exporter is `otlp_grpc`; older releases only know it as `otlp`, which 0.161 still accepts.

## Running in Docker

The repository ships a `Dockerfile` whose default command is `probe -v`. State — the profile database
with registrations and tokens — lives under `/data` (`XDG_CONFIG_HOME`), so mount a volume there. Port
8080 carries only the health endpoints:

```sh
docker build -t zeta .

docker run -d --name zeta-probe-ref \
  -p 8080:8080 \
  -v zeta-probe-ref:/data \
  -v ./secrets:/secrets:ro \
  -e ZETA_PROBE_ENV=ref \
  -e ZETA_AUTH_METHOD=p12 \
  -e ZETA_AUTH_P12_FILE=/secrets/smcb.p12 \
  -e ZETA_AUTH_P12_PASSWORD=… \
  -e ZETA_PROBE_ENDPOINTS="https://vsdm.example/" \
  -e ZETA_PROBE_ENDPOINT_SCOPES="vsdservice" \
  -e OTEL_EXPORTER_OTLP_ENDPOINT=https://collector.example:4318 \
  -e OTEL_EXPORTER_OTLP_HEADERS="Authorization=Bearer …" \
  zeta
```

The same as Compose:

```yaml
services:
  zeta-probe:
    build: .
    ports: ["8080:8080"]
    volumes:
      - zeta-probe:/data
      - ./secrets:/secrets:ro
    environment:
      ZETA_PROBE_ENV: dev,ref
      ZETA_PROBE_TYPE_LABELS: "vsdm:criticality=high popp:criticality=high"
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

The image has a built-in `HEALTHCHECK` against `/healthz` (every 30s, 5s timeout, 60s start period, three
retries), so `docker ps` shows `healthy` while the prober works and `unhealthy` when it is wedged (see
below); Compose and Swarm pick it up without extra configuration, and `depends_on: condition:
service_healthy` works. It follows `ZETA_PROBE_HEALTH_PORT`; setting that to `0` turns the endpoints off
and the container then reports `unhealthy`. The check connects to `127.0.0.1`, so keep
`ZETA_PROBE_HEALTH_HOST` at `0.0.0.0` (the default) or a loopback address. The image has no `curl`, so the check speaks HTTP through
bash's `/dev/tcp` and starts no second JVM. Plain Docker does not restart an unhealthy container by itself;
use an orchestrator or an autoheal sidecar for that.

In Kubernetes, which ignores the image's `HEALTHCHECK`:

```yaml
livenessProbe:
  httpGet: { path: /healthz, port: 8080 }
  periodSeconds: 30
readinessProbe:
  httpGet: { path: /readyz, port: 8080 }
  periodSeconds: 10
```

Health endpoints (`--health-host` / `--health-port`, env `ZETA_PROBE_HEALTH_HOST` / `_PORT`, default
`0.0.0.0` / `8080`; port `0` turns them off):
- `GET /healthz` — liveness of the prober itself: `200 ok`, or `503` with every failing check on one line
  (`refresh loop overdue by 95s; login dev/vsdm-2 running for 190s (limit 180s)`). It fails when a probe
  loop or a catalog refresh loop has stopped, when a loop has not woken up 60s after it was due (a wedged
  or starved scheduler), or when a single probe runs longer than `--probe-timeout` plus 60s (a blocking
  call that ignores the timeout). Failing targets and an unreachable collector do **not** fail liveness —
  those are what the metrics report, and restarting would not fix them.
- `GET /readyz` — `200 ready` once the initial catalog fetch is done (or `--no-catalog`), there is at
  least one target and the probe loops run; otherwise `503` with a one-line reason (`starting`,
  `no targets`). Readiness does not depend on the collector.

Notes:
- The container runs as uid `10001`. A bind mount for `/data` must be writable by that user
  (`chown 10001 ./probe-data`).
- `docker stop` sends SIGTERM; the daemon stops the health endpoints and scheduling, closes its SDK
  sessions, pushes the last metric batch and pending spans, and exits. Give it a grace period of ~20s.
- The SQLite driver unpacks a native library into `java.io.tmpdir`. With a read-only root filesystem
  mount a tmpfs at `/tmp`, or add `-Dorg.sqlite.tmpdir=/data/tmp` to `JAVA_OPTS`.
- For `--auth-method connector`, mount the `.kon` file and point `ZETA_CONNECTOR_CONFIG` at it; the
  Konnektor must be reachable from the container network.

## Metrics

All series carry `env`, `service`, `type` and `endpoint` plus any `--type-label`s (see [Labels](#labels)).
`service` is the instance slug: the catalog's instance name for a VSDM service (the name its routing table
points at, e.g. `vsdm-1`), `popp` for the PoPP service, the `--endpoint-name` or host name for an explicit
endpoint. `type` is the service kind. `endpoint` is the resource origin (`https://vsdm.example/`).

The OTLP instrument names below are the contract. The right-hand column is what a collector's
`prometheusremotewrite` exporter with `add_metric_suffixes: true` makes of them; write queries and
dashboards against those names.

| OTLP instrument | Unit | Type | Name after the collector | Labels | Meaning |
| --- | --- | --- | --- | --- | --- |
| `zeta.probe.duration` | `s` | histogram | `zeta_probe_duration_seconds_bucket` / `_sum` / `_count` | `probe`, `step`, `result` | Wall time per step (`status`, `register`, `clear`, `authenticate`, `subject_token`, `verify`) and for the whole probe (`step="total"`). `result` is `ok`/`error` per step; `ok`/`error`/`timeout` for the total. |
| `zeta.probe.runs` | `{run}` | counter | `zeta_probe_runs_total` | `probe`, `result`, `error_type`, `fallback` | Completed probes. `error_type` is the SDK's flow code when it reports one (`AUTHENTICATION_ERROR`, `REGISTRATION_FAILED_ERROR`, …), else `timeout`, `no_tokens`, or the exception class (`SocketException`); empty on success. |
| `zeta.probe.up` | — | gauge | `zeta_probe_up` | `probe` | `1` if the last probe of that kind against the endpoint succeeded, `0` otherwise. |
| `zeta.probe.last_success.time` | `s` | gauge | `zeta_probe_last_success_time_seconds` | `probe` | Unix time of the last successful probe of that kind. |
| `zeta.probe.last_run.time` | `s` | gauge | `zeta_probe_last_run_time_seconds` | `probe` | Unix time of the last probe of that kind, whatever its result. |
| `zeta.probe.backoff.until.time` | `s` | gauge | `zeta_probe_backoff_until_time_seconds` | `probe` | Unix time until which a failing target is held off; present only while it is. |
| `zeta.probe.token.expiry.time` | `s` | gauge | `zeta_probe_token_expiry_time_seconds` | — | Unix time at which the endpoint's current access token expires. |
| `zeta.probe.catalog.age` | `s` | gauge | `zeta_probe_catalog_age_seconds` | `env` only | Age of the service-discovery catalog copy the target list is built from; absent until a catalog was obtained. |
| `zeta.probe.targets` | — | gauge | `zeta_probe_targets` | `env` only | Endpoints currently in the schedule, per environment. |

Useful alerts:
- `zeta_probe_up == 0` for longer than two intervals.
- `increase(zeta_probe_runs_total{probe="refresh",fallback="true"}[1h]) > 0` — the refresh grant is broken.
- `histogram_quantile(0.95, rate(zeta_probe_duration_seconds_bucket{step="subject_token"}[15m]))` —
  the Konnektor's signing latency.
- The prober stopped pushing (three missed 30s exports) — with a push model this replaces the scrape's
  `up` series. In VictoriaMetrics (MetricsQL): `lag(zeta_probe_targets[1d]) > 90`; in Prometheus:
  `absent_over_time(zeta_probe_targets{job="zeta-probe"}[90s])`.

## Traces

Each probe is one trace: a root span `zeta.probe.login` / `zeta.probe.refresh` (attributes
`zeta.env`, `zeta.service`, `zeta.type`, `zeta.endpoint`, `zeta.scopes`, `zeta.probe`, the
`--type-label`s by their own names, `zeta.result`, `zeta.fallback`, `zeta.registered`,
`zeta.status.before` / `zeta.status.after`) with children `probe.status`, `probe.register` (bootstrap
only), `probe.clear`, `sdk.authenticate` → `sdk.subject_token` (full exchange only), and `probe.verify`.
Failed steps carry the exception.

The CLI's own `--trace` (the in-process span tree printed at exit) is refused by `zeta probe`: a
process that never exits would grow that tree forever.

## What the data looks like

Both samples below are from a run against the `dev` catalog with a PKCS#12 identity and
`--type-label vsdm:criticality=high --type-label popp:criticality=high`, pushed through the collector
configuration above into VictoriaMetrics.

Series as the backend stores them (histogram buckets omitted; every series also carries
`service_name`, `service_version`, `service_instance_id`, `deployment_environment_name` and
`otel_scope_name`, left out here):

```
target_info{job="zeta-probe",instance="zeta-probe-smoke",deployment_environment_name="dev",service_version="0.15.0"} 1
zeta_probe_targets{job="zeta-probe",instance="zeta-probe-smoke",env="dev"} 5
zeta_probe_catalog_age_seconds{job="zeta-probe",instance="zeta-probe-smoke",env="dev"} 89
zeta_probe_up{job="zeta-probe",instance="zeta-probe-smoke",env="dev",service="popp",type="popp",criticality="high",endpoint="https://popp.dev.poppservice.de/",probe="refresh"} 1
zeta_probe_up{job="zeta-probe",instance="zeta-probe-smoke",env="dev",service="vsdm-1",type="vsdm",criticality="high",endpoint="https://vsdm-dev.tk.de/",probe="login"} 0
zeta_probe_runs_total{…,service="vsdm-3",type="vsdm",probe="refresh",result="ok",error_type="",fallback="false"} 2
zeta_probe_runs_total{…,service="vsdm-3",type="vsdm",probe="refresh",result="ok",error_type="",fallback="true"} 1
zeta_probe_runs_total{…,service="vsdm-1",type="vsdm",probe="login",result="error",error_type="AUTHENTICATION_ERROR",fallback="false"} 1
zeta_probe_runs_total{…,service="vsdm-2",type="vsdm",probe="refresh",result="error",error_type="REGISTRATION_FAILED_ERROR",fallback="false"} 1
zeta_probe_runs_total{…,service="vsdm-4",type="vsdm",probe="login",result="error",error_type="SocketException",fallback="false"} 1
zeta_probe_duration_seconds_count{…,service="vsdm-3",probe="login",step="subject_token",result="ok"} 1
zeta_probe_last_success_time_seconds{…,service="vsdm-3",probe="refresh"} 1790350379
zeta_probe_token_expiry_time_seconds{…,service="vsdm-3"} 1790350679
```

One `login` trace as the collector's debug exporter shows it, attributes abbreviated (resource
`service.name=zeta-probe`, `service.version=0.15.0`, `service.instance.id`,
`deployment.environment.name=dev`; scope `de.gematik.zeta.cli.probe`):

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

Failures and fallbacks are logged at WARN, recoveries, bootstraps and the expected full exchange of the
first refresh after a bootstrap at INFO, and every successful probe at DEBUG — so `-v` shows the
interesting events and `-vv` every probe. A failed OTLP export is one WARN line per failure burst (the
SDK throttles it); its stack trace is at DEBUG. Skipped ticks of a held-off target are logged at DEBUG. `-vv` also shows the SDK's own
lines (`Access token expired, fetching refresh token` on a refresh probe, `No refresh token found,
getting new access token` on a login probe), which is the quickest way to confirm which flow ran.

## Options

See the [`zeta probe` options table](../README.md#zeta-probe) in the README. Everything is also
settable through `zeta.yaml`.

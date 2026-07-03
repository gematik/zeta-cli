# ZETA Guard load-test findings

**The login ceiling is the box, not the code.** A fleet load test of full SMC-B
re-logins plateaus at **~2,300 req/min** and won't budge with more client
concurrency. The cause isn't a lock, a policy gate, or the HSM — it's a 2-core
node running at 99% CPU.

| | |
| --- | --- |
| Target | `zeta-plain.t20r.cloud` |
| Scenario | login-storm (cold re-login) |
| Tool | `zeta-stress` (branch `stress-test-harness`) |

| Metric | Value |
| --- | --- |
| Sustained full-login ceiling | **~2,300/min (~38 req/s)** |
| Node CPU at the ceiling | **99% — 1,996m / 2,000m** |
| Cores the whole stack shares | **2 — the root cause** |

## 1. What we set out to find

Can ZETA Guard hold the 5,000 req/min target for a thundering-herd re-login, and
if not, what's the limit?

We drove a fleet of 200 registered SMC-B clients (distinct identities) at the auth
server and ramped the request rate until it broke. Every ramp hit the same wall:
throughput flattens, latency climbs, errors stay near zero. That signature — flat
throughput as you add concurrency — means a single shared resource is serializing
the work. This report is the hunt for which one.

## 2. The harness, extended

New capabilities added to `zeta-stress` to make this measurable:

- **Soak** — sustain a target rate for a duration, re-expiring each client so
  every op is a real token-endpoint hit.
- **Ramp to breaking point** — step the rate up until the trailing-window failure
  rate or p99 crosses a threshold; report the peak healthy rate.
- **Live progress + export** — a live throughput/latency panel, and per-attempt
  CSV/JSON export.

We fixed a bottleneck in the harness itself first, so we'd be measuring the server
and not our own client. The stress DB moved from one connection behind a global
lock to a **WAL connection pool**:

| Metric (warm 200-client fleet) | Single conn | Pooled | Δ |
| --- | ---: | ---: | ---: |
| Low-load latency (p50) | ~640 ms | ~400 ms | −35% |
| p99 at ~2,000/min | ~1,250 ms | ~640 ms | −49% |
| Login-storm peak healthy | 2,019/min | 2,360/min | +17% |

Real, but small — and the throughput plateau barely moved. The wall was
downstream, on the server.

## 3. The bottleneck hunt

Every "usual suspect" for a serialized auth path — eliminated in turn.

| Status | Suspect | Verdict |
| --- | --- | --- |
| **Fixed** | Harness storage lock | Single-connection SQLite store serialized our own client. The WAL pool removed it — needed to push the server hard enough to saturate it, but not the ceiling. |
| Ruled out | OPA policy gate | Deployed but serving an allow-all inline policy, and lock-free in code. Measured at 17–30m CPU under load — under 1.5% of a core. |
| Ruled out | OCSP revocation | Off on this deploy (responder DNS doesn't resolve); soft-fails and caches per-cert with no lock held across the lookup. |
| Ruled out | HSM token signing | No `hsm-sim` pod exists — signing is software (sub-ms, parallel). A tempting single-serialized-backend theory, but not this deployment. |
| **Root cause** | CPU on a 2-core node | The entire stack — Keycloak, Postgres, both OPA pods, the PEP proxy, ingress — shares one 2-core, 3.8 GB box. Under load the node pegs at 99%. |

## 4. The proof — where the 2 cores go

Node at **1,996m / 2,000m (99%)** under saturating load:

| Component | CPU under load | Share of node |
| --- | ---: | ---: |
| authserver (Keycloak) | 1,408m | 70% |
| keycloak-db (Postgres) | 230m | 12% |
| opa + opa-simulation | 55m | 3% |
| k8s / proxy / kernel | ~300m | 15% |
| **Node total** | **1,996m / 2,000m** | **99%** |

The correlation is exact: at the instant the node hit 99% CPU, the harness
reported **38 req/s (2,283 req/min)** — the same plateau seen at client
concurrency 150 *and* 300. More client threads only deepen the queue; they can't
add throughput to a node that's already full. Keycloak's per-login CPU
(brainpool-EC subject-token verify, JWT signing, JSON/TLS, and the Postgres writes
it drives) dominates at ~1.4 cores.

> **Why the refresh path is faster** — refresh-churn peaked at ~4,180/min
> (~70 req/s), roughly 2× the cold-login rate, because it skips the subject-token
> verify, the ASL handshake, and the user import. Same CPU wall, cheaper work per
> request.

## 5. Conclusion & path to 5,000/min

The ceiling is a sizing limit, not a ZETA Guard software defect. Full re-logins top
out at **~2,300/min on this 2-core test box**, and every request succeeded right up
to the wall — the server stays correct, it just runs out of CPU. To reach the
5,000/min target:

- **Give the stack more cores**, and put Keycloak and Postgres on their own nodes
  so they stop competing for the same 2 cores.
- **Expect ~linear scaling with Keycloak's real cores.** At ~1.4 core-equivalents
  → 2,300/min, clearing 5,000/min needs on the order of **3 dedicated Keycloak
  cores**, plus Postgres headroom.
- **Watch the next limits.** Postgres is capped at 1 core, and RAM is tight
  (3.8 GB total, ~1.2 GB free; authserver already at 947 Mi of its 1,536 Mi cap).
  One of those bites next.

An easy confirmation: resize the node and re-run the ramp below — the peak healthy
rate should climb with the core count.

## 6. Reproduce

```sh
# warm 200-client fleet, then ramp to the breaking point
zeta-stress run --db push.db \
  --resource https://zeta-plain.t20r.cloud --scope zeta \
  --scenario login-storm --cohort 200 --concurrency 150 \
  --ramp --ramp-start 2000 --ramp-step 2000 --ramp-step-interval 6 \
  --rate 30000 --max-p99 3000 --max-fail-pct 10 \
  --csv ramp.csv

# on the server, watch the node saturate
kubectl -n zeta-guard top pod
```

---

Measured on a single-node k3s cluster (2 vCPU, 3.8 GB) hosting the full
`zeta-guard` namespace. Load driven from `zeta-stress` with a WAL-pooled per-client
SDK store. Findings are specific to this test deployment's sizing.

# zeta-cli — project overview

A Kotlin/JVM command-line client for gematik **Zeta Guard** protected resources, built on the
Zeta SDK. Authenticates via SMC-B (Konnektor or PKCS#12), handles OAuth/DPoP token flows,
and drives the popp WebSocket service for proof-of-patient-presence tokens.

## Repository layout

```
zeta-cli/
├── connector/            Konnektor SOAP client (generated DTOs + handwritten facade)
├── cli/                  Clikt application — entry point, commands, output, config
├── buildSrc/             3 convention plugins (common / library / application)
├── gradle/libs.versions.toml   single source of truth for dependency versions
├── settings.gradle.kts   include("connector", "cli")
├── Formula/zeta.rb       Homebrew formula
├── zeta-dev              dev wrapper around `:cli:run`
├── justfile              convenience tasks (regen connector, etc.)
└── CLAUDE.md             project-specific guidance for Claude Code
```

## Lines of code

| Module | Hand-written `main` | `test` | Notes |
|---|---:|---:|---|
| `connector` | 1,312 | 751 | Hand-written facade. **Plus generated SOAP DTOs** under `de/gematik/connector/api/`. |
| `cli` | 3,671 | 253 | Application code. Owns the `inspect` wire types + fetch functions. |
| **total non-generated** | **4,983** | **1,004** | ~6k Kotlin we own. |
| Generated DTOs | (~18k) | — | XML-schema-driven. Regenerate via `just generate-connector`. |

### Top files (excluding generated)

| LOC | File | Concern |
|---:|---|---|
| 540 | `connector/.../ConnectorClient.kt` | High-level Konnektor SOAP facade |
| 353 | `cli/.../http/CurlieLogging.kt` | Wire log + JWT-aware reformatter |
| 311 | `cli/.../client/ZetaSessionCommand.kt` | Auth-flow base for `http` / `ws` / `popp` |
| 243 | `connector/.../ServiceDiscovery.kt` | SDS XML model + lookup |
| 201 | `cli/.../connector/ConnectorInspectCommand.kt` |
| 192 | `cli/.../popp/PoppConnectorCommand.kt` |
| 191 | `connector/.../Dotkon.kt` | `.kon` config model + env substitution |
| 159 | `cli/.../client/WsCommand.kt` |
| 143 | `cli/.../storage/JsonFileStorage.kt` |
| 138 | `cli/.../client/HttpCommand.kt` |

## `cli` package map

| Package | Files | Concern |
|---|---:|---|
| `cli/client/` | 9 | Auth orchestration, lazy session, token providers, header parsing |
| `cli/connector/` | 8 | `zeta connector …` commands + `.kon` resolution |
| `cli/` (root) | 6 | `Main`, `ZetaCommand`, `ZetaCliktCommand`, `CliConfig`, `Logging`, `VersionCommand` |
| `cli/popp/` | 4 | popp WebSocket flow, sealed `PoppMessage` model |
| `cli/output/` | 4 | JSON / XML / sections renderers, output enum |
| `cli/storage/` | 2 | XDG paths + atomic JSON file storage |
| `cli/http/` | 2 | Wire logger + custom HTTP client factory |
| `cli/config/` | 2 | YAML config-file value source + path discovery |
| `cli/term/` | 1 | TTY detection for stderr coloring |
| `cli/inspect/` | 4 | `zeta inspect URL` — command + RFC 8414/9728 wire models + two suspend fetch fns |

## Module dependencies

```
connector ──► ktor-client-core (api)
              │ xmlutil-serialization, bouncycastle-bcpkix
              │ ktor-client-okhttp (compileOnly — JSSE engine bridge)
              │ kotlinx-{serialization-json, coroutines}, kotlin-logging

cli ──► connector, zeta-sdk
        │ clikt, logback-classic, kotlin-logging
        │ ktor-{okhttp, logging, core}, kotlinx-serialization-json
        │ snakeyaml (zeta.yaml), jna (isatty)
```

`connector` deliberately stays framework-free (no Clikt, no Logback) so it's consumable by
anything that brings its own `HttpClient` and SLF4J binding. Only `cli` pulls the
application stack.

## Library versions (`gradle/libs.versions.toml`)

| Group | Version | Purpose |
|---|---|---|
| Kotlin | 2.3.0 | Toolchain (JDK 21) via `kotlin("jvm")` + `plugin.serialization` |
| Clikt | 5.1.0 | CLI framework — commands, options, value sources |
| Ktor | 3.4.3 | HTTP/WS client (CIO + OkHttp engines, logging plugin) |
| kotlinx-coroutines | 1.10.2 | Suspend / `runBlocking` throughout |
| kotlinx-serialization | 1.8.0 | JSON + sealed-interface polymorphism (popp messages) |
| Logback | 1.5.16 | SLF4J binding |
| kotlin-logging | 7.0.7 | Kotlin-idiomatic SLF4J facade |
| xmlutil | 0.91.1 | XML serializer for SOAP envelopes |
| BouncyCastle | 1.83 | Cert chain + ISIS-MTT admission-extension parsing |
| SnakeYAML | 2.5 | `zeta.yaml` config parser |
| JNA | 5.14.0 | `isatty(2)` call for stderr-color detection |
| zeta-sdk | `latest` | mavenLocal pin (commented `includeBuild` in `settings.gradle.kts`) |

## Build conventions (`buildSrc`)

Three precompiled Kotlin DSL plugins; each module applies exactly one:

- `buildlogic.kotlin-common-conventions` — Kotlin JVM, JDK 21 toolchain, Maven Central +
  mavenLocal, JUnit Jupiter, dependency-version constraints.
- `buildlogic.kotlin-library-conventions` — common + `java-library` (`connector`).
- `buildlogic.kotlin-application-conventions` — common + `application` (`cli`).

`cli` additionally code-generates `BuildConfig.kt` at build time, stamping `VERSION` and
`ZETA_SDK_VERSION` so `zeta version` reports them.

## Architecture sketch

```
            ┌────────── zeta CLI ─────────┐
            │  Main → ZetaCommand         │
            │   ├─ version                │
            │   ├─ inspect                │
            │   ├─ http      ┐  extends ZetaSessionCommand
            │   ├─ ws        ┤  ─► openSession(resource, scopes) { ... }
            │   ├─ popp connector ┘
            │   ├─ connector inspect      │
            │   ├─ connector configs      │
            │   └─ connector get cards    │
            └─────────────────────────────┘
                        │
                        ▼
                   :connector
              ConnectorClient ──► generated SOAP DTOs
              Dotkon (.kon, env subst)
              ServiceDiscovery (SDS)
              CardService 8.2.1 / EventService / AuthSignature / …
                                          │
                                          ▼
                                 Konnektor (SOAP, OkHttp+JSSE, mTLS)

           ZetaSdk (de.gematik.zeta:zeta-sdk-jvm)
            – AccessTokenProvider (cached, 10s skew, refresh-token aware)
            – LazySubjectTokenProvider (cli-side wrapper)
                ↳ resolves SMC-B handle on first createSubjectToken()
                ↳ delegates to SmcbTokenProvider → ConnectorTokenProvider
                                                  → ConnectorClient
```

### Lifecycle (lazy by design)

A typical `zeta http GET …` invocation with valid cached SDK tokens makes **zero**
Konnektor round trips:

1. `openSession` resolves the `.kon` and builds the OkHttp engine (cheap; no SOAP yet).
2. `ZetaSessionCommand.buildTokenProvider` returns a `LazySubjectTokenProvider`.
3. The SDK reads its cached access token from `JsonFileStorage` (keyed by `resource`).
4. The cached token is valid → `createSubjectToken` is never called → the lazy provider's
   factory never runs → `ConnectorSession.connector()` is never invoked → no SDS load,
   no `GetCards`, no `ReadCardCertificate`.

Only when tokens are expired without a refresh token does the chain fully activate, ending
in a single `ExternalAuthenticate` round trip.

## Configuration sources

In Clikt's standard precedence (high → low):

1. **CLI flag** — `--auth-connector-telematik-id=…`
2. **Environment variable** — most options expose one (e.g. `ZETA_AUTH_CONNECTOR_TELEMATIK_ID`)
3. **Config file** — `./zeta.yaml`, then `$XDG_CONFIG_HOME/telematik/zeta/zeta.yaml`
4. **Option default**

The YAML files support `${VAR}` substitution via the **same regex and missing-variable
behaviour** that `.kon` parsing uses (single implementation in
`de.gematik.connector.expandEnvVars`). Cwd file overrides XDG file via Clikt's
`ChainedValueSource`.

## State persistence

- **SDK state** (registration, access + refresh tokens, ASL keys): `JsonFileStorage` at
  `$XDG_CONFIG_HOME/telematik/zeta/<profile>.storage.json`. Atomic writes, mode 0600 on
  POSIX. The Zeta SDK keys entries by `<resource>` hash so different services / profiles
  don't collide.
- **`.kon` configs**: hand-edited at `$XDG_CONFIG_HOME/telematik/kon/<name>.kon`. Cwd takes
  precedence; `--connector-config` selects by short name.
- **CLI config**: `zeta.yaml` (cwd or XDG; same path layout as `.kon`).

XDG paths follow Linux conventions on every OS, including macOS — the same files work
across platforms without symlinks or platform-specific code.

## Observations

- **Hand-written / generated split ≈ 1 : 3.6**. Connector is small in spirit (1.4k
  hand-written) but the generated SOAP surface dominates total bytes.
- **No circular deps**: `connector` ← `cli`. SDK depends on nothing from us.
- **Single application module** — `cli` is the only one that produces a distribution
  (`./gradlew :cli:installDist`).
- **Testing density**: connector ≈ 57%, cli ≈ 7%. The cli's coverage focuses on
  `JsonFileStorage` and `PoppMessages` round-trip — areas where regression risk
  outweighs the tooling cost.
- **Version catalog is exhaustive** — every dep is named in `libs.versions.toml`;
  modules reference via `libs.foo`. Single-place version bumps.
- **`zeta-sdk = "latest"`** is the only dynamic pin; it resolves only against `mavenLocal`.
  CI builds therefore need the SDK pre-installed; the commented `includeBuild` in
  `settings.gradle.kts` is the alternative when working on both repos side-by-side.

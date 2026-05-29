# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Run

Gradle multi-project build using the wrapper. All commands run from the repo root.

- Build everything: `./gradlew build`
- Run the CLI: `./gradlew :cli:run --args="version"` (forward args via `--args`)
- Faster dev wrapper: `./zeta-dev <args>` — forwards args verbatim through `:cli:run`, preserving spaces and quotes (e.g. `./zeta-dev describe endpoint https://example.com`)
- Build a runnable distribution: `./gradlew :cli:installDist` (script at `cli/build/install/zeta/bin/zeta`)
- Build the release tarball: `./gradlew :cli:distTar` → `cli/build/distributions/zeta-<version>.tar.gz`
- Run all tests: `./gradlew test`
- Single module: `./gradlew :connector:test`
- Single test class: `./gradlew :cli:test --tests "de.gematik.zeta.cli.SomeTest"`
- Clean: `./gradlew clean`

JDK 21 toolchain is enforced via `buildSrc` conventions; the foojay-resolver plugin auto-provisions a JDK if needed. Configuration cache is enabled (`org.gradle.configuration-cache=true`).

The version is set via `gradle.properties` (`version=…`) and propagated to subprojects in the root `build.gradle.kts`. The CLI prints it via a generated `de.gematik.zeta.cli.BuildConfig` object — see the `generateBuildConfig` task in `cli/build.gradle.kts`. To override at build time: `-Pversion=1.2.3`.

## Architecture

```
cli (application, de.gematik.zeta.cli)  ──►  connector (library, de.gematik.connector)
                                        ──►  de.gematik.zeta:zeta-sdk-jvm
```

- **`cli`** — `de.gematik.zeta.cli`. Clikt-based CLI. Entry point is `de.gematik.zeta.cli.MainKt#main` (see `cli/build.gradle.kts`, `applicationName = "zeta"`). Owns the Ktor engine (`ktor-client-okhttp` — single engine for the whole CLI; CIO is not on the classpath) and `HttpClient` lifecycle. Depends directly on `de.gematik.zeta:zeta-sdk-jvm`; the SDK transitively pulls `slf4j-simple`, which is excluded in `cli/build.gradle.kts` so it doesn't clash with our Logback binding.
- **`connector`** — see `connector/CLAUDE.md`. Hand-written facade over the generated Konnektor SOAP API.

### CLI commands

Wired in `Main.kt` via Clikt's `subcommands(...)`. Each top-level command lives in its own subpackage (or directly in `de.gematik.zeta.cli` for one-off verbs).

| Command | Package | Purpose |
| --- | --- | --- |
| `zeta version` | `cli` | Print build version. |
| `zeta discover URL` | `lifecycle` | Run the SDK's `discover()` flow against the resource origin (RFC 9728 — host root, no sub-path). Persists protected-resource + AS metadata under the active profile and renders everything the SDK extracts. |
| `zeta status [URL]` | `state` | Read-only view of cached SDK state for one or every resource in the profile. No SDK build, no auth options shown. `--reveal` exposes raw access JWS, `client_secret`, `registration_access_token`. Refresh tokens never emitted. |
| `zeta register URL` | `lifecycle` | DCR — register with the AS, persist response. |
| `zeta authenticate URL --scope …` | `lifecycle` | Token exchange — get access + refresh, persist. |
| `zeta login URL --scope …` | `lifecycle` | Idempotent `register` + `authenticate`; prints a small trace of which steps ran vs were skipped. |
| `zeta logout URL` | `lifecycle` | SDK `logout()` — revoke + clear tokens locally, keep registration. |
| `zeta forget [URL]` / `--all` | `lifecycle` | Per-URL: drive `ZetaSdk.forget(client)` via [NoopSubjectTokenProvider] (auth options not required). `--all`: delete profile storage file. Interactive confirmation, suppressed by `--force`; non-TTY without `--force` refuses. |
| `zeta http URL` / `zeta ws URL` | `client` | Bearer-bound HTTP / WebSocket via the SDK's authenticated client. |
| `zeta connector …` | `connector` | Talk to a Konnektor described by a `.kon` file. |
| `zeta popp …` | `popp` | Retrieve a Proof-of-Patient-Presence token. |

Command-base hierarchy:

```
ZetaCliktCommand            // sticky -v, --output-format, --connect-timeout, --insecure, --ca-cert, …
  ZetaProfileCommand        // adds --profile; helpers loadEntry / renderEntry. Used by discover / status / forget.
    ZetaSessionCommand      // adds Connector + PKCS#12 auth groups; openSession(...). Used by register / authenticate / login / logout / http / ws.
```

**All commands extend `ZetaCliktCommand`** (or one of its subclasses), never `CliktCommand` directly — this is what gives them the sticky options. Override `runCommand()`, not `run()` (the base makes `run` final and calls `Logging.applyVerbosity` before dispatching). Help text goes in `override fun help(context: Context)` (Clikt 5 moved help out of the constructor).

### SDK builder + read-only stub

`sdk/SdkBuilder.kt` exports `buildZetaSdkClient(...)` — the single place that constructs a `ZetaSdkClient` with the CLI-wide defaults (product id, JsonFileStorage, software attestation, ZETA Guard role OID). Both `ZetaSessionCommand.buildSdk` and the auth-free commands (`discover`, `forget URL`) call this helper.

`NoopSubjectTokenProvider` is the stub used for SDK operations that never call `createSubjectToken` — `discover()`, `forget()`. If the SDK ever does invoke it, the stub `error()`s loudly rather than hanging or returning empty.

### State / enumeration

`state/ProfileEnumeration.kt` walks a profile's `JsonFileStorage` via the public SDK storage interfaces (`ConfigurationStorage`, `ClientRegistrationStorage`, `AuthenticationStorage`) and builds an `Entry` per cached resource. `Entry` carries the linked AS, the [SdkStatus] enum, decoded access-token claims, and a `RegistrationInfo` view of `ClientRegistrationResponse`.

The status enum is computed to match the SDK's own `status()` as of zeta-sdk 1.0.1: tokens are looked up by the build-time resource URL (`https://$fqdn/`), matching what `AccessTokenProviderImpl` saves under. (Pre-1.0.1 SDKs had a bug where `status()` keyed by `authServer.issuer` instead, so the enum always under-reported — the CLI used to mirror that bug; both are now fixed.)

`state/EntryRenderer.kt` exposes `renderEntryText` / `renderEntryJson` — `status` uses both directly; lifecycle commands use them via `ZetaProfileCommand.renderEntry`.

### HTTP client wiring

The CLI owns the `HttpClient`. `de.gematik.zeta.cli.http.createHttpClient(...)` builds a Ktor OkHttp client with the `HttpTimeout` plugin and an optional custom `X509TrustManager` for TLS. OkHttp was chosen over CIO because (a) CIO's TLS stack hard-codes `RSA`/`DSS` cert types, silently dropping *all* EC client certs in mTLS regardless of curve — see the connector module CLAUDE.md for the full story — and (b) CIO doesn't preemptively send `Proxy-Authorization` on `CONNECT`, which causes `Connection reset` against authenticating corporate proxies. The HTTP options are sticky — declared on `ZetaCliktCommand` so they accept anywhere. Each command merges its parsed values into the shared `CliConfig` on the Clikt context, and the `HttpClient` is created lazily on first access:

- `--connect-timeout SECONDS` / `--request-timeout SECONDS` — Ktor `HttpTimeout` plugin.
- `-k/--insecure` — installs an all-accepting `X509TrustManager` (disables TLS verification entirely; logs a WARN). Use only for dev/test.
- `--ca-cert FILE` (repeatable) — adds PEM-encoded CA certs to the trust store on top of the default JVM roots. Use this for corporate/self-signed CAs when `curl` works but the JVM doesn't trust the cert (typical macOS Temurin behaviour: Keychain isn't on the JVM trust path).

Subcommands that need HTTP read the shared client via `cliConfig.httpClient` and bridge to suspend code with `runBlocking`. Don't create a new `HttpClient` in a command — reuse the shared one so timeouts/proxies/headers stay consistent.

### Logging & verbosity

`cli/src/main/resources/logback.xml` configures Logback: stderr appender, default level **WARN**, coloured pattern (`%highlight` + `%gray`). Libraries log via `io.github.oshai:kotlin-logging` (`private val log = KotlinLogging.logger {}`); both `cli` and `connector` depend on it.

The `-v/--verbose` option on `ZetaCliktCommand` is `counted()`, so it's sticky — accepted at any depth (`zeta -v status`, `zeta status -v` are both valid). Each `-v` increments: 1 → INFO, 2 → DEBUG, 3+ → TRACE. `Logging.applyVerbosity` is no-op at count 0, so an unspecified `-v` on a deeper command does **not** reset a level set higher up. To globally raise verbosity in tests or dev, prefer `-v` on the root.

### Tracing

`--trace` (env: `ZETA_TRACE=1`) turns on an in-process span tracer (`cli/src/main/kotlin/de/gematik/zeta/cli/trace/`). At end of command, the collected tree prints to stderr via the `de.gematik.zeta.trace` Logback logger (pinned to INFO so it shows regardless of `-v`). When the flag is absent, the tracer is a no-op — every `Tracer.span(...)` short-circuits to `block(NoopSpan)` with zero allocation.

Activation is pre-detected in `Main.kt` by sniffing `args` for `--trace` (and the `ZETA_TRACE` env var) before Clikt parses anything, so the root span is open before the first subcommand runs. The same `--trace` option is also declared on `ZetaCliktCommand` for help-text + value-source completeness.

Span hierarchy:

```
cli.run
├─ sdk.init                          (in buildZetaSdkClient)
├─ sdk.session                       (in ZetaSessionCommand.openSession; wraps the auth flow)
│  ├─ connector.<op>                 (one per call via ConnectorSession.traced helper)
│  │  └─ http.request                (Ktor plugin on the connector HttpClient)
│  └─ popp.connect
│  ├─ popp.ws.recv  type=…           (sibling of popp.connect, child of sdk.session)
│  ├─ popp.ws.send  type=…
│  └─ connector.secureSendApdu       (one per APDU round)
```

WS message spans are deliberately siblings of `popp.connect`, not children — long-lived connections would otherwise produce an unreadably-deep nest. `PoppClient` takes a `wsSpanParent: Span?` and uses `Tracer.spanUnder(parent, …)` to bypass the ThreadLocal parent lookup.

To wrap a new connector call: `session.traced("opName") { connector().yourCall() }`. To wrap a new SDK / HTTP call inside a command, use `Tracer.span("name") { … }` (blocking) or `Tracer.spanSuspend("name") { … }` (inside a coroutine). Cross-`runBlocking` parent propagation works because `Tracer.spanSuspend` uses `ThreadLocal.asContextElement` to restore the parent on every coroutine dispatch.

### CLI output styling

Two reusable renderers live in `de.gematik.zeta.cli.output`:

- **`renderSections(theme, colorize) { section("...") { field(label, value); field(label, listOfValues) } }`** — Kotlin DSL for `Label: Value` layouts. Section titles render bold yellow; labels bold cyan; values plain. Multi-value fields wrap continuation lines aligned under the value column. Per-section auto-padding picks the longest `label:` width so columns align.
- **`renderJson(element, theme, colorize)`** — pretty-prints a `kotlinx.serialization.json.JsonElement` with bat/Helix-style highlighting (keys bold cyan, strings green, numbers red, booleans yellow, null muted). When `colorize = false` it falls through to `Json { prettyPrint = true }` — clean, parseable JSON.

Every command supports `-o, --output-format text|json` (sticky on `ZetaCliktCommand`, default `text`). Commands branch on `cliConfig.outputFormat`. For colour control, pass `currentContext.terminal.terminalInfo.outputInteractive` as the `colorize` flag — true on a TTY, false when piped/redirected, so `… -o json | jq` always gets plain JSON. Help text and Clikt errors keep Mordant's default colour treatment.

Output models that need `-o json` should be `@Serializable` and live next to the command that owns them. The command calls `Json.encodeToJsonElement(Foo.serializer(), value)` and hands the result to `renderJson`. When the SDK already provides a `@Serializable` model (e.g. `ProtectedResourceMetadata`), reuse it — `DiscoverCommand` does this rather than maintaining a parallel CLI-side schema.

### Convention plugins (`buildSrc`)

Shared build logic lives in `buildSrc/src/main/kotlin/` as precompiled Kotlin DSL plugins. Module build files apply one of these instead of repeating config:

- `buildlogic.kotlin-common-conventions` — Kotlin JVM, Maven Central, JUnit Jupiter on the test classpath, JDK 21 toolchain, dependency version constraints.
- `buildlogic.kotlin-library-conventions` — common + `java-library` (`api`/`implementation` split). Used by `connector`.
- `buildlogic.kotlin-application-conventions` — common + `application` (provides `run` + `installDist`). Used by `cli`.

Shared library versions live in `gradle/libs.versions.toml` (the `libs` version catalog is shared between the main build and `buildSrc` via `buildSrc/settings.gradle.kts`). Pin new third-party versions there. Cross-module dependency-version constraints (e.g. transitive pins) belong in `buildlogic.kotlin-common-conventions`.

To add a new module: create `<module>/build.gradle.kts` applying the right convention, then add it to `include(...)` in the root `settings.gradle.kts`.

## CI & release

GitHub Actions workflows in `.github/workflows/`:

- **`ci.yml`** — runs on `push` to `main` and on PRs. Installs Temurin 21, runs `./gradlew build`, then smokes the CLI via `:cli:installDist` + `zeta version`.
- **`release.yml`** — runs on `v*` tags. Derives version from the tag (`v1.2.3` → `1.2.3`), builds `:cli:distTar` with `-Pversion=…`, attaches the `.tar.gz` + `.sha256` to a GitHub release, then bumps `Formula/zeta.rb` in the `gematik/homebrew-tap` tap repo via `mislav/bump-homebrew-formula-action`.

Cutting a release:

1. Bump `version` in `gradle.properties` if you want a non-snapshot default for local builds (optional — the workflow overrides via `-P`).
2. `git tag v1.2.3 && git push origin v1.2.3`.
3. The workflow publishes the release and opens/commits the formula bump in the tap.

The Homebrew tap-bump step needs a repo-scoped PAT for the tap repo, stored as the `HOMEBREW_TAP_TOKEN` secret on this repo. Pre-release versions (containing `-`, e.g. `1.2.3-rc1`) skip the formula bump.

## Homebrew

`Formula/zeta.rb` is the canonical formula. The release workflow keeps the copy in the `gematik/homebrew-tap` tap in sync; this file is the source of truth for non-version-bump changes (deps, install logic, tests). To mirror the bump into a second tap (e.g. a personal one) after a release is cut, run `just publish-brew <owner>` — it pushes the same formula into `<owner>/homebrew-tap`.

The formula installs the gradle distribution into `libexec/`, then writes a wrapper at `bin/zeta` via `write_env_script` that pins `JAVA_HOME` to Homebrew's `openjdk@21` (overridable by the user). The tarball produced by `:cli:distTar` has a single `zeta-<version>/` top-level dir, which Homebrew strips on stage — so the `Dir["*"]` glob in `def install` picks up `bin/` and `lib/` cleanly. Don't change the dist layout without updating the formula.

To install from the tap once the tap repo exists:

```
brew tap gematik/tap
brew install zeta
```

To test the formula locally before a release: download or build the tarball, update `url` to a `file://` path, recompute `sha256` (`shasum -a 256 zeta-<version>.tar.gz`), then `brew install --build-from-source ./Formula/zeta.rb`.

# CLAUDE.md

Behavioral guidelines to reduce common LLM coding mistakes. Merge with project-specific instructions as needed.

**Tradeoff:** These guidelines bias toward caution over speed. For trivial tasks, use judgment.

## 1. Think Before Coding

**Don't assume. Don't hide confusion. Surface tradeoffs.**

Before implementing:
- State your assumptions explicitly. If uncertain, ask.
- If multiple interpretations exist, present them - don't pick silently.
- If a simpler approach exists, say so. Push back when warranted.
- If something is unclear, stop. Name what's confusing. Ask.

## 2. Simplicity First

**Minimum code that solves the problem. Nothing speculative.**

- No features beyond what was asked.
- No abstractions for single-use code.
- No "flexibility" or "configurability" that wasn't requested.
- No error handling for impossible scenarios.
- If you write 200 lines and it could be 50, rewrite it.

Ask yourself: "Would a senior engineer say this is overcomplicated?" If yes, simplify.

## 3. Surgical Changes

**Touch only what you must. Clean up only your own mess.**

When editing existing code:
- Don't "improve" adjacent code, comments, or formatting.
- Don't refactor things that aren't broken.
- Match existing style, even if you'd do it differently.
- If you notice unrelated dead code, mention it - don't delete it.

When your changes create orphans:
- Remove imports/variables/functions that YOUR changes made unused.
- Don't remove pre-existing dead code unless asked.

The test: Every changed line should trace directly to the user's request.

## 4. Goal-Driven Execution

**Define success criteria. Loop until verified.**

Transform tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Ensure tests pass before and after"

For multi-step tasks, state a brief plan:
```
1. [Step] → verify: [check]
2. [Step] → verify: [check]
3. [Step] → verify: [check]
```

Strong success criteria let you loop independently. Weak criteria ("make it work") require constant clarification.

---

**These guidelines are working if:** fewer unnecessary changes in diffs, fewer rewrites due to overcomplication, and clarifying questions come before implementation rather than after mistakes.

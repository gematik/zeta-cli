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
- Single module: `./gradlew :api:test`
- Single test class: `./gradlew :cli:test --tests "de.gematik.zeta.cli.SomeTest"`
- Clean: `./gradlew clean`

JDK 21 toolchain is enforced via `buildSrc` conventions; the foojay-resolver plugin auto-provisions a JDK if needed. Configuration cache is enabled (`org.gradle.configuration-cache=true`).

The version is set via `gradle.properties` (`version=…`) and propagated to subprojects in the root `build.gradle.kts`. The CLI prints it via a generated `de.gematik.zeta.cli.BuildConfig` object — see the `generateBuildConfig` task in `cli/build.gradle.kts`. To override at build time: `-Pversion=1.2.3`.

## Architecture

```
cli (application, de.gematik.zeta.cli)  ──►  api (library, de.gematik.zeta.api)
```

- **`api`** — `de.gematik.zeta.api`. Pure library: data models (`ProtectedResource`, `OAuth2Client`) and Ktor-based clients (`ProtectedResourceClient`). **The `api` module never instantiates an `HttpClient`** — clients are constructor-injected by the host (CLI). `api` only depends on `ktor-client-core` (the engine lives in `cli`). It also depends on `de.gematik.zeta:zeta-sdk-jvm`; the SDK transitively pulls `slf4j-simple`, which is excluded in `api/build.gradle.kts` so it doesn't clash with our Logback binding.
- **`cli`** — `de.gematik.zeta.cli`. Clikt-based CLI. Entry point is `de.gematik.zeta.cli.MainKt#main` (see `cli/build.gradle.kts`, `applicationName = "zeta"`). Owns the Ktor engine (`ktor-client-okhttp` — single engine for the whole CLI; CIO is not on the classpath) and `HttpClient` lifecycle.

### CLI command layout

Commands are wired together in `Main.kt` via Clikt's `subcommands(...)`. Each top-level command that has children lives in its own subpackage; standalone commands live directly in `de.gematik.zeta.cli`.

- `de.gematik.zeta.cli.ZetaCommand` — root, name `zeta`.
- `de.gematik.zeta.cli.VersionCommand` — `zeta version` (simple, in the cli package).
- `de.gematik.zeta.cli.inspect.InspectCommand` — `zeta inspect <URL>` — top-level command that hits `<URL>/.well-known/oauth-protected-resource` and prints everything Zeta Guard advertises about that resource.
- `de.gematik.zeta.cli.get.GetCommand` — parent for `zeta get …`
  - `GetClientsCommand` — `zeta get clients`

When adding a new top-level command group: create a package under `de.gematik.zeta.cli`, put the parent command and its children there, and register the parent (with its `.subcommands(...)` chain) in `Main.kt`. **All commands extend `ZetaCliktCommand`, never `CliktCommand` directly** — this is what gives every command the sticky `-v/--verbose` option (see below). Override `runCommand()`, not `run()` (the base class makes `run` final and calls `Logging.applyVerbosity` before dispatching). Help text goes in `override fun help(context: Context)` (Clikt 5 moved help out of the `CliktCommand` constructor).

### HTTP client wiring

The CLI owns the `HttpClient`. `de.gematik.zeta.cli.http.createHttpClient(...)` builds a Ktor OkHttp client with the `HttpTimeout` plugin and an optional custom `X509TrustManager` for TLS. OkHttp was chosen over CIO because (a) CIO's TLS stack hard-codes `RSA`/`DSS` cert types, dropping brainpool-ECC client certs in mTLS — see the connector module CLAUDE.md for the full story — and (b) CIO doesn't preemptively send `Proxy-Authorization` on `CONNECT`, which causes `Connection reset` against authenticating corporate proxies. The HTTP options are sticky — declared on `ZetaCliktCommand` so they accept anywhere. Each command merges its parsed values into the shared `CliConfig` on the Clikt context, and the `HttpClient` is created lazily on first access:

- `--connect-timeout SECONDS` / `--request-timeout SECONDS` — Ktor `HttpTimeout` plugin.
- `-k/--insecure` — installs an all-accepting `X509TrustManager` (disables TLS verification entirely; logs a WARN). Use only for dev/test.
- `--ca-cert FILE` (repeatable) — adds PEM-encoded CA certs to the trust store on top of the default JVM roots. Use this for corporate/self-signed CAs when `curl` works but the JVM doesn't trust the cert (typical macOS Temurin behaviour: Keychain isn't on the JVM trust path).

Subcommands that need HTTP read it from context with `by requireObject<HttpClient>()`, then construct the api-side client they need (e.g. `ProtectedResourceClient(httpClient).fetch(url)`). They bridge to suspend code via `runBlocking`.

When adding a new HTTP-using subcommand: declare `private val httpClient: HttpClient by requireObject()`, construct the relevant `*Client` from `de.gematik.zeta.api`, and call its `suspend fun` from inside `runBlocking { ... }`. Don't create a new `HttpClient` in the command — reuse the shared one so timeouts/proxies/headers stay consistent.

### Logging & verbosity

`cli/src/main/resources/logback.xml` configures Logback: stderr appender, default level **WARN**, coloured pattern (`%highlight` + `%gray`). Libraries log via `io.github.oshai:kotlin-logging` (`private val log = KotlinLogging.logger {}`); both `api` and `cli` depend on it.

The `-v/--verbose` option on `ZetaCliktCommand` is `counted()`, so it's sticky — accepted at any depth (`zeta -v get clients`, `zeta get -v clients`, `zeta get clients -v` are all valid). Each `-v` increments: 1 → INFO, 2 → DEBUG, 3+ → TRACE. `Logging.applyVerbosity` is no-op at count 0, so an unspecified `-v` on a deeper command does **not** reset a level set higher up. To globally raise verbosity in tests or dev, prefer `-v` on the root.

### CLI output styling

Two reusable renderers live in `de.gematik.zeta.cli.output`:

- **`renderSections(theme, colorize) { section("...") { field(label, value); field(label, listOfValues) } }`** — Kotlin DSL for `Label: Value` layouts. Section titles render bold yellow; labels bold cyan; values plain. Multi-value fields wrap continuation lines aligned under the value column. Per-section auto-padding picks the longest `label:` width so columns align.
- **`renderJson(element, theme, colorize)`** — pretty-prints a `kotlinx.serialization.json.JsonElement` with bat/Helix-style highlighting (keys bold cyan, strings green, numbers red, booleans yellow, null muted). When `colorize = false` it falls through to `Json { prettyPrint = true }` — clean, parseable JSON.

Every command supports `-o, --output-format text|json` (sticky on `ZetaCliktCommand`, default `text`). Commands branch on `cliConfig.outputFormat`. For colour control, pass `currentContext.terminal.terminalInfo.outputInteractive` as the `colorize` flag — true on a TTY, false when piped/redirected, so `… -o json | jq` always gets plain JSON. Help text and Clikt errors keep Mordant's default colour treatment.

Output models that need `-o json` should be `@Serializable` in the `api` module (see `ProtectedResource` for the pattern: `@Serializable` + `@SerialName(...)` per RFC field). `cli` then calls `Json.encodeToJsonElement(Foo.serializer(), value)` and hands the result to `renderJson`.

### Convention plugins (`buildSrc`)

Shared build logic lives in `buildSrc/src/main/kotlin/` as precompiled Kotlin DSL plugins. Module build files apply one of these instead of repeating config:

- `buildlogic.kotlin-common-conventions` — Kotlin JVM, Maven Central, JUnit Jupiter on the test classpath, JDK 21 toolchain, dependency version constraints.
- `buildlogic.kotlin-library-conventions` — common + `java-library` (`api`/`implementation` split). Used by `api`.
- `buildlogic.kotlin-application-conventions` — common + `application` (provides `run` + `installDist`). Used by `cli`.

Shared library versions live in `gradle/libs.versions.toml` (the `libs` version catalog is shared between the main build and `buildSrc` via `buildSrc/settings.gradle.kts`). Pin new third-party versions there. Cross-module dependency-version constraints (e.g. transitive pins) belong in `buildlogic.kotlin-common-conventions`.

To add a new module: create `<module>/build.gradle.kts` applying the right convention, then add it to `include(...)` in the root `settings.gradle.kts`.

## CI & release

GitHub Actions workflows in `.github/workflows/`:

- **`ci.yml`** — runs on `push` to `main` and on PRs. Installs Temurin 21, runs `./gradlew build`, then smokes the CLI via `:cli:installDist` + `zeta version`.
- **`release.yml`** — runs on `v*` tags. Derives version from the tag (`v1.2.3` → `1.2.3`), builds `:cli:distTar` with `-Pversion=…`, attaches the `.tar.gz` + `.sha256` to a GitHub release, then bumps `Formula/zeta.rb` in the `gematik/homebrew-zeta` tap repo via `mislav/bump-homebrew-formula-action`.

Cutting a release:

1. Bump `version` in `gradle.properties` if you want a non-snapshot default for local builds (optional — the workflow overrides via `-P`).
2. `git tag v1.2.3 && git push origin v1.2.3`.
3. The workflow publishes the release and opens/commits the formula bump in the tap.

The Homebrew tap-bump step needs a repo-scoped PAT for the tap repo, stored as the `HOMEBREW_TAP_TOKEN` secret on this repo. Pre-release versions (containing `-`, e.g. `1.2.3-rc1`) skip the formula bump.

## Homebrew

`Formula/zeta.rb` is the canonical formula. The release workflow keeps the copy in the `gematik/homebrew-zeta` tap in sync; this file is the source of truth for non-version-bump changes (deps, install logic, tests).

The formula installs the gradle distribution into `libexec/`, then writes a wrapper at `bin/zeta` via `write_env_script` that pins `JAVA_HOME` to Homebrew's `openjdk@21` (overridable by the user). The tarball produced by `:cli:distTar` has a single `zeta-<version>/` top-level dir, which Homebrew strips on stage — so the `Dir["*"]` glob in `def install` picks up `bin/` and `lib/` cleanly. Don't change the dist layout without updating the formula.

To install from the tap once the tap repo exists:

```
brew tap gematik/zeta
brew install zeta
```

To test the formula locally before a release: download or build the tarball, update `url` to a `file://` path, recompute `sha256` (`shasum -a 256 zeta-<version>.tar.gz`), then `brew install --build-from-source ./Formula/zeta.rb`.

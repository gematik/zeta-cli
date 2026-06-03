# ZETA command line client

![zeta CLI demo](demo.gif)

Command-line client for resources protected by [ZETA Guard](https://github.com/gematik/zeta). Handles the full OAuth2 client lifecycle — dynamic client registration, SMC-B authentication via a gematik Konnektor or local PKCS#12 keystore, token refresh and revocation — and ships a curl-like HTTP client and a JSON-over-WebSocket client that transparently attach the bearer token to every request. Also obtains Proof-of-Patient-Presence (PoPP) tokens via Konnektor-driven eGK flows.

- [Quick start](#quick-start)
- [Commands](#commands)
- [Options reference](#options-reference)
- [Configuration file: `zeta.yaml`](#configuration-file-zetayaml)
- [Logging & output](#logging--output)
- [Install](#install)
- [Development](#development)

## Quick start

```sh
# 1. See what a Zeta-Guard-protected resource advertises (no auth needed)
zeta discover https://popp.dev.poppservice.de

# 2. Log in (register + authenticate) using your SMC-B via the Konnektor
zeta login https://popp.dev.poppservice.de \
  --scope popp \
  --auth-method connector \
  --auth-connector-telematik-id 5-2-1234567

# 3. Check local SDK state
zeta status

# 4. Call the protected resource — the bearer token is added for you
zeta http https://popp.dev.poppservice.de/some/api --scope popp

# 5. Get a PoPP token by driving the Konnektor's eGK
zeta popp connector
```

For headless / no-Konnektor environments, substitute the auth options:

```sh
zeta login https://popp.dev.poppservice.de \
  --scope popp \
  --auth-method p12 \
  --auth-p12-file ./smcb.p12
```

(`--auth-p12-alias` defaults to `alias`, `--auth-p12-password` to `00` — see the [PKCS#12 method table](#pkcs12-method---auth-method-p12).)

## Commands

| Command | What it does |
| --- | --- |
| `zeta version` | Print build version. |
| `zeta discover URL` | Fetch + cache protected-resource and AS metadata (RFC 9728). No auth. |
| `zeta status [URL]` | Show cached SDK state (registration + token validity). Read-only. |
| `zeta register URL` | Dynamic client registration with the AS. |
| `zeta authenticate URL --scope …` | Exchange SMC-B subject token → access + refresh token. |
| `zeta login URL --scope …` | Idempotent `register` + `authenticate`. |
| `zeta logout URL` | Revoke tokens; keep client registration. |
| `zeta forget URL` / `zeta forget --all` | Wipe local state for one resource, or the whole profile. |
| `zeta http URL` | HTTP request to a Zeta-protected resource (curl-like). |
| `zeta ws URL` | WebSocket to a Zeta-protected resource, round-tripping JSON from stdin. |
| `zeta connector inspect` | SDS / product info from the Konnektor. |
| `zeta connector get cards` | List cards visible in the Konnektor's terminals. |
| `zeta connector configs` | List discoverable `.kon` files. |
| `zeta popp connector` | Get a PoPP token via the Konnektor's eGK flow. |
| `zeta popp kartos` | Get a PoPP token via a kartos smartcard simulator. |

Examples for the less obvious ones:

```sh
# Status of a single resource as JSON (good for jq pipelines)
zeta status https://popp.dev.poppservice.de -o json | jq

# HTTP POST with body and custom header
zeta http https://api.example.org/v1/foo \
  --scope foo -X POST -d '{"x":1}' -H 'X-Trace-Id: dev-42'

# WebSocket with PoPP header inlined; messages from stdin
zeta ws wss://service.example.org/stream --scope foo --popp-token "$TOKEN" <<<'{"hello":1}'

# Same in PowerShell — note the $env: prefix; bare $ZETA_POPP_TOKEN won't reach the JVM
# $env:ZETA_POPP_TOKEN = (zeta popp connector)
# zeta http https://api.example.org/v1/foo --scope foo

# List .kon files the CLI can resolve by short name
zeta connector configs

# Wipe everything in a profile (asks for confirmation on a TTY; --force skips it)
zeta forget --all --profile staging
```

## Options reference

All options below are **sticky** — they're accepted at any depth: `zeta -v http …` and `zeta http -v …` both work. The same goes for `-c/--connector-config`, `--profile`, etc.

Values resolve in this precedence (high → low):
**CLI flag → environment variable → `zeta.yaml` → built-in default.**

> **PowerShell users:** `$ZETA_POPP_TOKEN = …` sets a PowerShell variable that child processes never see. Use `$env:ZETA_POPP_TOKEN = …` (same for any other `ZETA_*` env var) so the JVM picks it up.

### Global options

Available on every command.

| Option | Env var | Default |
| --- | --- | --- |
| `-v, --verbose` (repeatable) | — | `warn` (0). `-v`=info, `-vv`=debug, `-vvv`=trace |
| `--connect-timeout=<seconds>` | `ZETA_CONNECT_TIMEOUT` | `5` |
| `--request-timeout=<seconds>` | `ZETA_REQUEST_TIMEOUT` | `30` |
| `-k, --insecure` | `ZETA_INSECURE` | `false` (TLS verified) |
| `--asl-prod` | `ZETA_ASL_PROD` | `false` (non-prod) |
| `--ca-cert=<file>` (repeatable) | `ZETA_CA_CERT` | JVM default trust store |
| `-o, --output-format=text\|json\|raw` | `ZETA_OUTPUT_FORMAT` | `text` |
| `-f, --file=<file>` | `ZETA_CONFIG` | auto-discovered `zeta.yaml` |
| `--no-config` | `ZETA_NO_CONFIG` | `false` (auto-discovery on) |
| `-c, --connector-config=<name>` | `ZETA_CONNECTOR_CONFIG` | `default` |
| `--proxy=<url>` | `ZETA_PROXY` | — |
| `--proxy-user=<user>` | `ZETA_PROXY_USER` | — |
| `--proxy-password=<password>` | `ZETA_PROXY_PASSWORD` | — |
| `--trace` — print in-process span tree at end of command | `ZETA_TRACE` | `false` |

`-v` is the one exception to the env-var rule: Clikt's repeat-count flag doesn't pair with a single env value. Use `-v`/`-vv`/`-vvv` on the CLI, or set `verbose:` in `zeta.yaml`.

`--proxy` accepts `http[s]://[user:pass@]host[:port]`. Use `--proxy-user` / `--proxy-password` to keep credentials out of the URL.

### Profile

State (registrations, tokens) is namespaced by profile. Persisted as `$XDG_CONFIG_HOME/telematik/zeta/<profile>.storage.json`.

Available on: `discover`, `status`, `register`, `authenticate`, `login`, `logout`, `forget`, `http`, `ws`, `popp …`.

| Option | Env var | Default |
| --- | --- | --- |
| `--profile=<name>` | `ZETA_PROFILE` | `default` |

### Authentication

Required by: `register`, `authenticate`, `login`, `logout`, `http`, `ws`, `popp …`.

Pick a method via `--auth-method`, then supply that method's options.

| Option | Env var | Default |
| --- | --- | --- |
| `--auth-method=connector\|p12` | `ZETA_AUTH_METHOD` | required |

#### Connector method (`--auth-method connector`)

Signs the SMC-B token via a Konnektor described by `--connector-config`. Pick **exactly one** card identifier (stability order, best → worst):

| Option | Env var | Default |
| --- | --- | --- |
| `--auth-connector-telematik-id=<tid>` | `ZETA_AUTH_CONNECTOR_TELEMATIK_ID` | — |
| `--auth-connector-card-iccsn=<iccsn>` | `ZETA_AUTH_CONNECTOR_CARD_ICCSN` | — |
| `--auth-connector-card-handle=<handle>` | `ZETA_AUTH_CONNECTOR_CARD_HANDLE` | — |

#### PKCS#12 method (`--auth-method p12`)

For headless / no-Konnektor environments. Signs locally with a `.p12` keystore.

| Option | Env var | Default |
| --- | --- | --- |
| `--auth-p12-file=<file>` | `ZETA_AUTH_P12_FILE` | **required** |
| `--auth-p12-alias=<name>` | `ZETA_AUTH_P12_ALIAS` | `alias` |
| `--auth-p12-password=<password>` | `ZETA_AUTH_P12_PASSWORD` | `00` |

### Command-specific options

#### `zeta authenticate` / `zeta login` / `zeta register` / `zeta logout` / `zeta status`

| Option | Env var | Default |
| --- | --- | --- |
| `-s, --scope=<name>` (repeatable, required) — `authenticate` / `login` only | `ZETA_SCOPE` | — |
| `--reveal` — include redacted secrets in status output | `ZETA_REVEAL` | `false` |

#### `zeta forget`

| Option | Env var | Default |
| --- | --- | --- |
| `--all` — wipe entire profile | `ZETA_FORGET_ALL` | `false` |
| `--force` — skip interactive confirmation | `ZETA_FORGET_FORCE` | `false` |

#### `zeta http`

| Option | Env var | Default |
| --- | --- | --- |
| `-X, --request=<method>` | `ZETA_HTTP_METHOD` | `GET` (or `POST` if `-d` set) |
| `-H, --header=<name: value>` (repeatable) | `ZETA_HTTP_HEADER` | — |
| `-d, --data=<body>` | `ZETA_HTTP_DATA` | — |
| `-i, --include` — print status + headers | `ZETA_HTTP_INCLUDE` | `false` |
| `-s, --scope=<name>` (repeatable, **required**) | `ZETA_SCOPE` | — |
| `-p, --popp-token=<token>` | `ZETA_POPP_TOKEN` | — |

#### `zeta ws`

| Option | Env var | Default |
| --- | --- | --- |
| `-H, --header=<name: value>` (repeatable) | `ZETA_WS_HEADER` | — |
| `-s, --scope=<name>` (repeatable, **required**) | `ZETA_SCOPE` | — |
| `-p, --popp-token=<token>` | `ZETA_POPP_TOKEN` | — |

#### `zeta popp connector [EGK_HANDLE]`

| Option | Env var | Default |
| --- | --- | --- |
| `--service-url=<url>` | `ZETA_POPP_SERVICE_URL` | popp dev service URL |
| `--connection=contact\|contactless` | `ZETA_POPP_CONNECTION` | `contact` |

`EGK_HANDLE` is positional and optional — auto-picked when exactly one eGK is visible.

#### `zeta popp kartos`

| Option | Env var | Default |
| --- | --- | --- |
| `-i, --image=<path>` | `ZETA_POPP_KARTOS_IMAGE` | — |
| `--kartos-bin=<path>` | `ZETA_KARTOS_BIN` | `kartos` on `PATH` |
| `--service-url=<url>` | `ZETA_POPP_SERVICE_URL` | popp dev service URL |

**Note on repeatable options.** `--ca-cert`, `--header`, and `--scope` accept multiple values on the CLI (repeat the flag) and in `zeta.yaml` (YAML list), but their env var holds only a single value. Use the CLI flag or YAML when you need more than one.

## Configuration file: `zeta.yaml`

`zeta.yaml` lets you persist defaults so you don't repeat flags. Keys are the **long option names without leading dashes** (e.g. `--auth-method` → `auth-method`).

### Lookup

| `--no-config` / `ZETA_NO_CONFIG` | `-f` / `ZETA_CONFIG` | Loaded |
| --- | --- | --- |
| no | no | `./zeta.yaml` if present, else `$XDG_CONFIG_HOME/telematik/zeta/zeta.yaml` if present, else none |
| no | yes | the named file (must exist; missing → usage error) |
| **yes** | no | **none — built-in defaults only** |
| **yes** | **yes** | **error: mutually exclusive** |

First hit wins — files are never merged. When `-f` is passed (or `ZETA_CONFIG` is set) the file **must exist**; a missing path fails fast with a usage error rather than silently falling through. Without `-f`, auto-discovery applies and is happy to find no file at all.

### Selecting a config file (`-f`)

`-f` is the docker-compose idiom for switching between scenarios: keep one `zeta.yaml` per environment, point at one with the flag.

```yaml
# ~/.config/telematik/zeta/zeta-popp-ru.yaml
profile: popp-ru                  # SDK state lands in popp-ru.storage.json
auth-method: connector
auth-connector-telematik-id: "${SMCB_TID_RU}"
scope: [popp]
```

```sh
zeta -f ~/.config/telematik/zeta/zeta-popp-ru.yaml http https://popp.dev.poppservice.de/some/api

# Or pin per-shell:
export ZETA_CONFIG=~/.config/telematik/zeta/zeta-popp-ru.yaml
zeta http https://popp.dev.poppservice.de/some/api
zeta popp connector
```

Pair each scenario file with its own `profile:` so SDK state (registrations, tokens) is also isolated — `popp-ru` writes to `popp-ru.storage.json`, `popp-tu` writes to `popp-tu.storage.json`, no cross-contamination.

Single file only — there is no docker-compose-style multi-`-f` merging today.

> For a totally clean run with no config at all, see `--no-config` below.

### Disabling the config file (`--no-config`)

Use `--no-config` (or `ZETA_NO_CONFIG=1`) when you need built-in defaults only — no project-local `./zeta.yaml`, no XDG fallback, no `-f`. Useful for:

- Reproducing an issue without your `zeta.yaml` interfering.
- One-off ad-hoc commands from an arbitrary directory.
- CI runs where output must be predictable regardless of the surrounding environment.

```sh
zeta --no-config status         # ignores any zeta.yaml on disk
ZETA_NO_CONFIG=1 zeta status    # env-var equivalent
```

`--no-config` is **mutually exclusive** with `-f` / `ZETA_CONFIG`. Passing both is treated as a mistake — the CLI exits with `Error: --no-config is mutually exclusive with -f / ZETA_CONFIG` rather than silently picking one. Only `1`, `true`, or `yes` (case-insensitive) activate the env var; `0`/empty leave it off.

### Structure

- Top-level keys apply to any command that defines that option.
- Subcommand-scoped keys (`<subcommand>: { … }`) only apply when that subcommand runs. Scoped wins over top-level.
- `${VAR}` placeholders are expanded against the process environment before parse — secrets and per-host overrides stay out of the file.
- Repeatable options take a YAML list.

#### Reading the schema (TOML-like)

The shape is the same as a TOML file: flat key–value pairs at the top, named sections for scope. Two rules:

- **Top-level kebab keys are flag names verbatim.** `auth-method` ↔ `--auth-method`. The hyphen is part of the option name, not a path separator.
- **Nested mappings are subcommand scope, not name hierarchy.** `http: { header: [...] }` means "the `--header` flag *when invoked with `zeta http`*". Nesting depth mirrors the command path: `popp.connector.connection` is the `--connection` flag under `zeta popp connector`.

So `auth-method` and `popp.connector.connection` look different on the page because they *are* different things: a flag name vs. a command-scoped flag. The two never mix — a key never means "namespaced option".

### Example

```yaml
# Defaults for every command
profile: dev
connector-config: my-kon
auth-method: connector
auth-connector-telematik-id: "${SMCB_TID}"

# Per-subcommand overrides
http:
  header:
    - "X-Trace-Id: ${USER}-dev"
  include: true

ws:
  header:
    - "X-WS-Trace: ${USER}-dev"

popp:
  connector:
    connection: contactless
```

With this in `./zeta.yaml`, the example from [Quick start](#quick-start) shrinks to:

```sh
zeta login https://popp.dev.poppservice.de --scope popp
zeta http  https://popp.dev.poppservice.de/some/api --scope popp
zeta popp connector
```

### Precedence reminder

CLI flag > env var > `zeta.yaml` value > built-in default. So a YAML value never overrides what you typed at the prompt, but it does override what the binary ships with as default.

### Caveat: sticky options + project YAML

A sticky option (e.g. `--connector-config`) declared at a parent depth (`zeta --connector-config=X ...`) gets re-read by the child from the same YAML key and may be overwritten. Workaround: pass sticky overrides at the deepest depth (`zeta connector inspect --connector-config=X`).

## Logging & output

- Logs go to **stderr**; data goes to **stdout** — safe for shell pipelines.
- Default log level is `warn`. Bump with `-v` (info), `-vv` (debug), `-vvv` (trace).
- `-o json` emits parseable JSON without colour when stdout is piped. `-o raw` (for `http`) prints the body verbatim with no framing.
- Colour follows TTY detection and respects `NO_COLOR` / `FORCE_COLOR`.

## Install

Requires JDK 21. The Gradle wrapper auto-provisions one if needed.

### Dev mode (no install)

Run straight from sources via `:cli:run` — the `./zeta-dev` wrapper forwards args verbatim:

```sh
./zeta-dev version
./zeta-dev discover https://popp.dev.poppservice.de
```

### Local install via Gradle (no Homebrew)

```sh
./gradlew :cli:installDist
./cli/build/install/zeta/bin/zeta version
export PATH="$PWD/cli/build/install/zeta/bin:$PATH"   # optional
```

### Local install via Homebrew

```sh
just install      # builds tarball, materialises a private tap, brew install
zeta version
```

Installs into `$(brew --prefix)/bin` and pins `JAVA_HOME` to Homebrew's `openjdk@21`.

## Development

Common tasks are wired up in the `justfile`:

```sh
just install   # build and brew-install locally from Formula/zeta.rb
just demo      # render demo.gif from demo.tape (requires vhs)
```

### Building against a different `zeta-sdk`

By default the CLI builds against the version of `de.gematik.zeta:zeta-sdk-jvm` pinned in `gradle/libs.versions.toml` (currently `1.0.1`, resolved from Maven Central). To swap in a local SDK build during development without editing the catalog, override the Gradle property at the command line:

```sh
# Use whatever you publishToMavenLocal'd as `latest`
./gradlew :cli:installDist -PzetaSdkVersion=latest

# Or any other tag living in your ~/.m2/repository/de/gematik/zeta/zeta-sdk-jvm/
./gradlew :cli:installDist -PzetaSdkVersion=1.0.2-local
```

Set it persistently in `~/.gradle/gradle.properties` (`zetaSdkVersion=latest`) to avoid passing the flag every time — Gradle will still let project- or command-line values override that.

`zeta version` prints the resolved SDK version so you always know which one shipped in the binary.

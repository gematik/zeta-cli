# zeta

![zeta CLI demo](demo.gif)

Swiss-army-knife CLI for [ZETA](https://github.com/gematik/zeta-guard) — inspect Zeta-Guard-protected resources, register clients, and talk to a gematik Konnektor.

## Install & run locally

Requires JDK 21. The Gradle wrapper (`./gradlew`) auto-provisions one if it isn't already on the system.

### Dev mode (no install)

Fastest feedback loop — runs straight from sources via `:cli:run`. The `./zeta-dev` wrapper forwards args verbatim, preserving spaces and quotes:

```sh
./zeta-dev version
./zeta-dev inspect https://popp.dev.poppservice.de
```

### Local install via Gradle (no Homebrew)

Build a self-contained distribution and run the launcher script directly:

```sh
./gradlew :cli:installDist
./cli/build/install/zeta/bin/zeta version
```

Add it to your `PATH` if you want to call `zeta` from anywhere:

```sh
export PATH="$PWD/cli/build/install/zeta/bin:$PATH"
```

### Local install via Homebrew

If you have Homebrew, `just install` builds the release tarball, materialises a private tap on the fly, and `brew install`s the formula from `Formula/zeta.rb`:

```sh
just install
zeta version
```

This drops `zeta` into `$(brew --prefix)/bin` and pins `JAVA_HOME` to Homebrew's `openjdk@21`.

## Usage

```sh
zeta --help              # overview
zeta version             # print version
zeta inspect <URL>       # show everything Zeta Guard advertises about a resource
zeta register ...        # register a client with Zeta Guard
zeta connector ...       # talk to a gematik Konnektor (.kon config)
```

Add `-v` (repeatable) for more log detail, `-o json` for machine-readable output.

## Development

Common tasks are wired up in the `justfile`:

```sh
just install   # build and brew-install locally from Formula/zeta.rb
just demo      # render demo.gif from demo.tape (requires vhs)
```

See [`CLAUDE.md`](CLAUDE.md) for the full architecture and build notes.

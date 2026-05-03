# zeta

![zeta CLI demo](demo.gif)

Swiss-army-knife CLI for [ZETA](https://github.com/gematik/zeta-guard) — inspect Zeta-Guard-protected resources, register clients, and talk to a gematik Konnektor.

## Install

Via the Homebrew tap:

```sh
brew tap gematik/zeta
brew install zeta
```

Or build from source (requires JDK 21):

```sh
./gradlew :cli:installDist
./cli/build/install/zeta/bin/zeta version
```

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

# What the CLI keeps on disk

Every persistent thing `zeta` writes, what is in it, and how to get rid of it. Four of these are
SQLite files; the rest are small text files. Nothing is encrypted at rest today — the protection
is file permissions and where you put the file.

| Store | Path | Holds | Mode |
| --- | --- | --- | --- |
| Profile state | `$XDG_CONFIG_HOME/telematik/zeta/<profile>.storage.db` | access + refresh tokens, `client_secret`, registration access token, the SDK's client/DPoP private keys, ASL sessions, AS metadata, the catalog cache | `0600` |
| Bundle cache | wherever `--cache-db` points (no default) | VSDM bundles, the PoPP tokens they were read with, KVNR, IKNR, reader Telematik-ID | `0600` |
| Stress DB | `./stress.db` (or `--db`) | SMC-B private keys and certs, PoPP tokens, per-client SDK state, run results | `0600` |
| Connector configs | `$XDG_CONFIG_HOME/telematik/connectors/*.kon`, `…/active` | Konnektor URL, trust anchors, and whatever credentials you put in them | yours |
| Config | `./zeta.yaml` or `$XDG_CONFIG_HOME/telematik/zeta/zeta.yaml` | option defaults, possibly `auth-p12-password` | yours |
| Stress reports | `./reports/<profile>-<timestamp>/` | latencies, outcomes, error strings | umask |
| Daemon socket | `$XDG_RUNTIME_DIR/zeta/zeta.sock` | — | `0600` |

`$XDG_CONFIG_HOME` defaults to `~/.config` on every OS, macOS included.

## Profile state

One SQLite file per `--profile` (default `default`), written by every command that builds an SDK
client. It is the CLI's credential store: refresh tokens outlive the process, and the SDK's
software-attestation key material lives there too. Treat it like an SSH private key.

```sh
zeta status                 # what is in it, secrets redacted (--reveal to see them)
zeta logout URL             # revoke and drop the tokens for one resource, keep the registration
zeta forget URL             # drop everything cached for one resource
zeta forget --all           # delete the profile file
```

The TI service-discovery catalog is cached in the same file under a `service-discovery` context.
It holds no secrets, and only `zeta forget --all` removes it.

## Bundle cache (`--cache-db`)

Off unless you name a file. It holds **Versichertenstammdaten and PoPP tokens, unencrypted** — the
most sensitive payload the CLI touches — so the location is a deliberate choice, not a default.

Nothing in it expires by freshness: every read is revalidated against the service, so a cached
bundle can never be served as current without the service confirming it. `--cache-max-entries`
(10000) and `--cache-max-age-days` (180) are hygiene bounds so records do not linger; `zeta serve`
applies them hourly, `zeta vsdm get` after each read.

Choosing a location:

- **Not in a repository.** A `cache-db:` key in a project-local `./zeta.yaml` enables the cache
  just as the flag does, and a relative path lands in the working directory. Patient data one
  `git add -A` away from a commit is not where you want it.
- **Not on a shared or synced filesystem.** The `0600` mode is the only thing keeping other users
  out; a network share, a backup sweep or a cloud-sync folder walks straight past it.
- **One file per tenant.** Entries are keyed by the reading SMC-B, so several identities can share
  a file and be counted and purged apart — but anyone who can read the file reads every bundle in
  it. Keying buys attribution, not isolation.

```sh
zeta vsdm cache stats  --cache-db FILE
zeta vsdm cache purge  --cache-db FILE --insurant KVNR      # one person's record
zeta vsdm cache purge  --cache-db FILE --actor TID          # one reader's entries
zeta vsdm cache purge  --cache-db FILE --tokens [filters]   # keep bundles, drop PoPP tokens
zeta vsdm cache purge  --cache-db FILE --all                # everything (asks first)
zeta vsdm get … --no-cache                                  # read in full, keep nothing
rm FILE FILE-wal FILE-shm FILE.meta.json                    # always safe
```

The file carries a `application_id`/`user_version` header and a `<file>.meta.json` sidecar. A file
written by a schema this build does not speak is discarded and recreated — a cache holds nothing
that cannot be fetched again. A file belonging to another application, or one that merely cannot
be opened right now, is refused and left alone.

## Stress DB

`zeta stress` imports SMC-B identities into `stress.db`: **private keys in the clear**, plus the
PoPP tokens obtained with them. The main CLI reads the same file for `--auth-method db`. There is
no purge command — delete the file. `zeta stress popp export` writes each token to its own `0600`
`.jwt` file; those are just as sensitive as the DB.

## What is deliberately not stored

- PoPP tokens are not written into the profile DB. `zeta popp …` prints one; keeping it is your
  decision.
- The VSDM `PZ` (Prüfziffer) is per-read and never cached.
- Nothing is logged to a file. `-vv` and `--trace` write to stderr, and the wire log can contain
  tokens — mind where you redirect it.

## Known gaps

- No at-rest encryption anywhere. The cache has the seam for it (`BlobCodec`, the `cipher` field in
  its sidecar) but ships with `cipher: "none"`.
- `zeta stress db` has no `purge`, and `zeta connector use` has no inverse — both are handled by
  deleting the file.
- The stress DB and the profile DB have no versioned migrations; their tables only ever grow.

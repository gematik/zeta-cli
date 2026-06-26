# Quick start: a PoPP token via the Konnektor

End-to-end walkthrough for the most common flow — authenticate with an SMC-B through a
Konnektor (or TI-Gateway) and obtain a **Proof-of-Patient-Presence (PoPP)** token for an
eGK. No real secrets appear in this guide — sensitive values are shown as placeholders.

## Prerequisites

- A **Konnektor or TI-Gateway** reachable from your machine (RU/TU environment).
- A **test SMC-B** (institution card) provisioned in that environment and plugged into a
  card terminal the Konnektor can see.
- A **test eGK** to present for the PoPP flow. A *Techniker Krankenkasse* eGK works for a
  full VSDM (Versichertenstammdaten) read.
- `zeta` installed — see the [README](../README.md#install).

## 1. Configure the connector (`default.kon`)

Create a `default.kon` in your current working directory. The CLI resolves `.kon` files from
the current directory first, and naming it **`default`** means every command picks it up
automatically — no `--connector-config` flag needed.

```json
{
  "url": "https://<konnektor-or-ti-gateway-host>:<port>",
  "mandantId": "<mandant>",
  "workplaceId": "<workplace>",
  "clientSystemId": "<client-system>",
  "credentials": {
    "type": "pkcs12",
    "data": "<base64-encoded PKCS#12 container>",
    "password": "00"
  },
  "env": "ru",
  "insecureSkipVerify": true
}
```

Produce the `data` value from your mTLS client keystore with `base64 < client.p12` and paste
it in; `00` is the conventional gematik test passphrase.

> **Security:** inlining the key and password is fine for a throwaway local test
> config, but anything shared or production-bound must keep secrets out of the file — use
> `${VAR}` environment substitution or an OS credential store instead. See the
> [security considerations](kon-format.md#9-security-considerations) in the `.kon` spec.

For self-signed Konnektor PKIs, `insecureSkipVerify: true` is the quickest start; prefer
pinning a `trustStore` for anything lasting. Full field reference:
[`kon-format.md`](kon-format.md).

Confirm the CLI resolves it:

```sh
zeta connector configs
```

## 2. List cards and read the SMC-B ICCSN

```sh
zeta connector get cards
```

This drives the Konnektor's service directory and lists every visible card. Take the
**ICCSN** of the SMC-B row and put it in a shell variable, so the commands below run as-is:

```sh
SMCB_ICCSN=<the SMC-B ICCSN from the table>
```

> The ICCSN is the most stable card identifier printed on the card body. If you manage
> several cards, `--auth-connector-telematik-id` is even more stable; `--auth-connector-card-handle`
> is the least (session-scoped).

## 3. Log in to the PoPP service

`login` is idempotent — it registers the client (if needed) and authenticates, caching
tokens for later calls. Options are grouped target → scope → auth:

```sh
zeta login https://popp.dev.poppservice.de \
  --scope popp \
  --auth-method connector \
  --auth-connector-card-iccsn "$SMCB_ICCSN"
```

`default.kon` is used automatically; pass `--connector-config <name>` to select a different
`.kon`, or run `zeta connector use <name>` once to make another the sticky default.

## 4. Get a PoPP token

Insert the eGK into a terminal the Konnektor can see, then:

```sh
zeta --sdk 1.0.1 popp connector \
  --auth-method connector \
  --auth-connector-card-iccsn "$SMCB_ICCSN" \
  -v
```

The PoPP flow is pinned to `zeta-sdk` **1.0.1** via the global `--sdk` flag (placed before
the subcommand). See `zeta sdk list` for the bundled versions; `--sdk` only applies to the
installed distribution — from a dev checkout use `./gradlew :cli-sdk1_0:run` instead.

With exactly one eGK visible it is auto-selected; otherwise pass its handle as the final
argument. To reuse the token in a follow-up protected call, capture it:

```sh
export ZETA_POPP_TOKEN="$(zeta --sdk 1.0.1 popp connector --auth-method connector --auth-connector-card-iccsn "$SMCB_ICCSN")"
```

## 5. Call the VSDM service (TK)

With `ZETA_POPP_TOKEN` exported from step 4, read the insured's VSD bundle. `zeta http` logs
in against the VSDM resource on first use (same connector auth), adds the bearer token for the
`vsdservice` scope, and forwards the captured token as the `PoPP` header automatically:

```sh
zeta http 'https://vsdm-dev.tk.de/vsdservice/v1/vsdmbundle?profileVersion=1.0' \
  --scope vsdservice \
  --auth-method connector \
  --auth-connector-card-iccsn "$SMCB_ICCSN" \
  -H "Accept: application/fhir+json" \
  -H 'If-None-Match: "0000000000000000000000000000000000000000000000000000000000000000"' \
  -vv
```

The all-zero `If-None-Match` ETag forces a full bundle (a matching ETag would return
`304 Not Modified`). This is where the *Techniker Krankenkasse* test eGK matters — it backs a
working VSDM read. Drop `--popp-token`/`ZETA_POPP_TOKEN` and the request is rejected for
missing proof of patient presence.

## Notes

- **Verbosity**: add `-v` (INFO), `-vv` (DEBUG), or `-vvv` (TRACE) at any depth to see the
  Konnektor round-trips. `--trace` prints a span tree of the whole flow.
- **Zero round-trips when cached**: once tokens are valid, `zeta http` / `zeta ws` against the
  resource add the bearer token without touching the Konnektor again.
- **Secrets**: the inline `data`/`password` above are fine for a local test `.kon`. Don't
  commit or share a file with real credentials — switch to `${VAR}` substitution or an OS
  credential store. See the [security considerations](kon-format.md#9-security-considerations).

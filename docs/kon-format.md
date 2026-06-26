# Konnektor Configuration Format (`.kon`)

**Status:** Draft · **Version:** 1.0.0 · **Last updated:** 2026-02-08

## 1. Introduction

To connect to a Konnektor, every client needs a set of parameters that are normally
supplied by an administrator:

- the address of the Konnektor (URL, port);
- whether and how TLS is used, including the certificates trusted to verify the
  Konnektor;
- the information-model context (mandant, workplace, client system);
- the credentials used to authenticate against the Konnektor interfaces (e.g.
  username/password or a TLS client certificate).

Today each client configures these parameters through its own client-specific mechanism.
This document specifies a **single, portable configuration format** so that an
administrator can hand the same standardized configuration to any conformant client.

Because the Telematikinfrastruktur (TI) uses a private PKI, clients must be able to
verify the Konnektor's certificates. The format therefore carries both the environment in
which the Konnektor is operated and the trust anchors used for verification.

Credentials must be storable either inline in the configuration **or** out of band — in an
operating-system credential store, an environment variable, or a secret manager. The
latter is required for production, where secrets MUST NOT be committed to a file in clear
text. To support this, the format defines an environment-variable substitution mechanism
(see [§4](#4-environment-variable-substitution)).

## 2. Conventions

The key words **MUST**, **MUST NOT**, **REQUIRED**, **SHOULD**, **SHOULD NOT**, **MAY**,
and **OPTIONAL** in this document are to be interpreted as described in
[RFC 2119](https://www.rfc-editor.org/rfc/rfc2119) and
[RFC 8174](https://www.rfc-editor.org/rfc/rfc8174).

A *configuration* is a single object containing all parameters required to reach one
Konnektor. The `url` of that object also serves as the base for the service-directory
lookup at `<url>/connector.sds`.

## 3. Encoding and file extensions

The configuration is an object; multiple encodings are possible. For simplicity and
ubiquity, JSON is the mandatory baseline.

- Clients **MUST** support JSON-encoded configurations.
- Clients **MUST** interpret files with the `.kon` extension as JSON.
- Clients **MAY** interpret files with the `.kony` extension as YAML.

A configuration **MAY** also be supplied through means other than a file — for example,
the full document passed in a single environment variable, or assembled from secrets at
startup.

## 4. Environment-variable substitution

To keep secrets out of the file and to allow the same template to be reused across
environments, the format defines variable substitution.

- Clients **MUST** support `${VAR}` placeholders.
- Substitution **MUST** be applied to the **raw document text before it is parsed**, so a
  placeholder may appear in any position — inside any string value, or spanning a value
  that itself encodes structured data.
- A placeholder has the exact syntax `${NAME}`, where `NAME` is one or more characters
  that are not `}`. The bare form `$NAME` (without braces) is **NOT** recognized.
- `NAME` is looked up in the process environment.
- An **undefined** variable **MUST** expand to the empty string (Docker Compose / Go
  reference behaviour). Clients MUST NOT fail solely because a referenced variable is
  unset.
- Default-value syntax (e.g. `${VAR:-fallback}`) and escaping (e.g. `$${VAR}`) are **NOT**
  defined by this version of the format. The characters between `${` and the first `}` are
  treated verbatim as the variable name.

> **Note — substituted values are inserted verbatim.** Because substitution happens before
> parsing, the expanded value must be valid in its surrounding context. A secret that
> contains `"`, `\`, or a newline can break the enclosing JSON string; choose values (or
> encodings, e.g. base64) that are safe to inline.

The same substitution rules apply equally to `.kon` and `.kony` documents.

### 4.1 Examples

Read a single secret from the environment:

```json
{
  "credentials": {
    "type": "basic",
    "username": "user",
    "password": "${KONNEKTOR_AUTH_BASIC_PASSWORD}"
  }
}
```

Parameterize the endpoint per environment:

```json
{
  "url": "https://${KONNEKTOR_HOST}:${KONNEKTOR_PORT}",
  "env": "${TI_ENV}"
}
```

Inject a base64-encoded PKCS#12 client certificate without writing it to disk:

```json
{
  "credentials": {
    "type": "pkcs12",
    "data": "${KONNEKTOR_CLIENT_P12_BASE64}",
    "password": "${KONNEKTOR_CLIENT_P12_PASSWORD}"
  }
}
```

An unset variable expands to an empty string — for `password` that surfaces as a normal
validation error ("password is required"), not a substitution failure.

## 5. Top-level parameters

| Parameter | Required | Type | Description |
| --- | --- | --- | --- |
| `version` | OPTIONAL | string | Version of the configuration format (e.g. `"1.0.0"`). |
| `url` | **REQUIRED** | string | Konnektor URL. MUST include the scheme (normally `https://`) and the FQDN or IP address, plus the port if it differs from the scheme default. Clients **MUST** tolerate a trailing `/`. Serves as the base for `<url>/connector.sds`. Examples: `https://konnektor.example.com:8443`, `https://192.168.1.2`, `https://konnektor.example.com/kon1`. |
| `rewriteServiceEndpoints` | OPTIONAL (default `false`) | boolean | When `true`, the endpoint URLs returned in the Konnektor's service directory (e.g. `https://10.1.1.1:80/SignatureService`) are ignored and rebuilt against `url`, keeping only the path component (e.g. `/SignatureService`). REQUIRED when the Konnektor runs behind a reverse proxy, NAT, or in a Docker network where the advertised internal addresses are not reachable by the client. |
| `mandantId` | **REQUIRED** | string | Mandant ID from the information model. Used as the call context for the Konnektor interfaces. |
| `workplaceId` | **REQUIRED** | string | Workplace ID from the information model. Used as the call context. |
| `clientSystemId` | **REQUIRED** | string | Client-system ID from the information model. Used as the call context. |
| `userId` | OPTIONAL | string | User ID of the HBA holder. Used as the call context. |
| `telematikId` | OPTIONAL | string | Telematik-ID of a card. When the client is bound to a specific identity (e.g. a KIM client module), this hints which smartcard to select. |
| `credentials` | **REQUIRED** | object | Authentication configuration. See [§6](#6-credentials). |
| `env` | OPTIONAL | string | Environment the Konnektor runs in. One of `ru` (Referenzumgebung), `tu` (Testumgebung), `pu` (Produktivumgebung). If present, clients **MUST** reject any other value. |
| `insecureSkipVerify` | OPTIONAL (default `false`) | boolean | When `true`, TLS verification is disabled. **MUST** be used for test purposes only. |
| `expectedHost` | OPTIONAL | string | FQDN/hostname that **MUST** appear as the Subject or a Subject Alternative Name (SAN) in the Konnektor's certificate. Use this when the Konnektor presents a certificate whose name differs from the host in `url`. |
| `trustStore` | OPTIONAL | array of string | Certificates used to verify the Konnektor, each a base64-encoded DER certificate. Both the end-entity certificate and CA certificates MAY be supplied. Clients **SHOULD** accept line-wrapped (MIME) base64. |

## 6. Credentials

The `credentials` object is a tagged union discriminated by its `type` field. This version
defines three types; further types MAY be added in the future, so clients **MUST** reject
an unknown `type` with a clear error rather than silently ignoring it.

- `basic` — username and password for HTTP Basic authentication.
- `pkcs12` — a PKCS#12 container holding a TLS client certificate and private key.
- `system` — a reference to an entry in an operating-system credential store.

### 6.1 `basic`

| Parameter | Required | Description |
| --- | --- | --- |
| `type` | **REQUIRED** | `"basic"` |
| `username` | **REQUIRED** | Username for authentication. |
| `password` | **REQUIRED** | Password for authentication. |

```json
{
  "type": "basic",
  "username": "user",
  "password": "${KONNEKTOR_AUTH_BASIC_PASSWORD}"
}
```

### 6.2 `pkcs12` (TLS client certificate)

| Parameter | Required | Description |
| --- | --- | --- |
| `type` | **REQUIRED** | `"pkcs12"` |
| `data` | **REQUIRED** | Base64-encoded PKCS#12 container. Clients **SHOULD** accept line-wrapped (MIME) base64. |
| `password` | OPTIONAL | Passphrase for the container. **SHOULD** only be used in non-production environments; in production the passphrase **SHOULD** come from a secret store via substitution, or the container **SHOULD** be passphrase-free. |

```json
{
  "type": "pkcs12",
  "data": "${KONNEKTOR_CLIENT_P12_BASE64}",
  "password": "${KONNEKTOR_CLIENT_P12_PASSWORD}"
}
```

### 6.3 `system`

References an entry in an OS-specific credential store by name; the client resolves the
actual secret at runtime.

| Parameter | Required | Description |
| --- | --- | --- |
| `type` | **REQUIRED** | `"system"` |
| `name` | **REQUIRED** | Name of the entry in the credential store. |

```json
{
  "type": "system",
  "name": "credential-name"
}
```

## 7. Validation

A conformant client **MUST** reject a configuration that:

- is not well-formed JSON (or YAML, for `.kony`) after substitution;
- omits any of `url`, `mandantId`, `workplaceId`, `clientSystemId`, or leaves them blank;
- specifies `env` with a value other than `ru`, `tu`, or `pu`;
- omits `credentials` or its `type`;
- for `basic`, omits or leaves blank `username` or `password`;
- for `pkcs12`, omits or leaves blank `data`;
- for `system`, omits or leaves blank `name`;
- carries a `trustStore` entry or `pkcs12.data` that is not valid base64.

Clients **SHOULD** report all field errors at once rather than failing on the first.

## 8. Complete example

```json
{
  "version": "1.0.0",
  "url": "https://konnektor.example.com:8443",
  "rewriteServiceEndpoints": false,
  "mandantId": "M1",
  "workplaceId": "W1",
  "clientSystemId": "C1",
  "userId": "U1",
  "telematikId": "2-2134567890",
  "credentials": {
    "type": "pkcs12",
    "data": "<base64-encoded PKCS#12 container>",
    "password": "00"
  },
  "env": "ru",
  "insecureSkipVerify": false,
  "expectedHost": "konnektor.example.com",
  "trustStore": [
    "<base64-encoded DER certificate>",
    "<base64-encoded DER certificate>"
  ]
}
```

## 9. Security considerations

- **Never commit clear-text secrets.** In production, supply `password`, `pkcs12.data`,
  and `pkcs12.password` via `${VAR}` substitution or the `system` credential type, and keep
  the resolved values in environment variables or a secret store.
- `insecureSkipVerify: true` disables certificate verification entirely and **MUST NOT** be
  used in production. Prefer `trustStore` (and `expectedHost` where names differ) to pin the
  Konnektor's PKI.
- A substituted value is inlined verbatim into the document text before parsing; treat it as
  untrusted input and ensure it is valid within its surrounding context
  (see [§4](#4-environment-variable-substitution)).

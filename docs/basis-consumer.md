# Basis-Consumer

What the connector library needs to know about the gematik Basis-Consumer
([gemSpec_Basis_Consumer V1.12.1](https://gemspec.gematik.de/downloads/gemSpec/gemSpec_Basis_Consumer/gemSpec_Basis_Consumer_V1.12.1.html))
to sign ZETA SMC-B subject tokens through it instead of a Konnektor.

The Basis-Consumer is a provider-run service in a data centre that lets organisations take part in
KIM. It holds SM(C)-B **ORG** and **KTR** identities (in an HSM or on cards) and offers a small SOAP
surface for them. Access to other Fachanwendungen is explicitly not part of it, and it does not serve
Praxis SMC-Bs.

## What differs from a Konnektor

| | Konnektor | Basis-Consumer |
| --- | --- | --- |
| Service discovery | `<url>/connector.sds` | none — endpoints must be configured |
| Call context | `Context` (Mandant, ClientSystem, Workplace, User) on every call | none — operations take only a `CardHandle` |
| Card enumeration | `EventService.GetCards` | none — the card handle must be known up front |
| Read the C.AUT cert | `CertificateService.ReadCardCertificate` | `CertificateService.ReadCertificate` |
| Sign a challenge | `AuthSignatureService.ExternalAuthenticate` | `SignatureService.ExternalAuthenticate` |
| Card sessions / APDUs | `CardService` | none, so the PoPP connector flow is not possible |
| Client authentication | per Konnektor configuration | not specified; left to the provider |
| Namespaces | `http://ws.gematik.de/conn/…` | `http://ws.gematik.de/consumer/…` |

The CardHandle for each identity is guaranteed by the provider to identify it uniquely and to become
invalid when the identity is removed (A_25030); how a client learns it is not specified.

## Operations used for ZETA signing

Generated from `api-telematik` tag `Consumer_1.2.1` into `de.gematik.connector.api.gematik.consumer.*`.

**ReadCertificate** (A_24782-02) — `certificateservice30`, payload types in `certificateservice31`

- SOAPAction `http://ws.gematik.de/consumer/CertificateService/v3.0#ReadCertificate`
- Input: `CardHandle`, `CertRefList` with `C.AUT` (or `C.OSIG`), `Crypt` `ECC` or `RSA` (default ECC).
- Faults: 4000 syntax error, 4149 invalid certificate reference, 4090 access to identity not permitted,
  4258 no certificates on the identity.

**ExternalAuthenticate** (A_17578-03 / A_17578-04) — `signatureservice32`

- SOAPAction `http://ws.gematik.de/consumer/SignatureService/v3.2#ExternalAuthenticate`
- Input: `CardHandle`, `OptionalInputs/SignatureType` `urn:bsi:tr:03111:ecdsa`, and the SHA-256 digest
  as `BinaryString`. Signs with `PrK.HCI.AUT` of the SM(C)-B; the DER ECDSA signature comes back in
  `SignatureObject/Base64Signature`, as with the Konnektor.
- Which requirement applies depends on the "ECC preferred" switch (A_26447-01), which gematik controls
  and which is off by default. Off (-03): RSA and ECDSA are both allowed and ECDSA is the default when
  `SignatureType` is absent. On (-04): ECDSA over at most 256 bits only. Sending ECDSA explicitly works
  in both states.
- Faults: 4000 syntax error, 4111 invalid signature type or variant, 4123 signing failed.

Fault bodies carry the same `http://ws.gematik.de/tel/error/v2.0` `Error` as the Konnektor's, so the
existing SOAP-fault handling applies unchanged.

## Version caveat

The generated code (`Consumer_1.2.1`) is newer than what gemSpec_Basis_Consumer V1.12.1 describes:

| | gemSpec V1.12.1 | generated |
| --- | --- | --- |
| SignatureService (WSDL and schema) | 3.1 | 3.2 |
| CertificateService WSDL / SOAPAction | 3.0 | 3.0 |
| CertificateService schema (`ReadCertificate` payload) | 3.0 | 3.1 |
| CertificateServiceCommon | 2.0 | 2.1 |

The version is part of the element namespaces (and, for SignatureService, the SOAPAction), so a
Basis-Consumer at the V1.12.1 level would not recognise these requests. Check which versions a real
system serves before relying on it.

## Authentication and TLS

The specification defines no client authentication for this interface. The `.kon` settings that
already exist for a Konnektor (`credentials`, `trustStore`, `insecureSkipVerify`, `expectedHost`)
apply as they are, whichever mechanism the provider chose.

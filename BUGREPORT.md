# Bug report: `ZetaSdkClient.ws()` masks HTTP failures with a `SerializationException`

**Component**: `zeta-sdk` (network + zeta-sdk modules)
**Surfaced from**: `zeta-cli` running `zeta popp kartos` against `popp.ref.poppservice.de`
**Severity**: High for debuggability — any non-101 WebSocket upgrade response with a JSON body produces a confusing serializer error instead of the real HTTP failure.

## Symptom

Running a CLI command that opens a WebSocket via `sdk.ws(...)` fails with:

```
ERROR d.gematik.zeta.cli.Main - Command failed
kotlinx.serialization.SerializationException: Serializer for class 'DefaultClientWebSocketSession' is not found.
Please ensure that class is marked as '@Serializable' and that the serialization compiler plugin is applied.

    at kotlinx.serialization.internal.Platform_commonKt.serializerNotRegistered(Platform.common.kt:90)
    at kotlinx.serialization.SerializersKt__SerializersKt.serializer(Serializers.kt:327)
    at io.ktor.serialization.kotlinx.SerializerLookupKt.serializerForTypeInfo(SerializerLookup.kt:32)
    at io.ktor.serialization.kotlinx.KotlinxSerializationConverter.deserialize(KotlinxSerializationConverter.kt:66)
    ...
    at io.ktor.client.plugins.contentnegotiation.ContentNegotiationKt.ContentNegotiation$lambda$0$convertResponse(ContentNegotiation.kt:284)
    ...
    at io.ktor.client.call.HttpClientCall.bodyNullable(HttpClientCall.kt:99)
    at io.ktor.client.plugins.websocket.BuildersKt.webSocket(builders.kt:282)
    at de.gematik.zeta.cli.popp.PoppKartosCommand.runPoppFlow(PoppKartosCommand.kt:67)
```

The exception suggests a missing `@Serializable` annotation, which is misleading — `DefaultClientWebSocketSession` is a Ktor type, not something the caller serializes. The real failure is hidden.

## Reproduction

Any `sdk.ws(...)` call where the server returns a non-101 response with `Content-Type: application/json` triggers this. In the observed case the response is:

```
RESPONSE: 403 Forbidden
content-type: application/json
zeta-api-version: 0.5.1

{
  "error": "PoPP",
  "error_description": "PoPP error: missing PoPP header",
  "error_uri": "https://popp.ref.poppservice.de/doc/errors/PoPP.html"
}
```

(That ZETA-Guard 403 is a separate question — the token-issuance endpoint requiring a PoPP header is likely a service misconfiguration — but it's not the SDK bug; it's just the trigger.)

## Root cause

`ZetaSdk.ws()` (`zeta-sdk/zeta-sdk/src/commonMain/kotlin/de/gematik/zeta/sdk/ZetaSdk.kt:234`) builds the WebSocket client through `ZetaHttpClientBuilder(...).build()`, which is the same factory used for ordinary REST clients. That factory unconditionally installs `ContentNegotiation` with a JSON converter:

```kotlin
// network/src/commonMain/kotlin/de/gematik/zeta/sdk/network/http/client/ZetaHttpClient.kt:135-138
install(ContentNegotiation) {
    Log.i { "Installing ContentNegotiation JSON plugin" }
    json(Json { ignoreUnknownKeys = true; isLenient = true })
}
```

Ktor's `client.webSocket(...)` materialises the session via `response.body<DefaultClientWebSocketSession>()`. That dispatches through the response pipeline. `ContentNegotiation.convertResponse` keys off the **response** `Content-Type`, not the requested target type — so as soon as the server replies with `Content-Type: application/json`, CN runs `KotlinxSerializationConverter.deserialize` against `typeInfo<DefaultClientWebSocketSession>()`. `serializer(DefaultClientWebSocketSession::class)` throws because that class isn't `@Serializable`, and the WebSocket builder propagates the exception verbatim — burying the underlying HTTP status and body.

On a clean 101 Switching Protocols upgrade no `Content-Type` is set, so CN silently skips and the bug stays latent. It only fires when the upgrade fails with a JSON error body — which is precisely when you most want to see the real status.

Compounding factor (not a fix on its own): `ZetaSdk.ws()` also sets `header(HttpHeaders.Accept, "application/json")` on the upgrade request (`ZetaSdk.kt:257`), making JSON error bodies more likely.

## Why it's been latent

Every `sdk.ws(...)` caller in `zeta-cli` (`zeta ws`, `zeta popp connector`, the new `zeta popp kartos`) goes through the same path. They only "work" when the server actually completes the WS upgrade. The first time anyone hit a 4xx with a JSON body, the SerializationException pointed in the wrong direction.

## Fix applied (local)

Three surgical changes in `zeta-sdk`:

1. **`network/src/commonMain/kotlin/de/gematik/zeta/sdk/network/http/client/ZetaHttpClient.kt`** — add `installJson: Boolean = true` parameter to `zetaHttpClient(...)` and guard the `install(ContentNegotiation) { ... }` block on it. Default preserves the original behaviour, so the existing `zetaHttpClient_installsContentNegotiation_always` test still passes.

2. **`network/src/commonMain/kotlin/de/gematik/zeta/sdk/network/http/client/ZetaHttpClientBuilder.kt`** — add a `buildForWebSocket()` overload that delegates to the factory with `installJson = false`. Same network/security/monitoring config as `build()`, just no JSON converter.

3. **`zeta-sdk/src/commonMain/kotlin/de/gematik/zeta/sdk/ZetaSdk.kt:249`** — switch the WS client construction from `.build()` to `.buildForWebSocket()`.

Republished `:network:publishJvmPublicationToMavenLocal` + `:zeta-sdk:publishJvmPublicationToMavenLocal`. CLI rebuilt against the patched artifact.

## What to expect after the fix

The kotlinx SerializationException is gone. Ktor will surface its own failure for a non-101 response — typically `NoTransformationFoundException` from `body<DefaultClientWebSocketSession>()`, or an engine-level `IOException`. The wire log (`-vv`) shows the real status and body clearly without the red-herring serializer stack.

The exception still won't carry the HTTP status/body. To get a clean `ResponseException(403, body)` that callers can `runCatching` on, a follow-up could enable `expectSuccess = true` on the WS client (one line in `buildForWebSocket()`). Left as a separate change because it's a behaviour change beyond the masking fix.

## Suggested follow-ups (separate issues)

- **`expectSuccess = true` on the WS client** — turns failed upgrades into a typed `ResponseException` instead of `NoTransformationFoundException`. Worth doing once we agree on the WS error contract.
- **DPoP `htu` claim** — observed value was `https://popp.ref.poppservice.de/` (origin only) for a request whose target URL was `wss://popp.ref.poppservice.de/popp/practitioner/api/v1/token-generation-ehc`. RFC 9449 §4.2 says htu is the request URL with query/fragment stripped — path should remain. Worth auditing `AccessTokenProvider.createDpopToken`.
- **Drop `Accept: application/json` on WS upgrades** (`ZetaSdk.kt:257`) — cosmetic; the header is meaningless for a WS handshake and only encourages JSON error bodies.

## Files referenced

| File | Lines | What |
| --- | --- | --- |
| `zeta-sdk/zeta-sdk/src/commonMain/kotlin/de/gematik/zeta/sdk/ZetaSdk.kt` | 234-264 | `ws()` implementation; builds WS client and adds DPoP/Accept headers |
| `zeta-sdk/network/src/commonMain/kotlin/de/gematik/zeta/sdk/network/http/client/ZetaHttpClient.kt` | 84-151 | `zetaHttpClient(...)` factory; `commonSetup` installs CN |
| `zeta-sdk/network/src/commonMain/kotlin/de/gematik/zeta/sdk/network/http/client/ZetaHttpClientBuilder.kt` | 180-245 | `build(...)` overloads — all funnel through the factory |
| `zeta-cli/cli/src/main/kotlin/de/gematik/zeta/cli/popp/PoppKartosCommand.kt` | 67-90 | Caller that surfaced the bug |

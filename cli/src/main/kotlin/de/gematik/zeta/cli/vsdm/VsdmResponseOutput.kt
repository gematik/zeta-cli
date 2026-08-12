package de.gematik.zeta.cli.vsdm

/**
 * Render a VSDM response as an HTTP/1.1 message — status line, one `Name: Value` header per line, a
 * blank line, then the body verbatim. `zeta vsdm get -i` uses this so a pipe carries the response
 * headers (`ETag`, the VSDM `PZ` / Prüfziffer, …) alongside the bundle. Pure, so it's unit-testable.
 */
internal fun httpResponseText(
    status: Int,
    reason: String,
    headers: Map<String, String>,
    body: ByteArray,
): String = buildString {
    append("HTTP/1.1 ").append(status)
    if (reason.isNotBlank()) append(' ').append(reason)
    append('\n')
    headers.forEach { (name, value) -> append(name).append(": ").append(value).append('\n') }
    append('\n')
    append(body.decodeToString())
}

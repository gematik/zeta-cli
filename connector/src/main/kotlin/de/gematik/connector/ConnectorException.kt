package de.gematik.connector

/**
 * Base for every exception thrown by the hand-written connector client.
 *
 * Catch this in CLI / tooling code to render a single-line user-facing error without a
 * stack trace — these are all "expected" failure modes (bad config, server-side errors,
 * SOAP faults) where the message itself is the diagnostic. Anything *not* extending
 * [ConnectorException] is by definition an unexpected error and warrants a full trace.
 */
abstract class ConnectorException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

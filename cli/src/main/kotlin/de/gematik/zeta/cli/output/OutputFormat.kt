package de.gematik.zeta.cli.output

enum class OutputFormat {
    TEXT,
    JSON,

    /**
     * Bare value, no decoration — for one-shot commands whose primary output is a single
     * string (a JWT, a card handle, a URL). Pipe-friendly: just `command | xargs ...`. Not
     * meaningful for commands that emit a list or structured data; those should treat
     * `RAW` as `TEXT` or reject the value.
     */
    RAW;
}

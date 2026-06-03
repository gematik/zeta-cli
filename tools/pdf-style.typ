// Typst preamble injected via pandoc's --include-in-header for the
// `just readme-pdf` build. Tunes heading spacing, table look, and
// code-block styling so the PDF reads more like a printed manual
// than the default pandoc dump.

// Headings — generous space above, tighter below, so each section
// has a clear visual break from the preceding content.
#show heading.where(level: 1): it => block(above: 3em,   below: 1.2em, it)
#show heading.where(level: 2): it => block(above: 2.6em, below: 1em,   it)
#show heading.where(level: 3): it => block(above: 2em,   below: 0.8em, it)
#show heading.where(level: 4): it => block(above: 1.6em, below: 0.7em, it)

// Tables: header row bold on a light fill, body rows separated by
// hairline rules. Reference tables, not data tables — left-align all
// cells. Pandoc's typst writer emits explicit `align: (auto, …)` per
// column AND wraps the whole table in `align(center)[…]`, so we need
// a cell-level show rule to win the cascade.
#show table: set table(
  inset: (x: 9pt, y: 7pt),
  stroke: (x, y) => (
    top: if y == 0 { 0.8pt + black } else { 0.2pt + luma(75%) },
    bottom: 0.8pt + black,
  ),
  fill: (x, y) => if y == 0 { luma(92%) } else { none },
)
#show table.cell: it => align(left + horizon, it)
#show table.cell.where(y: 0): set text(weight: "bold")

// Tables get extra breathing room from the surrounding flow so they
// don't crowd up against the prose above and below them.
#show table: it => block(above: 1.2em, below: 1.2em, it)

// Block code listings get a tinted background and a subtle border so
// they read as a contained unit rather than blending into the prose.
// Inline `code spans` get a lighter tint plus tight padding so they
// remain compact but visually distinct from surrounding text.
#show raw.where(block: true): it => block(
  width: 100%,
  above: 1.2em,
  below: 1.2em,
  inset: (x: 11pt, y: 9pt),
  radius: 3pt,
  fill: luma(96%),
  stroke: 0.5pt + luma(82%),
  it,
)
#show raw.where(block: false): it => box(
  fill: luma(94%),
  inset: (x: 3pt, y: 0pt),
  outset: (y: 3pt),
  radius: 2pt,
  it,
)

// More space between consecutive paragraphs so dense prose doesn't
// run together, with slightly looser line leading for readability.
#set par(spacing: 1.1em, leading: 0.68em)

// Lists: a hair more space between items so multi-item bullets don't
// pack together.
#set list(spacing: 0.8em)
#set enum(spacing: 0.8em)

# 0115. Converting tool: a format per side with detection, a data tree between formats, best effort with notes

- Status: accepted; amends [0047](0047-module-dependencies-and-shared-modules.md) (`converting` joins the editor
  family); see [0110](0110-tools-menu.md), [0112](0112-codecs-tool.md), [0079](0079-yaml-preview.md)
- Date: 2026-10-30

## Context
Developers keep converting configuration between formats: `application.properties` to `application.yml`, a JSON
response to YAML, TOML to JSON, a JSON array to CSV for a spreadsheet, a `curl` command from the docs to a `.http`
request. Online converters mean pasting configs that contain secrets. Codecs ([ADR 0112](0112-codecs-tool.md)) covers
encodings of a text; formats of structured data need other answers: which format the input is, what happens to what
the target format cannot hold (comments, nulls in TOML, nesting in properties and CSV), and how YAML's many readings of
a scalar are settled.

## Decision
- **Module `converting`** in the editor family, beside `codecs`: a tab of the Tools menu (`Converting`, one per window,
  state outlives the tab, nothing stored across restarts because configs hold secrets) and "Open Selection in
  Converting" in the code editors.
- **Format families as an extension point.** A `FormatFamily` (Spring bean) owns formats that convert into each other:
  it detects its formats in a text, reads a text into its own model and writes the model in any of its formats, with
  options. Formats of different families never meet. The data family (JSON, YAML, TOML, properties, CSV, TSV) is built
  in; other modules contribute families (the HTTP client's requests: [ADR 0116](0116-curl-and-http-requests.md)).
- **Two editable sides, a format picker on each**: like Codecs, the side edited last is the source and the other side
  follows it; a Swap button exchanges the sides. Each picker offers **Detect** and the formats; Detect shows what it
  found ("Detect: YAML"), choosing a format pins it. A pinned format that fails while another one reads the text gets
  a hint offering to switch. Detection scores every format that reads the text; ties go to the side's previous format,
  then the last pinned one. The target picker disables formats that cannot hold the source (a top-level array in TOML)
  with the reason. When detection lands on the target's format, the target switches to the last other format; the
  same format on both sides is allowed when chosen, so the tool also formats.
- **Highlighted in-memory editors** (`CodeEditors.text`) on both sides, highlighted per format; a read error marks its
  position in the source and the status line jumps there.
- **A data tree between data formats**: Jackson's `JsonNode`, a list of documents for multi-document YAML, with
  numbers kept exact (`BigDecimal`, `BigInteger`). JSON and TOML are read and written with Jackson's data formats; CSV and TSV by own code (char offsets for errors, delimiter guess);
  YAML is read and written with SnakeYAML (as the YAML preview, [ADR 0079](0079-yaml-preview.md)); properties are read and written by own code for the Spring Boot dialect and line numbers.
- **YAML 1.2 core schema** on reading: only `true` and `false` are booleans, so GitHub Actions' `on:` stays a key and
  `NO` a string. Writing quotes what a YAML 1.1 reader would take for something else (`yes`, `off`, `~`, `0123`,
  dates). Anchors and aliases are expanded (bounded against alias bombs), `<<` merges resolved, custom tags (`!Ref`)
  keep their value with a note, `!!binary` becomes Base64 text.
- **Spring Boot's properties dialect**: dots nest, `a[0]` indexes lists, `[a.b]` keeps a dotted key part, `#---`
  separates documents like YAML's `---`, `${…}` is plain text. A key with both a value and children is an error at its
  line. Values are strings; "Infer types" (on by default, also for CSV) makes integers, decimals and booleans of them,
  but not values with a leading zero or `+`. Non-ASCII is written as `\u` escapes by default, as Spring Boot reads
  `.properties` as ISO-8859-1.
- **Best effort with notes**: what the target cannot hold is converted by fixed rules and listed in the target's status
  ("2 nulls dropped", "3 comments dropped"): comments are always dropped, nulls dropped in TOML, NaN and infinities
  become strings in JSON, nested values become `a.b` / `a[0]` columns in CSV. Only shapes that cannot be written are
  errors: a top-level array or scalar in TOML or properties, non-records in CSV, several documents in TOML or CSV.
  Several YAML documents become a JSON array (with a note) and `#---` sections in properties.
- **Output options** of the pair shown above the sides: indent 2 or 4, sort keys, compact JSON, escape non-ASCII,
  CSV delimiter and header row, plus the family's own.
- **Off the FX thread**: detection and conversion run after a short debounce on a background thread, the latest edit
  wins; files up to 1 MiB, as in Codecs.
- **Entry points**: Tools → Converting; "Open Selection in Converting" (the whole file without a selection, its format
  pinned from the extension); the explorer's "Convert to ▸" on a file of a data format, which writes the converted file
  beside it (asking before overwriting) and opens it; "Open as Scratch", Open File… and Save as… on each side.

## Consequences
- Comments never survive a conversion; keeping them would need own readers and writers with comment slots for every
  format.
- Some conversions do not round-trip: TOML date-times become strings, a JSON array becomes one YAML document, CSV
  headers with dots stay flat, properties values lose their type without "Infer types".
- New formats join a family (or bring their own) without touching the tool's UI; formats left for later are collected
  in [../ideas/converting-formats.md](../ideas/converting-formats.md).
- Listed in [../index.md](../index.md).

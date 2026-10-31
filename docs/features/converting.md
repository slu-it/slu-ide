# Converting tool

**Modules:** `modules/converting` (package `dev.sluide.converting`: the `FormatFamily` API and the window's
`Converting` bean, the data family in `data`, the FX-free model in `model`, UI in `ui`); menu in `ide` (`ToolsMenu`,
`WindowTools`); decisions in [ADR 0115](../adr/0115-converting-tool.md) and [ADR 0110](../adr/0110-tools-menu.md)

The Converting tool converts structured text between formats: `application.properties` to YAML, a JSON response
to YAML, TOML to JSON, a JSON array to CSV and back. It is a tab of the editor area with Swap and the options of the
two formats at the top and two sides below. Each side has a header with its format picker, Copy, Clear and the File
menu, a highlighted editor and a status line.

## Opening
- **Tools → Converting** opens the tab. There is one Converting tab per window: choosing the item again selects the
  open tab.
- **Open Selection in Converting** in the context menu of every code editor puts the selection on the left side as the
  source; without a selection the item reads **Open in Converting** and takes the whole text. The source is pinned to
  the format of the file's extension (detected for an unknown one) and the tab is shown. Text over 1 MiB disables the
  item ("larger than 1 MiB"). Code: `OpenInConvertingExtension` in `editor`, registered by `ide` after Codecs' item.
- **Convert to ▸** in the explorer's context menu of a single file of a data format (JSON, YAML, TOML, properties, CSV,
  TSV; not requests) lists the other data formats. It converts the file in the background with the window's current
  options and writes the result beside it: same base name, the target's first extension (`.yaml`, but `.yml` when the
  folder has `.yml` files and no `.yaml` file). The file is written through a temporary file and moved over the target;
  a new file gets the usual mode, a replaced file keeps its permissions. If the target exists (a dangling symbolic
  link counts), a question asks before replacing it (Cancel is the default); a folder of that name is an error. The
  conversion reads the file on disk: if a tab has unsaved changes of it, a question first says so (Convert Saved File
  or Cancel). The new file is revealed in the explorer and opened. A conversion that dropped something (comments,
  nulls) tells what in an information dialog after the file is opened; a failure (a read error with line and column,
  a file over 1 MiB or not UTF-8, a shape the target cannot hold) is shown as an error and writes nothing.
  `Converting.conversionTargets` and `Converting.convertFile` do the work; `ide` (`dev.sluide.ide.conversions`:
  `WindowConversions`) writes and opens; the explorer only has the callbacks `conversionTargets` and `convertFile`.
- **File → Open as Scratch** on a side (shown once `ide` sets `openScratch`) creates the scratch file
  `converted.<extension of the side's format>` with its text, shows it in the explorer and opens it
  ([scratch-files.md](scratch-files.md)).
- Shortcut+W closes the tab. Texts, formats and options belong to the window: when the tab is closed and opened again,
  they are as they were. Nothing is stored, as configs hold secrets; after a restart the tool starts empty.

## Two sides
Both sides are editable code editors, highlighted for the side's format, in the editor font. The side you edited last
is the **source**; the other side, the **target**, follows it shortly after every change (detection and conversion run
in the background, the latest edit wins). There is no direction switch.
- **Swap** exchanges the texts and formats of the sides; the source moves with its text.
- When the source cannot be read, its status line shows the error with its line and column ("Line 2, column 6:
  Unexpected character") and the editor underlines the character in red until the next edit; a click on the status
  line selects the character. The target keeps what it showed.
- What the target format cannot hold is converted by fixed rules and listed in the target's status line ("3 comments
  dropped"); a target format that cannot hold the source at all shows why instead ("CSV needs a list of records").
- The status lines name the side's format ("JSON · detected" on a detecting source) and count characters. They take
  one line; a longer text ends in "…" and shows in full as the line's tooltip.
- **Copy** copies the side's text, **Clear** empties both sides, and the **File** menu has **Open File…** and
  **Save as…**.

## Formats and options
Each side's picker offers **Detect** and the formats, grouped under their family's name: **Data** (the formats below)
and **Requests** (cURL and HTTP requests of `.http` files, from the HTTP client: [http-client.md](http-client.md#curl),
[ADR 0116](../adr/0116-curl-and-http-requests.md)). Formats of different families never convert into each other. On the
source, Detect shows what it found ("Detect: JSON"), choosing a format pins it. On the target, Detect follows the
suggestion (see Detection); the picker shows the format, and formats that cannot hold the current source are disabled
with the reason ("CSV — CSV needs a list of records"), checked when the picker opens. Choosing the source's own format
on the target is allowed, so the tool also formats.

The options bar shows the read options of the source's format and the write options of the target's; an option keeps
its value when the formats change.

| Format | Extensions | Reading | Writing | Options |
|---|---|---|---|---|
| JSON | `json`, `jsonc` | comments and trailing commas allowed (comments dropped with a note); duplicate keys and trailing content are errors; numbers kept exact | several documents become an array (with a note); NaN and infinities become strings (with a note) | Indent (2, 4), Compact, Sort keys |
| YAML | `yaml`, `yml` | YAML 1.2 core schema, see below; several documents (`---`) | block style, `---` between documents, quoting as below; multi-line strings as literal blocks | Indent (2, 4), Sort keys |
| TOML | `toml` | TOML 1.0 with exact numbers; date-times become strings | one object only: a top-level array or scalar or several documents are errors; nulls dropped and integers beyond 64 bit written as strings (with notes); tables as `[a.b]`, arrays of tables as `[[a.b]]` sections | Sort keys (writing) |
| Properties | `properties` | Spring Boot's dialect, see below | one object per document, `#---` between documents; nulls and empty objects or lists become empty values (with notes) | Infer types (reading); Sort keys, Escape non-ASCII (writing) |
| CSV | `csv` | RFC 4180, delimiter `,` or `;` guessed from the first records; a field whose quote is never closed is an error at the quote | records become columns; nested values become `a.b` and `a[0]` columns (with a note); several documents or values that are not records are errors | First row is header, Infer types (reading); Delimiter (`,`, `;`), Sort keys (writing) |
| TSV | `tsv`, `tab` | as CSV with a tab | as CSV with a tab | as CSV, without Delimiter |

- **Comments** never survive a conversion; the target's status line lists how many were dropped.
- Data nested more than 500 levels deep is an error, in every format.
- **Sort keys** sorts the keys of every object case-insensitively, then naturally; lists keep their order.
- **YAML reading:** only `true` and `false` are booleans, so GitHub Actions' `on:` stays a key and `NO` a string; there
  are no timestamps or sexagesimal numbers. A number with a leading zero is octal as in YAML 1.1, Spring Boot and
  go-yaml (`0644`, with a note), one with the digits 8 or 9 (`089`) a string. Anchors and aliases are expanded (bounded
  against alias bombs), `<<` merge keys resolved, custom tags (`!Ref`) are dropped with a note and their value kept,
  `!!binary` becomes Base64 text.
- **YAML writing** quotes every string a YAML 1.1 or 1.2 reader would take for something else (`yes`, `off`, `~`,
  `0123`, `1e3`, `12:30`, `2024-01-01`), so the file reads the same in older tools.
- **Properties** follow Spring Boot: dots nest (`server.port`), `a[0]` indexes lists, `[a.b]` keeps a dotted key part,
  `#---` separates documents like YAML's `---`, `${…}` is plain text. A key with both a value and children, a list
  with a missing index or a malformed key is an error at its line; of duplicate keys the last value is kept (with a
  note). Values are text; "Infer types" (on by default) reads integers, decimals and `true`/`false` as such, but not
  values with a leading zero or `+`. "Escape non-ASCII" (on by default) writes `\u` escapes, as Spring Boot reads
  `.properties` files as ISO-8859-1. A top-level array or scalar cannot be written as properties.
- **CSV and TSV:** with "First row is header" (on by default) every row becomes a record keyed by the header; empty or
  duplicate column names get unique ones (`column3`, `a_2`, with a note), and rows with more or fewer cells than the
  header are noted. Without it, rows become lists of cells. "Infer types" (on by default) reads integers, decimals and
  `true`/`false` as such and empty cells as null, but not values with a leading zero or `+`, which stay text. Written
  records take the union of their columns; a list of lists is written row by row (nested values as JSON), a list of
  plain values as one `value` column.

## Detection
On a detecting source, every format that reads the text is scored and the surest one wins: JSON for text starting with
`{` or `[`, YAML for block mappings and lists, TOML for tables and `key = value` lines, properties for compact
`key=value` lines, CSV and TSV for lines with the same number of fields. Ties go to the side's previous format, then
to the last pinned one. When a pinned source format fails to read a text another format reads, a hint at the end of
the source's status line offers to switch ("Looks like YAML" — **Switch**); nothing switches on its own.

The target follows the source: when detection lands on the target's format, the target switches to the format used as
target last, else the usual partner (JSON ↔ YAML, TOML and CSV to JSON, properties to YAML). A target format you
pinned stays.

## Files
**File → Open File…** or a file dropped on a side reads a UTF-8 file of at most 1 MiB into that side, which becomes
the source pinned to the format of the file's extension (detected for other extensions). Errors (missing file, too
large, not UTF-8) show in the side's status line. **File → Save as…** writes the side's text as UTF-8, suggesting the
opened file's name with the extension of the side's format (`application.yaml`, else `converted.json`).

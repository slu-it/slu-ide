# 0187. Detecting the type of a text

- Status: accepted
- Date: 2026-11-19

## Context
Text pasted from the clipboard (explorer Paste Text, File → New Scratch from Clipboard) needs a file extension, and
the user should rarely have to pick it. The Converting tool already scores data formats 1..100 (`FormatFamily.detect`:
JSON, YAML, TOML, Properties, CSV, TSV, cURL, HTTP requests), but `file-explorer` must not depend on `converting`
(unrelated feature modules), and Converting knows nothing of code, markup or Markdown. Its scores also say "YAML 80"
for texts that are something else: a Python `def f():` block, a Markdown heading with a list, `Note: …` lines.

## Decision
- A foundation module `modules/text-types` (plain library: no Spring, JavaFX or third-party libraries), package
  `dev.sluide.texttypes`: `TextType(name, extension)`, the list `TextTypes.all` in picker order (Plain Text, Markdown,
  JSON, YAML, XML, HTML, SVG, CSS, JavaScript, TypeScript, Kotlin, Kotlin Script, Java, Python, Shell Script, SQL,
  HTTP Request, TOML, Properties, CSV, TSV, Mermaid, PlantUML, KQL, Bicep, Diff, No extension) with `byExtension` (with or
  without dot, any case, never No extension), `TypeScore(type, score)`, the seam `TextTypeSource` and
  `TextTypeDetector(sources).detect(text)`.
- **Mechanics.** A leading BOM is dropped; a blank text is Plain Text. The built-in rules see the first
  `RULES_LIMIT` (64 Ki) chars; the given sources see the whole text, and only when it has at most `SOURCE_LIMIT`
  (1 Mi) chars, else they are not called. The highest score wins; a tie goes to the type earlier in `TextTypes.all`,
  whichever rule or source scored it; a best score below `MIN_SCORE` (50) is Plain Text. A source or rule that throws
  (also an `Error`, such as a parser's stack overflow) counts as no scores. A score above 100 breaks the contract and
  is dropped, not clamped (one below 1 cannot reach 50 anyway); No extension and types outside the list are never the
  answer. Stateless, so thread-safe; it runs off the FX thread.
- **Strong signs** (internal sources in `rules/`, one per group):
  - Shebang, 100: `bash`/`sh`/`zsh`/`dash`/`ksh`/`ash`/`mksh` → Shell Script, `python*` → Python, `node`/`nodejs`/
    `deno`/`bun` → JavaScript, also through `/usr/bin/env` with options and `VAR=value`s.
  - Markup, read over the prolog (declaration, PIs, comments, doctype) to the root element's name, nothing parsed:
    an `svg` root 98 → SVG; an HTML doctype 98 → HTML; an `html` root 95 → HTML; an `<?xml` declaration 95 → XML; a
    text that starts with an element and ends with its end tag 70 → HTML for an HTML element's name, else XML.
  - Diff: `diff --git` 95; `---`, `+++` and an `@@ -a,b +c,d @@` hunk line together 92, above Markdown and the code
    heuristics, so a diff of a README or of code stays a diff.
  - Mermaid, 90: the diagram keyword (`graph`/`flowchart` with a direction, `sequenceDiagram`, `classDiagram`,
    `stateDiagram(-v2)`, `erDiagram`, `gantt`, `pie`, `journey`, `gitGraph`, `mindmap`, `timeline`) as the whole first
    line that is not blank or a `%%` comment, and one more line.
  - PlantUML, 95 (added 2026-11-26): a `@start<type>` line (optional name after a blank or in parentheses) as the
    first line that is not blank or a `'` comment, and a later line starting with `@end`; above the code heuristics'
    cap of 90, so a class diagram with Kotlin-like members (`fun`, `val`) stays PlantUML.
  - SQL: statements at a statement start (the first line, after a line ending in `;`, after a blank line; `--`
    comment lines left out) with a statement's shape (`SELECT … FROM`, `INSERT INTO`, `UPDATE … SET`, `DELETE FROM`,
    `CREATE TABLE|VIEW|INDEX`, `ALTER TABLE`, `WITH … AS (`, any case) and one sign that it is not a sentence (a `;`
    at its end, the first keyword in capitals, a `*` between `SELECT` and `FROM` (`SELECT *`, `t.*`, `count(*)`), a
    comparison after `WHERE`/`SET`/`ON`/`AND`/`OR`): one statement 80, two 90. A statement that ends in `.`, `?` or
    `!` is a sentence. "Select the files from the list." stays prose, and so does a paragraph whose `*` is a list item
    or whose `=` stands outside a clause.
  - Bicep: `resource <name> '<Provider>@<yyyy-mm-dd>'` 95, `targetScope =` 90, two `param`/`output` lines with a Bicep
    type 70.
  - Markdown by the number of kinds of its syntax (ATX headings, list items, links, tables, bold, block quotes,
    setext headings, front matter, a pair of fences): 2 kinds 60, 3 kinds 80, 4 or a fence pair with another kind 90.
    One kind is never enough: a `# Note` line, a shell script's `#` comments.
- **Keyword heuristics** for Kotlin, Java, Python, JavaScript, TypeScript and CSS: each sign (a line-start keyword, a
  typical piece of syntax) has a weight, a type's score is the sum of the weights of its signs found, each counted
  once, capped at 90. Signs that two languages share are split by what only one has: `package`/`import` without `;`
  are Kotlin, with `;` Java; `fun`, `val x: T`, `data class` Kotlin, typed declarations ending in `;` Java.
  TypeScript is JavaScript with types: a text with a TypeScript-only sign (`interface X {`, `type X =`, `: string`,
  `as const`, `enum`, typed members or parameters) scores JavaScript's signs plus those as TypeScript, and not as
  JavaScript. The cap of 90 is a tie with a fenced Markdown text, which Markdown wins by list order: a README with a
  code block stays Markdown. A Python `def f(…):` line weighs 60 and a more indented line after a `:` line 25, so a
  Python snippet beats the YAML 80 that Converting gives it; a Kotlin `fun name(` line (no blank before the
  parenthesis) and a `val name: Type` line weigh 50 each, so one function or one typed value is Kotlin on its own.
  Signs that prose can show are held to code's shape: a type annotation (`: string`, `: never`) and a typed member
  are followed by punctuation or the line's end, not by words ("public transport: any line is fine"); `!!` stands
  before `.` or `)` or ends an assignment's line ("Wow!!"); `object Name` is followed by `:`, `{`, `(` or the line's
  end ("object of the game"); a Python class is named in CapWords ("class schedule:"); a Python `with` line has a
  call right after `with` (`with open(…)`) or an `as` ("with the kids:", "with open questions (see below):"); a `for`
  line names at most three loop variables; a JavaScript `let`/`const` has a declaration's shape, a name followed by
  `=`, `:`, `;` or `,`, or a destructuring `{` or `[` ("let me know").
- **Patterns stay linear on hostile text**: a pattern never has two open-ended scans over the same chars next to each
  other (each scan is possessive, `[ \t]*+`, or stops at a char the next element needs), and no unbounded repetition of
  groups (Java's regex recurses per repetition). `HostileTextTest` checks it within a second per text: the rule limit
  filled with repeated pieces, and each keyword of the patterns followed by one long run of a single char.
- **The source seam.** `ide` wires Converting's families in as a `TextTypeSource` (`ConvertingTextTypes` in
  `dev.sluide.ide.clipboard`, built into the one detector that all windows share by the `ClipboardTextTypes` singleton
  bean; its consumers are the explorer's Paste Text and File → New Scratch from Clipboard): each `FormatMatch` maps by
  its format's first extension through `byExtension` (unknown ones dropped, cURL's `sh` is Shell Script, an HTTP
  request's `http` HTTP Request), the score as given, a throwing family counts as nothing. Converting scores prose and
  plain text as data ("Hello, world" CSV 70, "Note: buy milk" and a German sentence YAML 80, `val name: String = "x"`
  YAML 80, a bare URL Properties 40 and, in the requests family, HTTP request 90, "Curl up with a good book" cURL 100,
  "GET milk and eggs" HTTP request 90), and a pasted note must become `.txt`, so a match counts only under these
  guards (`DataTextGuards`, plain line and token scans, linear), chosen by the match's format id, not by its extension:
  - a) JSON always counts.
  - b) Every format but JSON, cURL and HTTP request needs at least 2 non-blank lines.
  - c) YAML needs a structural sign: the first line indented under a bare `key:` line is itself a `key:` entry (key
    without whitespace) or a `- ` item, or the key opens a block scalar (`key: |`, `key: >`, with a chomping mark or
    an indent number) with an indented line under it; or a `---` line; or a flow collection that starts the text; or a
    `- ` item that holds a `key:`. A continued plain scalar ("Todo:" and an indented sentence) and a list of plain
    items do not count. Also no top-level key may contain whitespace: flat `Key: prose` lines are not YAML.
  - d) CSV: every non-blank line has at least two delimiters (`,` or `;`, the same on all lines) outside quotes, so
    three fields, and no line ends with `.`, `!`, `?` or `:`. TSV has only the two lines: prose rarely has a tab on
    every line, and a spreadsheet range, with sentences in its cells, is the main TSV case.
  - e) Properties: every entry has a `=` or `:` and a key of one token without whitespace (comments, blank and
    continued lines pass).
  - f) cURL: after the comment lines the requests family skips (`#`, `::`, `REM`) and one `$ ` prompt, the first word is
    exactly `curl` and a later token holds `://` or is an option (starts with `-` and is longer than it). The requests
    family reads "curl" in any case and with nothing after it. HTTP request: the first line that is no blank or
    comment (`#`, `//`) is `METHOD target` or `METHOD target HTTP/x` and nothing more; a method is one of `.http`'s (a
    copy of `HttpFileParser.METHODS`, which is internal to `http-client`), the target is `http://…`, `https://…` or
    `{{…`, or a path (`/…`) of a raw request copied from a browser, which needs the HTTP version or a `Host:` header
    line. The family also reads a bare `https://…` line, "GET milk" and a request line with words after it as a
    request. Both formats count on one line.
  Known losses, checked with the real `DataFamily` and `RequestFamily`: flat real YAML (`name: x`, `version: 1`) and
  YAML whose first entry under a key has a key with whitespace are `.txt`; a CSV with two columns (`key,value`) or with
  a line ending in sentence punctuation is `.txt`; a YAML top-level key with whitespace (`"my key": 1`), an unindented
  `key:` list of plain items, a Windows `curl.exe` command and a `curl` command with neither a scheme nor an option
  (`curl localhost:8080/health`) are `.txt`; an `.http` text that starts with an
  `@name = value` line, or whose request line has a comment or a space in the URL, is `.txt`. Still passing as data,
  because their shape is that of the data: a list of `- Key: prose` lines, a nested Markdown list under a one-word
  item (`- Setup:` over `  - install java`) and "Todo:" over indented `Key: prose` lines (YAML); lines with two or more
  commas each and no sentence end (a log excerpt, a list of days or names, `Apples, pears, and plums are ripe now`)
  (CSV).
- **Calibration** against Converting's scores (`DataFamily.detect`, measured on the test corpus): Python class and
  `def` snippets YAML 80; Markdown with front matter YAML 85 (multi-document), so front matter needs 4 kinds to win;
  `INSERT`/`UPDATE` lines CSV 70; most code, Markdown and markup none or YAML 10, some JavaScript CSV 30. Known losses,
  where Converting's score stands because Plain Text has no score: prose with a comma on every line is CSV 70, two
  `Key: value` lines of prose YAML 80, two `val x: T = …` Kotlin lines YAML 80, a Markdown heading with a list YAML 80
  (in `ide` the source seam's guards remove these four, the Markdown one by the `- ` rule: Markdown scores 60 or more
  and wins; prose with two or more commas on every line and no sentence end stays CSV, see above).
  Known losses of the rules, kept because a fix would cost real code: prose with a CapWords `class Name:` line or a
  `for word in …:` line followed by an indented line is Python (a class and a loop look exactly so).
- **Tests**: a corpus under `src/test/resources/text-types/<extension>/*.sample` (`txt/` for the negatives) detected
  with the rules alone, the confusable pairs with fake sources that give the measured Converting scores, the mechanics
  with fake sources, and each rule at its edges.

Rejected:
- An ML model or a language-detection library: none on the JVM is worth a dependency for this (Apache Tika tells MIME
  types by magic bytes and file names, not languages in a snippet; Linguist and Guesslang are Ruby and Python, the
  latter a TensorFlow model); a wrong guess costs one pick in the dialog.
- One type list shared with the scratches: left for later (the scratch types have their own order and icons).

## Consequences
- A pasted text gets a likely extension without `file-explorer` knowing Converting; `ide` adds the data formats.
- New languages (Go, Rust, C#, …) are a new rule with its signs and corpus samples; their scores have to stay under the
  strong signs and be checked against Converting's on the corpus.
- The heuristics are guesses: the corpus and the confusion tests are the specification, and a misdetection report
  becomes a sample there first.
- Related: [0047](0047-module-dependencies-and-shared-modules.md) (foundation modules),
  [0115](0115-converting-tool.md) (Converting's detection),
  [0188](0188-paste-clipboard-text-in-the-explorer.md) (Paste Text and New Scratch from Clipboard, the consumers).

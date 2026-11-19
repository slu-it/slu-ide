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
  HTTP Request, TOML, Properties, CSV, TSV, Mermaid, KQL, Bicep, Diff, No extension) with `byExtension` (with or
  without dot, any case, never No extension), `TypeScore(type, score)`, the seam `TextTypeSource` and
  `TextTypeDetector(sources).detect(text)`.
- **Mechanics.** A leading BOM is dropped; a blank text is Plain Text. The built-in rules see the first
  `RULES_LIMIT` (64 Ki) chars; the given sources see the whole text, and only when it has at most `SOURCE_LIMIT`
  (1 Mi) chars, else they are not called. The highest score wins; a tie goes to the type earlier in `TextTypes.all`,
  whichever rule or source scored it; a best score below `MIN_SCORE` (50) is Plain Text. A source or rule that throws
  counts as no scores. A score above 100 breaks the contract and is dropped, not clamped (one below 1 cannot reach 50
  anyway); No extension and types outside the list are never the answer. Stateless, so thread-safe; it runs off the
  FX thread.
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
- **The source seam.** `ide` wires Converting's families in as a `TextTypeSource` (later package): each `FormatMatch`
  maps by its format's first extension through `byExtension` (unknown ones dropped), and a match other than JSON
  needs at least 2 non-blank lines (else "Note: buy milk" is YAML 80 and "Hello, world" CSV).
- **Calibration** against Converting's scores (`DataFamily.detect`, measured on the test corpus): Python class and
  `def` snippets YAML 80; Markdown with front matter YAML 85 (multi-document), so front matter needs 4 kinds to win;
  `INSERT`/`UPDATE` lines CSV 70; most code, Markdown and markup none or YAML 10, some JavaScript CSV 30. Known losses,
  where Converting's score stands because Plain Text has no score: prose with a comma on every line is CSV 70, two
  `Key: value` lines of prose YAML 80, two `val x: T = …` Kotlin lines YAML 80, a Markdown heading with a list YAML 80.
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
  [0115](0115-converting-tool.md) (Converting's detection).

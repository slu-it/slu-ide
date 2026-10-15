# 0031. Own tokenizers for languages without an RSyntaxTextArea lexer

- Status: accepted
- Date: 2026-10-15

## Context
Code views tokenize with the RSyntaxTextArea lexers ([ADR 0005](0005-syntax-highlighting.md)); small formats without
a lexer get line rules on the plain lexer ([ADR 0029](0029-file-name-and-shebang-matching.md)). TOML (`pyproject.toml`,
`uv.lock`, `Cargo.lock`), Bicep and KQL are files projects carry often, but RSyntaxTextArea (4.0.1, still the newest)
has no lexer for them, and line rules cannot follow their multi-line strings, inline tables over several lines,
interpolations or context such as "after `|`". ADR 0029 left them plain text.

Rechecked on 2026-10-15:
- **TextMate grammars via Eclipse tm4e:** still not on Maven Central, only snapshot builds.
- **sora-editor's TextMate port** (`io.github.rosemoe:language-textmate`): published as an Android library (`.aar`)
  only.
- **tree-sitter via jtreesitter** (0.26.1 on Maven Central): the grammars are native libraries per language and
  platform, which our per-platform fat jars and app images would have to build and ship.

## Decision
- A language without a lexer that is too large for line rules gets its own tokenizer: an `internal object`
  implementing `SyntaxHighlighter` (`TomlHighlighter`, `BicepHighlighter`, `KqlHighlighter`), registered in its
  `CodeFileViewer` and in `RSyntaxHighlighter.forLanguage`. It imports nothing from `org.fife`.
- Rules for each tokenizer:
  - one pass over the text, linear in its length; state only per call, so the object is thread-safe;
  - sorted, non-overlapping spans with the existing `TokenStyle`s, chosen as for neighbouring languages (keys
    `property`, headers and types `type`, decorators and commands `annotation`, calls `builtin`);
  - nothing is flagged as an error: unterminated single-line forms end with their line, multi-line ones with the text;
  - a typo must not restyle the rest of the file: at line starts the grammar decides what cannot continue an open
    construct and ends it (TOML: a `key =` line ends open arrays, a line that is only a table header ends all
    brackets; Bicep's graph: a declaration keyword at column 0 resets the bracket depth).
- Context may span the whole text (bracket stacks, open strings), since highlighting always gets the whole text. A
  multi-line opener typed near the top restyles the whole file, which stays fast.
- Bicep's highlighter and its resource graph (`BicepGraph`, [previews.md](../features/previews.md)) read the same
  token stream (`BicepTokens`), so "outside comments and strings" means the same to both.
- KQL keeps only the last few tokens as context: where a statement begins (start of the text, `;`, `<|`, `{`,
  `let x =`, a `(` that is no call, a blank line) decides whether `print` or `range` is an operator. Hyphenated
  operators (`project-away`) are found by their known names, so other `a-b` stays arithmetic.
- Tests: an exact-span `<Language>HighlightingTest` per tokenizer; cases in `HighlightingPerformanceTest` (50,000
  lines, long lines, a real-size `uv.lock`, unterminated and hostile inputs) within 3 seconds; rows in
  `CodeFileViewersTest` and `ViewerCommentSyntaxTest`.

Rejected (again): tm4e, sora-editor and tree-sitter, for the reasons above.

## Consequences
- We maintain each tokenizer and its known gaps ([file-viewers.md](../features/file-viewers.md)); a new language with
  a grammar of this size costs a few hundred lines plus tests.
- An exception to ADR 0005's rejected "hand-written highlighters", for languages without a lexer only; languages with
  a lexer stay on RSyntaxTextArea with corrections. ADR 0029's "TOML, Bicep and KQL stay plain text" is lifted.
- Switching to TextMate grammars later would replace these objects too, behind the same `SyntaxHighlighter`.

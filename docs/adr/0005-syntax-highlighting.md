# 0005. Syntax highlighting with RichTextFX and the RSyntaxTextArea lexers

- Status: accepted; partly superseded by [0020](0020-editing-and-saving-files.md) (the code area is editable, edited code is highlighted in the background)
- Date: 2026-10-12

## Context
Code views need syntax highlighting, starting with Python. Many languages will follow, and we do not want a
dependency per language. JavaFX itself only has plain text controls.

## Decision
- **Rendering:** RichTextFX `CodeArea` (`org.fxmisc.richtext:richtextfx`, BSD-2). It is read-only, shows line
  numbers and gets one CSS class per token. Its POM declares no JavaFX dependencies, so it does not conflict with
  [ADR 0003](0003-javafx-platform-jars.md).
- **Tokenizing:** the lexers ("TokenMakers") of RSyntaxTextArea (`com.fifesoft:rsyntaxtextarea`, BSD-3). It is one
  jar without dependencies and covers about 50 languages. Only the lexers are used, never its Swing editor.
- Both stay behind our own interface. `SyntaxHighlighter` returns `HighlightSpan`s with our own `TokenStyle`s, and
  only `RSyntaxHighlighter` imports `org.fife.*`.
- Lexers that lag behind their language are corrected per language in `RSyntaxHighlighter`'s companion. The
  corrections are: extra keywords, types and builtins; restyling whole token types; string prefixes; multi-line raw
  strings; and per-line regex rules, which YAML needs because its lexer is very basic. Lexer errors are never shown,
  so valid but newer syntax (e.g. Python's `1_000`) is not flagged.
- A language may use a related lexer when its own is missing or weaker: CSS, SCSS and Sass use the LESS lexer, which handles
  nesting and units better than the CSS lexer.
- JSX (`.jsx`, `.tsx`) has no lexer; it is the JavaScript or TypeScript lexer plus line rules for tags and attributes.
  The rules only match whole tags, and line rules never restyle what the lexer found to be a comment or string
  (except in YAML, whose quoted keys are strings to the lexer, and `.env` files, whose lexer starts a comment at
  every `#`), so plain code and strings stay as the lexer saw them.
- Small formats without a lexer (ignore files, `.git-blame-ignore-revs`, SSH config, Caddyfiles, Mermaid, cookie
  jars, PEM) are line rules on the plain lexer (`SYNTAX_STYLE_NONE`), which is never run: it styles nothing and takes
  quadratic time on long lines full of backslashes. Line rules and lexer spans are combined in near-linear time
  ([ADR 0029](0029-file-name-and-shebang-matching.md)).

Rejected:
- **TextMate grammars via Eclipse tm4e:** the best language coverage, but only snapshot Maven builds exist.
- **tree-sitter:** a native library per language and platform.
- **The JavaFX incubator `CodeArea`:** incubating, and the OpenJFX Gradle plugin cannot add it.
- **Hand-written regex highlighters:** one per language to write and maintain.

## Consequences
- A new language is one `CodeFileViewer` subclass with a `SyntaxConstants.SYNTAX_STYLE_*` value, plus corrections
  where its lexer is outdated.
- A Swing library is on the classpath (`java.desktop` is part of the JDK anyway). The fat jar grows by about 2.5 MB.
- Highlighting quality is limited by the lexers. Switching to TextMate grammars later only means replacing
  `RSyntaxHighlighter`.
- Highlighting runs on the JavaFX thread when a file is opened, which is fine for files up to the 10 MiB limit.

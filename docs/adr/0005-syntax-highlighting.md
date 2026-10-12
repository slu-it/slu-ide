# 0005. Syntax highlighting with RichTextFX and the RSyntaxTextArea lexers

- Status: accepted
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

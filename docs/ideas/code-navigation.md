# Idea: Go to Definition

**Status:** idea, explored 2026-10-21; nothing decided or built. First language in mind: Kotlin.

Knowing whether a piece of code is a function declaration, a call, a variable declaration or a use of it, and
navigating with Cmd+click (Ctrl+click on Linux) from a use to its declaration, e.g. from a function call to the
function.

## Where we stand

- Highlighting is lexical only: Kotlin goes through RSyntaxTextArea's `SYNTAX_STYLE_KOTLIN` lexer behind our
  `SyntaxHighlighter` (`RSyntaxHighlighter`, [ADR 0005](../adr/0005-syntax-highlighting.md)). It tells identifiers from
  keywords and strings, but not a declaration from a use.
- Input is no obstacle: code views have their own key and mouse handling ([ADR 0022](../adr/0022-editor-key-bindings.md)),
  and RichTextFX turns a mouse position into a text offset (`hit(x, y)`). "Shortcut held + hover underlines the word,
  click jumps" is plain UI work.
- Missing: `FileViewHost.openFile(file)` opens a file but cannot place the caret in it (Go to Line only works within
  the open file); there is no Back/Forward history of positions.

## Navigation UI (needed by every option)

- Hover underline while the shortcut key is held, click resolves and jumps.
- Opening a file at an offset through the host (caret placed, line scrolled into view).
- Back/Forward through visited positions (Cmd+[ / Alt+←); without it, jumping around gets frustrating fast.
- A small chooser when there is more than one target.
- One small interface behind it, independent of how names are resolved: given a file, its text and an offset, return
  the targets.

## Option 1: one file, from syntax alone

Parse the open file to tell declarations from uses; navigate to local variables, parameters and functions within the
same file.

- Kotlin's grammar (string templates, lambdas, generics, soft keywords) is too large for an own tokenizer as in
  [ADR 0031](../adr/0031-own-tokenizers.md). Candidates: tree-sitter-kotlin through the Java bindings (Foreign Function
  API, fine on JDK 25; a native library per platform, and releases are per platform anyway,
  [ADR 0003](../adr/0003-javafx-platform-jars.md)), or the Kotlin compiler's parser without its resolution step (a heavy
  jar).
- Effort: medium. Precision: good within one file, nothing across files.

## Option 2: project-wide name index (Sublime Text / ctags style)

Index the declarations of all `.kt` files of the project by name; narrow the candidates by imports and package, the
chooser for the rest. Kept up to date through the `FileWatcher`.

- Builds on option 1 plus the index. Effort: medium.
- Roughly right for the project's own top-level functions and classes. Wrong wherever types decide: in `foo.bar()`,
  which `bar` it is depends on the type of `foo`; overloads, extension functions; nothing from libraries or the JDK.
- Honest and lightweight, and the same approach works for every language that has a parser.

## Option 3: real name resolution through a language server (LSP)

An LSP client in Thoreau (e.g. lsp4j); a separate language-server process answers `textDocument/definition`.

- Precision: IDE level, including library sources.
- The client is a one-time investment: Java (jdtls), TypeScript, Python and others follow almost for free, and
  completion, diagnostics, hover and rename can come later.
- Kotlin: JetBrains' official [kotlin-lsp](https://kotlinlang.org/docs/kotlin-lsp.html) supports Gradle and Maven JVM
  projects but is still Alpha (as of the docs of May 2026), [partly
  closed-source](https://cdn.jsdelivr.net/gh/kotlin/kotlin-lsp@main/README.md) and mostly tested with VS Code; recent
  releases of its VS Code extension [ask for region and data-sharing choices on first
  start](https://newreleases.io/project/github/Kotlin/kotlin-lsp/release/kotlin-lsp%2Fv263.4702.0) (unknown whether the
  bare server does). The older community server (fwcd) is superseded by it.
- Hidden costs:
  - managing the server process and its memory (a whole IntelliJ core);
  - keeping it in sync with unsaved edits (the editor already tracks text changes);
  - indexing time before it can answer.
- Project trust: the server imports the Gradle build, i.e. runs the project's build scripts, so it belongs behind a
  `ProjectTrustCheck` ([ADR 0053](../adr/0053-project-trust.md)).
- Embedding the Kotlin compiler's analysis API directly is a worse variant of this option: huge, unstable API, tied to
  one compiler version, Kotlin only.

## Suggested approach

1. Build the navigation UI first, behind the resolver interface; it is needed whichever option wins.
2. Option 2 as the default fits a lightweight IDE; LSP as an optional upgrade where a server is installed.
3. Before committing to LSP: a timeboxed spike, a minimal lsp4j client sending `textDocument/definition` to kotlin-lsp
   on this repository, to measure startup time, memory and behaviour in practice.

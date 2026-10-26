# 0097. More languages on the RSyntaxTextArea lexers, own tokenizers for HCL and GraphQL

- Status: accepted
- Date: 2026-10-26

## Context
A survey on 2026-10-26 found that every UTF-8 text file opens, but many common programming files opened as plain
text. These included `.properties`, C/C++, C#, Go, Rust, Ruby, PHP, Windows batch, Protobuf and Terraform, and XML
or JSON files under extensions the existing viewers did not list. RSyntaxTextArea 4.0.1, still its newest release,
ships lexers for most of these languages ([ADR 0005](0005-syntax-highlighting.md)), but only a handful were used.
Terraform/HCL and GraphQL have no lexer there. Several lexers are older than the language versions people write
today: they miss keywords such as C# `record`, take Rust lifetimes (`'a`) for unclosed character literals, or style
PHP 8 attributes (`#[Route]`) as comments.

## Decision
- **Every useful lexer becomes a viewer.**
  - Compiled: C, C++ (also `.h`), C#, Go (plus line rules for `go.mod` and `go.work`), Rust, D, Scala, Dart, Visual
    Basic, Pascal, Fortran, x86 assembler and VHDL.
  - Dynamic: Ruby (also `Gemfile`, `Rakefile`, …), PHP, Perl, Lua, Clojure, Lisp/Scheme/Racket, Tcl and Windows
    batch.
  - Markup and IDL: Protobuf, LaTeX, Handlebars, JSP, DTD and `.htaccess`.
  - Config: `.properties`.

  The languages are grouped in family files in `highlight`: `CompiledLanguages`, `DynamicLanguages`,
  `ConfigLanguages`, `WebLanguages` and `MarkupLanguages`.
- **Each lexer is checked against the current language version.** A realistic sample is lexed, and what is wrong is
  corrected with the existing knobs. Whatever cannot be fixed cheaply is listed as a known gap in
  [file-viewers.md](../features/file-viewers.md). Words that are often ordinary names (C# `record`/`get`/`set`, Dart
  `base`, Fortran `in`/`out`) are keywords only in their context.
- **`RSyntaxHighlighter` gets two more knobs:**
  - `lexerMasks` blanks regex matches for the lexer at the same offsets. Examples: Rust lifetimes, C/C++ digit
    separators (`1'000`), VB `REM` bodies with quotes, PHP attributes. Line rules still see the original text.
  - `stringStyles` lists the line-rule styles that may sit inside a lexed string and split it, e.g. `$var` in Tcl
    and batch strings. Other rules still leave strings alone, so a keyword inside a batch string stays string.
- **Existing viewers take more names:**
  - XML: `.plist`, `.csproj`, `.props`, `.xaml` and others. `.config` counts only when the file starts with `<`, so
    it is not in `fileExtensions`, and commit diffs show it plain. LaTeX's `.cls` works the same way: it opens as
    LaTeX only when its text looks like LaTeX, since Salesforce Apex and VBA class modules use the extension too.
  - JSON: `.geojson`, `.tfstate` and `.babelrc`.
  - Groovy: `Jenkinsfile`. Config: `.gitmodules`.
  - JSON5 and JSON Lines get code viewers without a preview, because the preview parser rejects several top-level
    values and unquoted keys.
  - `CODEOWNERS`, `.gitattributes` and `.mailmap` get line rules.
- **HCL (Terraform) and GraphQL get their own tokenizers** (`HclHighlighter`, `GraphQlHighlighter`), under the rules
  of [ADR 0031](0031-own-tokenizers.md). `.terraform.lock.hcl` gets the generated-file banner.
- **Left out on purpose:**
  - fish, CMake, Swift, Haskell, F#, R, Elixir and other languages without a lexer;
  - `.prettierrc`/`.eslintrc` without an extension (JSON or YAML, undecidable by name);
  - `.m`/`.mm` (MATLAB or Objective-C), `.cl` (OpenCL or Common Lisp) and `.pp` (Puppet or Pascal);
  - binary files.

## Consequences
- Most source files a polyglot project carries are highlighted, and fenced code in Markdown and notebooks picks up
  the same languages through `MarkupLanguages.forLanguage`.
- Lexer bugs stay ours to work around. The knobs make that cheap, but each correction needs a test in its
  `<Language>HighlightingTest`, and line rules and masks must stay near-linear (`HighlightingPerformanceTest`).
- A file under an extension that two formats share opens in one of them; the doc rows name the clashes (`.d`, gcc
  dependency files).
- Encodings are a separate decision: [ADR 0098](0098-windows-1252-fallback.md).

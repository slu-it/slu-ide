# 0012. File explorer icons from Ikonli's Material Design Icons pack

- Status: accepted
- Date: 2026-10-13

## Context
The file explorer showed plain names. Users want icons for folders, readable files, binary files the IDE cannot
show yet and the languages that have a code viewer, and more file types will follow. JavaFX ships no file icons,
and none of our dependencies (RichTextFX, RSyntaxTextArea, JediTerm) has any.

Options considered:
- **Our own vector icons** (`SVGPath` or `-fx-shape` strings): no dependency, but every icon has to be drawn or
  copied by hand, and JavaFX cannot load `.svg` files.
- **Native OS icons** through Swing's `FileSystemView`: small bitmaps, poor on Linux, ignore the dark theme and need
  `javafx.swing`.
- **Emoji / Unicode characters**: no work, but they look different on every OS and font.
- **Ikonli** (`org.kordamp.ikonli`, Apache 2.0) with one icon-font pack. Packs checked in version 12.4.0:
  - **Material Design Icons** (`materialdesign2`, about 6,900 icons): folders (open/closed), document, and language
    icons for Kotlin, Java, Python, JavaScript, TypeScript, HTML5, Markdown, C/C++/C#, Go, Rust, Ruby, PHP, Swift, …;
    `code-json`, `file-xml-box`; Docker, Kubernetes, Terraform; image, pdf, zip, table files. No binary-file icon, no
    YAML icon.
  - **Codicons** (VS Code, 324 icons): has `file-binary`, but no language logos.
  - Font Awesome 6, Simple Icons, Devicons, fileicons: each misses several of our languages (e.g. Kotlin,
    TypeScript or Java).

## Decision
- `modules/file-explorer` uses `ikonli-javafx` with `ikonli-materialdesign2-pack` only, so all icons share one
  style. `FileIcon` maps every icon of the pack that stands for a file type (language, tool, document, media,
  archive, package, platform, settings/security formats) to file names and extensions; `fileIconFor` picks one by
  folder state, then file name, then extension, then by whether the file can be opened. Icons for file actions
  (`file-move`, `file-sync`, …) are not used. Languages without an icon share `file-code`; Gradle uses `elephant`
  (its logo), Git files `source-branch` (the pack has no Git logo).
- Gaps: binary files use `file-question-outline` ("cannot be read yet"), YAML uses `code-braces`.
- Icons are font glyphs coloured per type in `file-explorer.css`, with dark values in a
  `@media (prefers-color-scheme: dark)` block ([ADR 0009](0009-themes.md)), and sized to the explorer font.
- Whether a file can be opened comes from the `ide` module (`FileExplorerView.canOpenFile`, wired to
  `EditorArea.canOpen`), so the explorer needs no dependency on `file-viewers`.

## Consequences
- A new file type is an enum entry (or a name/extension added to one) and, if needed, a CSS colour; no drawing.
  `FileIconTest` checks that no name or extension is mapped twice and that every style class has a colour.
- One more library, about 1 MB for the pack. Ikonli releases are infrequent (12.4.0 in April 2025); as the packs are
  fonts, that is low risk.
- Ikonli finds its packs with `ServiceLoader`; this works in the Spring Boot fat jar.
- Files without a known extension are read (up to 8 KB, by the viewers' text check) when their cell is first shown;
  the result is cached per tree item and checked again when the file changes.

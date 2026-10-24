# File Viewers

**Module:** `modules/file-viewers` (package `dev.sluide.fileviewers` and its sub-packages, [ADR 0055](../adr/0055-sub-packages-within-modules.md))

The WebView previews (Markdown, HTML, SVG and images, JSON, XML, YAML, CSV, cookie jars, notebooks, Mermaid, PEM
and the Bicep preview) are not in this module: they live in `modules/previews` (package `dev.sluide.previews`,
[previews.md](previews.md), [ADR 0069](../adr/0069-previews-module.md)) and use only the root package of this
module as API, listed at the end. This module keeps the code views, highlighters, diffs, language viewers, .env,
the Bicep graph and `BicepParamFileViewer`.

- `FileViewer` is the extension point: `canOpen(file)` decides cheaply whether the viewer can show a file, and
  `open(file)` creates a `FileView` for it. A `FileView` has the JavaFX `node` and `reload()`, which re-reads the file
  and keeps the scroll position and caret; the editor calls it when the file changes on disk. It never throws away
  unsaved changes. Views in which the file can be edited are `EditableFileView`s with `dirty` and `save()`
  ([text-editing.md](text-editing.md)): code views, plain text and the previews' source. Images and diffs are read-only.
  `EditableFileView.showChanges(diff)` marks the lines changed against an original in the gutter of the code view
  (`Gutter`; code views implement the public column hook `GutterColumns`, e.g. for run icons;
  [git-diff.md](git-diff.md)). `focusText()` gives the focus to the content at once (a code view's text, a
  preview's source unless the preview shows alone), e.g. back from a diff; `focus()` is what the editor calls when the
  view's tab is shown: the main content by [ADR 0050](../adr/0050-focus-when-a-file-tab-is-shown.md) (the preview
  while it shows alone or a diagram zooms in Split), at once or as soon as it is in a scene (`FocusWhenShown`). Code
  views, previews and images never take it after `dispose()`; the default `focus()` cannot drop a pending request.
  `dispose()` is called once when the view is no longer shown (its tab closed, or a diff view shown over the editor
  was hidden again); previews and the image view stop their `WebView` page there, other views do nothing.
  `moved(to)` lets a view follow its file to a new path (a rename in the explorer): code views and previews read and
  save it there from now on, keeping text, unsaved changes, caret and undo history, take the indentation again for
  the new path, and previews render it again from there; other views return `false` by default, and the editor opens the file anew.
  `closeWarning` (default `null`) says why closing the view's tab from the UI should be confirmed first, e.g. while
  a view runs a build; the editor asks with it when the tab closes and, with the other warnings, when the window
  closes.
  `activity` (`ViewActivity`: `NONE` by default, `RUNNING`, `FAILED`; changes on the FX thread) is shown as a marker on
  the view's tab; the view itself sets it back, e.g. from `FAILED` to `NONE` when its tab is shown again
  ([editor-tabs.md](editor-tabs.md)).
- `FileViewers` asks every specific `FileViewer` bean whether it can open a file. Of those that agree it takes the one
  whose name match is strongest (`NameMatch`): the file's path (a `PathFileViewer`, e.g. YAML files in
  `.github/workflows`, which takes part only at its paths), an exact file name, then the extension, then a name pattern; viewers
  other than `CodeFileViewer`s (previews, images, `.http`) count as extension matches, and Spring's bean order only
  decides between equals. So `Dockerfile.md` is Markdown, `Caddyfile.json` and `.env.json` are JSON,
  `Dockerfile.dev` is a Dockerfile and `Dockerfile.dockerignore` an ignore file, whatever the order of the viewers.
  `DefaultTextFileViewer` is always asked last ([ADR 0004](../adr/0004-file-viewer-selection.md),
  [ADR 0029](../adr/0029-file-name-and-shebang-matching.md), [ADR 0074](../adr/0074-path-matched-file-viewers.md)). Returns `null` if no viewer agrees. Before the
  fallback, a text file without an extension that no viewer opens by name goes to the first viewer opening the
  interpreter of its shebang (`ShebangViewer`, implemented by every `CodeFileViewer`), see below.
- `DefaultTextFileViewer` shows any text file as plain text in an editable `CodeView` without highlighting. A file is text if its
  first 8 KiB contain no NUL byte and are valid UTF-8 (`isTextFile`); empty files are text, files over 10 MiB are not.
- Code viewers extend `CodeFileViewer(extensions, highlighter, comment, names, namePatterns, interpreters)` and show
  text files with syntax highlighting and line numbers in an editable `CodeView`. Which files they open is decided by
  `FileMatch`, all case-insensitive: a file extension, an exact file name (`names`, e.g. `dockerfile`, `.bashrc`), or a
  `Regex` matching the whole name (`namePatterns`, e.g. `caddyfile\..+`); `canOpen` looks at nothing else. Of several
  viewers matching a name, `FileViewers` prefers path, then exact name, extension and pattern (above). A file without
  an extension that no viewer opens this way is also opened by the first viewer whose `interpreters` include the one
  its shebang names: `#!/bin/bash`, `#! /bin/sh`, `#!/usr/bin/env node`, `#!/usr/bin/env -S python3 -u`
  (options of `env`, including the argument of `-u`/`--unset`, `-C`/`--chdir` and `-P`, and `NAME=value` are skipped).
  So a name always beats a shebang: a file listed in one viewer's `names` opens there even if its shebang names
  another viewer's interpreter. The interpreter matches with and without its version, so `python3.12` counts as
  `python3.12`, `python3` and `python`, and `python2` as `python`. The launcher `uv run`, with any options
  (`#!/usr/bin/env -S uv run --script` for PEP 723 scripts), counts as `python`; no other launcher is recognised.
  A file with an extension is never matched by its shebang, and a name with a leading dot like `.bashrc` counts as
  having one, so dotfiles go into `names`. `FileViewers` reads the file's 8 KiB text sample once for all viewers and
  takes the shebang from its first line. A code view renders a RichTextFX `CodeArea`, colours the tokens from a `SyntaxHighlighter`
  (`RSyntaxHighlighter` = RSyntaxTextArea lexers, [ADR 0005](../adr/0005-syntax-highlighting.md)) and is styled by
  `syntax.css`; its banners (file changed or deleted on disk, generated file) and find bar by `code-view.css`. Small
  formats without a lexer (ignore files, `.git-blame-ignore-revs`, SSH config, Caddyfiles, Mermaid, cookie jars, PEM)
  are line rules on the plain lexer, which itself is never run (it styles nothing and takes quadratic time on long
  lines full of backslashes). Larger languages without a lexer (TOML, Bicep, KQL) have their own tokenizers
  (`TomlHighlighter`, `BicepHighlighter`, `KqlHighlighter`; [ADR 0031](../adr/0031-own-tokenizers.md)): one linear
  pass over the whole text, never flagging errors. Line rules and lexer spans are combined in near-linear time, so
  large files and long lines stay fast (`HighlightingPerformanceTest`). Edited text is highlighted again in
  the background once typing pauses ([ADR 0020](../adr/0020-editing-and-saving-files.md)). Token classes: `keyword`, `type`, `builtin`, `string`, `number`, `comment`, `annotation`, `property`, `tag`, `attribute`, `operator`.
  The viewer's `comment` parameter (a `CommentSyntax`) says how Shortcut+/ comments out lines; preview viewers take
  one for their source too ([text-editing.md](text-editing.md)).
- Highlighted languages, with the corrections for outdated lexers (configured by family in `highlight`:
  `WebLanguages`, `ScriptLanguages`, `ConfigLanguages`, `MarkupLanguages`; `RSyntaxHighlighter`'s companion
  delegates to them):

  | Viewer | Files | Corrections to the lexer |
  |--------|------------|--------------------------|
  | `PythonFileViewer` | `.py`, `.pyw`, `.pyi`; shebang `python`, `python3` | `async`/`await`/`nonlocal` keywords, `print`/`exec` builtins, string prefixes like `f` belong to the string |
  | `KotlinFileViewer` | `.kt`, `.kts` (not `build.gradle.kts`, see [gradle.md](gradle.md)) | `package` keyword; multi-line raw strings (`"""…"""`). Known gap: ranges like `1..2` show as two numbers |
  | `GroovyFileViewer` | `.groovy`, `.gradle` (Gradle's Groovy DSL; not `build.gradle`, see [gradle.md](gradle.md)) | `trait`/`var`/`record` keywords. Known gaps: dollar-slashy strings (`$/…/$`) are not recognised; `${…}` in a GString stays unstyled code |
  | `JavaFileViewer` | `.java` | none needed (text blocks, records, sealed types work) |
  | `JavaScriptFileViewer` | `.js`, `.mjs`, `.cjs`; shebang `node`, `deno`, `bun` | `from`/`undefined` keywords; `${…}` in template literals stays unstyled code |
  | `TypeScriptFileViewer` | `.ts`, `.mts`, `.cts` | keywords such as `type`, `readonly`, `as`, `keyof`, `satisfies`; types `unknown`, `never`, `object`, `symbol`, `bigint`; decorators |
  | `JsxFileViewer` | `.jsx` | as JavaScript; line rules add JSX tags (`tag`: `<Name` and its `>`/`/>` when the tag is complete on its line or its attributes continue on the next lines, `</Name>`, fragments, a `>`/`/>` alone on a line) and attributes with a value (`attribute`). Code like `a <b && c > d` or `b="x"` stays code, and strings are never restyled. Known gaps: text between tags is lexed as code (e.g. `for` shows as a keyword); boolean attributes are unstyled |
  | `TsxFileViewer` | `.tsx` | as TypeScript plus JSX like `JsxFileViewer`; generics (`useState<T>(`, `<T>(x: T) => T`, `<T,>`) are no tags. Known gap (also in `.ts`): after `= <` the lexer reads E4X markup to the end of the line, so the rest of a generic arrow function's line is unstyled |
  | `JsonFileViewer` (source side of the preview, [json-preview.md](json-preview.md)) | `.json`, `.jsonc`; `Pipfile.lock` | keys as `property`; `//` comments allowed (`/* */` are not recognised by the highlighting; the preview accepts both) |
  | `XmlFileViewer` (source side of the preview, [xml-preview.md](xml-preview.md)) | `.xml`, `.xsd`, `.xsl`, `.xslt`, `.fxml`, `.iml` (IntelliJ modules) | none needed; DTDs/processing instructions styled as `annotation`, entities as `number`, CDATA as `string` |
  | `YamlFileViewer` (source side of the preview, [yaml-preview.md](yaml-preview.md)) | `.yaml`, `.yml` | the lexer only knows comments and quoted strings; line rules add keys (`property`), numbers, `true`/`false`/`null`/`~`, anchors/aliases/tags (`annotation`) and `---`/`...`. Known gap: block scalar content (after `\|`/`>`) is not shown as string |
  | `HtmlFileViewer` (source side of the preview, [previews.md](previews.md)) | `.html`, `.htm`, `.xhtml` | none needed; `<!DOCTYPE>` as `annotation`, entities as `number`, embedded `<script>` and `<style>` highlighted as JavaScript and CSS |
  | `SingleFileComponentFileViewer` | `.vue`, `.svelte` | as HTML; tag names the lexer does not know (`<template>`, components like `<MyCard>`) are made `tag` by the same whole-tag rules as JSX. Known gaps: `<script lang="ts">` is lexed as JavaScript; template expressions (`{{ … }}`, `{…}`, `{#if}`) are plain text |
  | `MdxFileViewer` | `.mdx` | headings and bold as `keyword`, emphasis as `type`, inline code as `string`, links and images as `attribute`, inline HTML as markup with component names as `tag`, `import`/`export` lines as `keyword`; fenced code with a known language highlighted as that language (`FencedCodeHighlighter`), other fences `string` |
  | `CssFileViewer` | `.css` | uses the LESS lexer (handles nesting and units better); at-rules (`@media`, `@supports`, `@layer`, …) as `keyword`; custom properties (`--gap`) and `#id`s as `annotation`. Selectors are `type`, properties and pseudo-classes `keyword`. Known gap: some nested selectors show as `keyword` |
  | `ScssFileViewer` | `.scss` | as CSS; `@mixin`/`@include`/`@use` (lexed as LESS variables) as `keyword`; `$variables`, also inside `#{…}`, as `annotation` |
  | `LessFileViewer` | `.less` | as CSS; `@variables` as `annotation`, known CSS at-rules stay `keyword` |
  | `SassFileViewer` | `.sass` | indented syntax; without braces the lexer styles every word as `type`, so that is dropped and line rules add properties (`keyword`), `=mixin`/`+mixin`, `$variables` and selector lines (`type`). Known gaps: a property without a space after the colon (`margin:0`) and continuation lines of multi-line values look like selectors; `url(//…)` starts a comment |
  | `SqlFileViewer` | `.sql` | keywords of current dialects (`LIMIT`, `OFFSET`, `RETURNING`, `TRUE`, `IF`, window clauses, …), common data types as `type` and functions (`COUNT`, `coalesce`) as `builtin`, each in upper and lower case; the `.` of `a.id` as `operator`. Known gap: mixed case (`Limit`) only for words the lexer knows (`Select`) |
  | `ShellFileViewer` | `.sh`, `.bash`, `.zsh`, `.ksh`; `.bashrc`, `.bash_profile`, `.zshrc`, `.zprofile`, `.profile`; shebang `sh`, `bash`, `zsh`, `dash`, `ksh` | variables (`$NAME`, `${1:-x}`, the `NAME` of `NAME=`) as `annotation`, also in strings; the shebang as `comment`; `function` keyword; `local`, `declare`, `source`, … builtins. Known gap: here-document content is lexed as code |
  | `PowerShellFileViewer` | `.ps1`, `.psm1`, `.psd1`; shebang `pwsh` | variables (`$name`, `$env:PATH`, `${x}`) outside strings as `annotation`; `$true`/`$false`/`$null` keywords in any case; `[string]`, `[int[]]` as `type`, `[CmdletBinding()]` as `annotation`, indexes (`$arr[i]`) plain. Known gap: here-string content is lexed as code |
  | `DockerfileFileViewer` | `Dockerfile`, `Containerfile`, `Dockerfile.*` (not `*.dockerignore`), `*.Dockerfile` | `HEALTHCHECK`, `SHELL` and the `AS` of a build stage (when `AS name` ends the line) as `keyword`; `$VAR`/`${VAR}` outside strings as `annotation`. Known gap: heredocs (`RUN <<EOF`) are plain |
  | `MakefileFileViewer` | `Makefile`, `GNUmakefile`, `.mk` | targets (also `%.o:`) as `type`; `include`, `export`, `override`, `vpath`, … keywords; `$(VAR)`, `${VAR}` and `$@`, `$<`, `$^`, … as `annotation`. Indented with tabs unless `.editorconfig` or the text say otherwise ([text-editing.md](text-editing.md)) |
  | `IniFileViewer` | `.ini` | sections as `type`; keys as `property` (before the first `=` that is no `>=`, `<=`, `!=`, `~=`, `==`, or before a `:` on lines without `=`); `;` and `#` comments. Shortcut+/ with `;`. Known gap: a `#` or `;` inside a value starts a comment |
  | `ConfigFileViewer` | `.cfg`, `.conf`, `.npmrc`, `.editorconfig`, `.gitconfig` | as INI; Shortcut+/ with `#`. nginx-style `.conf` files are read as INI too |
  | `IgnoreFileViewer` | `.gitignore`, `.dockerignore`, `.npmignore`, `.prettierignore`, `.eslintignore`, `.helmignore` (by extension, so `Python.gitignore` too) | no lexer; line rules: `#` comments at the start of a line, a leading `!` as `keyword`, unescaped `**`, `*`, `?` as `operator`; files named exactly `.gitignore` open in `GitignoreFileView` with gutter dots for the lines that have an effect ([gitignore-line-effects.md](gitignore-line-effects.md)) |
  | `GitBlameIgnoreRevsFileViewer` | `.git-blame-ignore-revs` | no lexer; line rules: `#` comments, commit hashes (7 to 64 hex digits) as `number` |
  | `SshConfigFileViewer` | `sshd_config`, `ssh_config` | no lexer; line rules: `#` comment lines, the first word (the option) as `keyword`, what `Host`/`Match` apply to as `type` |
  | `CaddyfileFileViewer` | `Caddyfile`, `Caddyfile.*`, `.caddyfile` | no lexer; line rules: `#` comments, site addresses and snippets (unindented lines opening a block) as `type`, quoted and backtick strings, directives as `keyword`, `@matchers` and `{placeholders}` as `annotation`. Known gaps: a site address without a block shows as a directive; placeholders inside quotes stay string |
  | `TomlFileViewer` | `.toml`; `Pipfile`, `uv.lock`, `poetry.lock`, `pdm.lock`, `Cargo.lock` | no lexer; own tokenizer `TomlHighlighter` (TOML 1.0): `#` comments; whole `[table]`/`[[array]]` headers as `type`; keys (bare, quoted, dotted as one span) as `property` at a line start or after `{`/`,` in inline tables; basic, literal and multi-line strings; integers (`_`, `0x`/`0o`/`0b`), floats, `inf`/`nan`, dates and times (also with a space) as `number`; `true`/`false` as `keyword`. Inline tables may span lines (TOML 1.1). Unclosed brackets do not restyle the rest: at a line start a key with `=` ends open arrays (in an inline table it is the next key), a line that is only a header whose path cannot be a value ends all brackets; a mismatched closer closes down to its opener or is skipped. Known gaps: a value-like header alone on a line in an unclosed array (`[5]`) stays in the array; odd words in values (`123abc`, `0xG1`) show as numbers; an unterminated `"""` makes the rest of the file a string |
  | `BicepFileViewer` (preview, [previews.md](previews.md)) | `.bicep` | no lexer; own tokenizer `BicepHighlighter`: `//` and `/* */` comments; `'…'` strings with escapes and `${…}` interpolations (`${` and `}` as `operator`, the expression as code, also nested), `'''…'''` without; keywords (`param`, `var`, `resource`, `module`, `output`, `existing`, `if`, `for`, …); `string`, `int`, `bool`, `object`, `array` and the name after `type` as `type`; decorators (`@description`, `@sys.description`) as `annotation`; object keys (also quoted, also after a decorator) as `property`; calls as `builtin`; words after `.`, `?.`, `.?` and `::` are members, never keywords; numbers; the operators `?? ?. .? ! == != =~ !~ && \|\| ? : =>`. Known gaps: user-defined type names are `type` only where declared; escapes are not styled on their own |
  | `BicepParamFileViewer` | `.bicepparam` | as Bicep, without preview |
  | `KqlFileViewer` | `.kql`, `.csl` | no lexer; own tokenizer `KqlHighlighter`: `//` comments; strings `'…'`/`"…"` with escapes, verbatim `@'…'`, obfuscated `h'…'`/`h@"…"`, ```` ```…``` ```` over several lines; `\|`, `<\|` and comparisons (`== != <> < > <= >= =~ !~`) as `operator`; tabular operators after `\|` as `keyword`; where a statement begins (start of the text, `;`, `<\|`, `{`, `let x =`, a `(` that is no call, a blank line) only the operators a query begins with (`print`, `range`, `union`, `search`, `find`, `datatable`, `externaldata`, `evaluate`) and `set`, `declare`, `alias`, `pattern`, `restrict`, unless `(` follows directly (a call, except for `datatable`/`externaldata`) or one of `),;=<>!/%.]\|` (a column); hyphenated operators (`project-away`, `mv-expand`, `graph-to-table`) as one keyword anywhere, by their known names (other `a-b` stays plain); `let`, `by`, `and`, … and string operators, also `!has`, `in~`, `!in~`, `!between` and the `_cs` variants, each one keyword; `from`/`to`/`step`/`default` within `range` and `make-series`; types of literals and declarations (`datetime(`, `x: long`, `typeof(long)`) as `type` and the value of `datetime(…)`, `timespan(…)`, `guid(…)` as `number`; numbers, hex and timespans (`1d`, `2hrs`, `30min`, `1.5d`, any case) as `number`; calls as `builtin`; management commands (`.show`, `.create-or-alter`) as `annotation` where a statement begins or first on a line after a `}`. Known gaps: join kinds, render types, `hint.*`, `$left`/`$right` are plain; a command right under a query without a separator is not styled |
  | `EnvFileViewer` ([env-files.md](env-files.md)) | `.env`, `.env.*`, `*.env` | keys as `property`, values as `string` (the lexer's numbers and `true`/`false` stay `number` and `keyword`), `export` as `keyword`; a comment starts at a `#` after a blank only, so `a#b` is a value. Values are masked |

- `ImageFileViewer` shows image files (png, jpg, gif, webp, bmp, ico up to 50 MB), although they are binary
  ([previews.md](previews.md)). It reads and base64-encodes the whole file on the JavaFX thread.
- Images and the SVG, Mermaid and Bicep previews zoom: toolbar, Shortcut + `+`/`-`/`0`/`9`, Shortcut+wheel, pinch
  (`ZoomControls`, [previews.md](previews.md), [ADR 0033](../adr/0033-zooming-images-and-diagrams.md)). Every other
  preview zooms as a document, without Fit ([ADR 0083](../adr/0083-zooming-document-previews.md)).
- Preview viewers (`PreviewFileViewer`) show highlighted, editable source beside a rendered `WebView`, with a Source /
  Split / Preview toolbar; the preview renders the saved text: Markdown ([markdown-preview.md](markdown-preview.md)) and
  HTML, SVG, CSV/TSV, cookie jars (`CookiesFileViewer`, `.cookies`), notebooks, Mermaid (`MermaidFileViewer`, `.mmd`,
  `.mermaid`), JSON (`JsonFileViewer`, `.json`, `.jsonc`, `Pipfile.lock`, [json-preview.md](json-preview.md)),
  XML (`XmlFileViewer`, `.xml`, `.xsd`, `.xsl`, `.xslt`, `.fxml`, `.iml`, [xml-preview.md](xml-preview.md)),
  YAML (`YamlFileViewer`, `.yml`, `.yaml`, [yaml-preview.md](yaml-preview.md)),
  PEM certificates and keys (`PemFileViewer`, `.pem`, `.crt`, `.cer`, `.key`, only with PEM text) and
  the resource graph of Bicep files (`BicepFileViewer`, `.bicep`) in [previews.md](previews.md). Their diffs are highlighted like their source.
  A preview that answers `Preview.outline` with headings (Markdown) gets a table-of-contents button in the toolbar.
- `EnvFileViewer` is a `CodeFileViewer` whose `EnvFileView` wraps the `CodeView` with a **Show values** toolbar and
  masks the values: `CodeView.maskedRanges` adds the style class `masked` over the token styles, below the find bar's
  matches ([env-files.md](env-files.md), [ADR 0030](../adr/0030-masking-env-values.md)).
- `MarkdownFileViewer` (`.md`, `.markdown`) is no code viewer: it shows the Markdown source, highlighted like the
  code views, beside a rendered preview ([markdown-preview.md](markdown-preview.md)). Fenced code in the source is
  highlighted as its language by `FencedCodeHighlighter` when it names a known one (` ```ts `, `~~~python`).
- Diff views: `FileViewers.openDiff(file, TextDiff)` shows a file side by side with an original version (left) in a
  `DiffFileView`: two read-only RichTextFX code areas with titles from the `TextDiff`, the same highlighting as the
  file's code viewer (plain for `DefaultTextFileViewer`; other viewers get no diff, `null`). `LineChange`s are
  `REMOVED` (grey, left only), `MODIFIED` (blue, both sides) or `ADDED` (green, right only), coloured on the line and
  its number (`diff-*` classes in `syntax.css`). Filler rows keep both sides aligned, line numbers are the real ones,
  and both sides scroll together. `show(diff)` re-reads the file with a new diff, `jumpToChangeAt(line)` jumps to the
  change at a line of the file, and while `onBack` is set, an Edit button and Escape go back. Used for git diffs on
  demand ([git-diff.md](git-diff.md)).
- Views set no font of their own: they inherit the editor font that `EditorArea` sets on their node
  ([settings.md](settings.md)). `syntax.css` makes the RichTextFX code area inherit it too, since RichTextFX's own
  stylesheet sets a monospace family on `.code-area`.
- A `FileView` can open other files and web pages through its `host` (`FileViewHost`: `openFile`, `openWebPage`),
  which whoever shows the view sets; the editor area does ([editor-tabs.md](editor-tabs.md)). Views that open nothing
  ignore it. The previews use it for links; HTML previews also take the files they may use from its `projectFolder`.
  `openFile(file, line)` (0-based) opens a file at a line: views with a caret implement `FileView.showLine` (code views
  as Go to Line does, previews in their source); the workflow graph's boxes use it.
  `windowService(type)` gives the service the window registered under exactly `type` (`WindowServices`), e.g. the
  HTTP client's session, or `null` ([ADR 0026](../adr/0026-http-client-window-session.md),
  [ADR 0070](../adr/0070-window-services.md)). `save(file)` saves the open file's editor text as File → Save does
  (the host shows errors): `true` when the file on disk holds the editor's text (saved now, or nothing was unsaved,
  or the view is not editable); `false` by default, when the file is not open or saving failed.
- For viewers of other modules, the root package `dev.sluide.fileviewers` is the API:
  - `CodeEditors.open(file, highlighter, lineComment, blockComment)` (`CodeEditors.kt`) creates the editable code view
    as a `CodeEditor`: `EditableFileView` and `GutterColumns` with `text`, `textProperty`, `onTextChanged` (every
    edit, without building the text), `caretLine`, `caretLineProperty`, `moveCaretTo`, `savedText`,
    `savedTextProperty` (what a preview renders), `topLine` (-1 while no line is shown), `onShownLinesChanged`
    (scrolling, resizing, shown again, a shown line edited) and `showLineAtTop`. `CodeEditors.display()` creates a
    read-only highlighted `CodeDisplay` (texts over 1 MiB stay plain).
  - Viewers that show files as text implement `SourceFileViewer` (`sourceHighlighter`) to get change markers and
    highlighted diffs. Viewers for extensions implement `ExtensionFileViewer` (`fileExtensions`, lower case, no dot)
    and viewers for exact names `NamedFileViewer` (`fileNames`), so that `FileViewers` also picks them by the name
    alone, e.g. for a commit's version of a file. Viewers for a location implement `PathFileViewer`
    (`matchesPath`, helper `isInFolders(file, ".github", "workflows")`): they beat every name match but take part only
    at their paths, looked at without the disk, also for a diff's relative name; the type of file stays with
    `canOpen`/`fileExtensions` ([ADR 0074](../adr/0074-path-matched-file-viewers.md)). Viewers that also open
    extensionless scripts by their shebang implement `ShebangViewer` (`opensInterpreter(interpreter)`, asked only when
    no viewer opens the file by name); `interpreterMatches(interpreter, interpreters)` matches as code viewers do,
    case-insensitive and with or without the version (`ksh93` as `ksh`, `python3.12` as `python3` and `python`).
  - Viewers whose views can run their file (e.g. shell scripts) implement `RunnableFileViewer`, and their views
    `RunnableFileView` (`run()`, FX thread: shows the output and runs the file, asking first what it needs to ask);
    meant for the window to offer Run for the files such a viewer opens (not wired yet).
  - Highlighters: `Highlighters.forLanguage(name)` for embedded code, the named `Highlighters.json`, `xml`, `yaml`,
    `html`, `markdown`, `mermaid`, `bicep`, `groovy`, `kotlin`, `shell`, `cookies` and `pem`, and
    `Highlighters.fenceLanguage(info)` for the language of a code fence.
    `SyntaxHighlighter`, `HighlightSpan` and `TokenStyle` are public.
  - `BicepResourceGraph.of(text)` gives a Bicep file's resource graph as Mermaid (`mermaid`, `isEmpty`, `note`).
  - `SourceSplit(source, other, otherLabel, buttonStyleClass, initial)` puts a source beside another side in a
    horizontal `SplitPane` (`node`), switched by the toggle buttons Source, Split and `otherLabel` (`modeButtons`, not
    focus traversable) or the `mode` property (`SplitMode.SOURCE`, `SPLIT`, `OTHER`; Split by default). Clicking the
    selected button keeps it selected, the divider (0.5 at first) is kept while one side is hidden, and both sides may
    get narrower than their content. Moving the previews and the visual `.env` editor of the HTTP client onto it is
    planned.
  - `FocusWhenShown` focuses a view's node at once or once it is in a scene
    ([ADR 0050](../adr/0050-focus-when-a-file-tab-is-shown.md)); `isTextFile` and `readTextSample` tell text files
    and give their first 8 KB.
  - `HttpFileViewer` (`.http`, `.rest`, module `http-client`) uses them ([http-client.md](http-client.md)).
- To add a viewer, implement `FileViewer` as a `@Component` in any module below `dev.sluide`. For a language with an
  RSyntaxTextArea lexer, add its highlighter to the family file in `highlight` (`WebLanguages.kt`,
  `ScriptLanguages.kt`, `ConfigLanguages.kt` or `MarkupLanguages.kt`) with a one-line delegation in
  `RSyntaxHighlighter`'s companion, extend `CodeFileViewer` like `PythonFileViewer` does, and add a row to
  `CodeFileViewersTest` and a `<Language>HighlightingTest`. A larger language without a lexer gets its own
  `SyntaxHighlighter` object instead ([ADR 0031](../adr/0031-own-tokenizers.md), rules there), plus cases in
  `HighlightingPerformanceTest`. Add its names to `MarkupLanguages.forLanguage` too: that list decides which fenced
  code (` ```py `) in Markdown and which notebook languages are highlighted. It knows `java`; `kotlin` `kt` `kts`;
  `groovy` `gradle`; `python` `py`; `javascript` `js` `mjs` `cjs`; `jsx`; `typescript` `ts`; `tsx`; `json` `jsonc`;
  `xml` `svg`; `html`; `vue` `svelte`; `yaml` `yml`; `css`; `scss`; `sass`; `less`; `markdown` `md`; `mdx`; `sql`;
  `kql` `kusto` `csl`; `bash` `sh` `shell` `zsh` `ksh`; `powershell` `ps1` `pwsh`; `dockerfile` `docker`
  `containerfile`; `makefile` `make` `mk`; `ini` `cfg` `conf` `editorconfig` `gitconfig`; `gitignore`; `ssh-config`
  `sshconfig` `ssh_config` `sshd_config`; `caddyfile` `caddy`; `mermaid` `mmd`; `bicep` `bicepparam`; `dotenv`
  `env`; `toml`.
- Not yet implemented: encodings other than UTF-8, and views of binary files other than images (`.xlsx`, `.pptx`,
  SQLite).

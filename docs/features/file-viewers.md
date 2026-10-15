# File Viewers

**Module:** `modules/file-viewers` (package `dev.sluide.fileviewers`)

- `FileViewer` is the extension point: `canOpen(file)` decides cheaply whether the viewer can show a file, and
  `open(file)` creates a `FileView` for it. A `FileView` has the JavaFX `node` and `reload()`, which re-reads the file
  and keeps the scroll position and caret; the editor calls it when the file changes on disk. It never throws away
  unsaved changes. Views in which the file can be edited are `EditableFileView`s with `dirty` and `save()`
  ([text-editing.md](text-editing.md)): code views, plain text and the previews' source. Images and diffs are read-only.
  `EditableFileView.showChanges(diff)` marks the lines changed against an original in the gutter of the code view
  (`Gutter`; code views implement the public column hook `GutterColumns`, e.g. for run icons;
  [git-diff.md](git-diff.md)). `focusText()` gives the focus to the content (a code view's text).
  `dispose()` is called once when the view is no longer shown (its tab closed, or a diff view shown over the editor
  was hidden again); previews and the image view stop their `WebView` page there, other views do nothing.
  `moved(to)` lets a view follow its file to a new path (a rename in the explorer): code views and previews read and
  save it there from now on, keeping text, unsaved changes, caret and undo history, take the indentation again for
  the new path, and previews render it again from there; other views return `false` by default, and the editor opens the file anew.
- `FileViewers` asks every specific `FileViewer` bean whether it can open a file. Of those that agree it takes the one
  whose name match is strongest (`NameMatch`): an exact file name, then the extension, then a name pattern; viewers
  other than `CodeFileViewer`s (previews, images, `.http`) count as extension matches, and Spring's bean order only
  decides between equals. So `Dockerfile.md` is Markdown, `Caddyfile.json` and `.env.json` are JSON,
  `Dockerfile.dev` is a Dockerfile and `Dockerfile.dockerignore` an ignore file, whatever the order of the viewers.
  `DefaultTextFileViewer` is always asked last ([ADR 0004](../adr/0004-file-viewer-selection.md),
  [ADR 0029](../adr/0029-file-name-and-shebang-matching.md)). Returns `null` if no viewer agrees. Before the
  fallback, a text file without an extension that no viewer opens by name goes to the first viewer opening the
  interpreter of its shebang (`ShebangViewer`, implemented by every `CodeFileViewer`), see below.
- `DefaultTextFileViewer` shows any text file as plain text in an editable `CodeView` without highlighting. A file is text if its
  first 8 KiB contain no NUL byte and are valid UTF-8 (`isTextFile`); empty files are text, files over 10 MiB are not.
- Code viewers extend `CodeFileViewer(extensions, highlighter, comment, names, namePatterns, interpreters)` and show
  text files with syntax highlighting and line numbers in an editable `CodeView`. Which files they open is decided by
  `FileMatch`, all case-insensitive: a file extension, an exact file name (`names`, e.g. `dockerfile`, `.bashrc`), or a
  `Regex` matching the whole name (`namePatterns`, e.g. `caddyfile\..+`); `canOpen` looks at nothing else. Of several
  viewers matching a name, `FileViewers` prefers exact name over extension over pattern (above). A file without
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
  `syntax.css`; its banners (file changed or deleted on disk) and find bar by `code-view.css`. Small formats without
  a lexer (ignore files, `.git-blame-ignore-revs`, SSH config, Caddyfiles, Mermaid, cookie jars, PEM) are line rules
  on the plain lexer, which itself is never run (it styles nothing and takes quadratic time on long lines full of
  backslashes). Line rules and lexer spans are combined in near-linear time, so large files and long lines stay fast
  (`HighlightingPerformanceTest`). Edited text is highlighted again in
  the background once typing pauses ([ADR 0020](../adr/0020-editing-and-saving-files.md)). Token classes: `keyword`, `type`, `builtin`, `string`, `number`, `comment`, `annotation`, `property`, `tag`, `attribute`, `operator`.
  The viewer's `comment` parameter (a `CommentSyntax`) says how Shortcut+/ comments out lines; preview viewers take
  one for their source too ([text-editing.md](text-editing.md)).
- Highlighted languages, with the corrections for outdated lexers (configured in `RSyntaxHighlighter`'s companion):

  | Viewer | Files | Corrections to the lexer |
  |--------|------------|--------------------------|
  | `PythonFileViewer` | `.py`, `.pyw`, `.pyi`; shebang `python`, `python3` | `async`/`await`/`nonlocal` keywords, `print`/`exec` builtins, string prefixes like `f` belong to the string |
  | `KotlinFileViewer` | `.kt`, `.kts` | `package` keyword; multi-line raw strings (`"""…"""`). Known gap: ranges like `1..2` show as two numbers |
  | `JavaFileViewer` | `.java` | none needed (text blocks, records, sealed types work) |
  | `JavaScriptFileViewer` | `.js`, `.mjs`, `.cjs`; shebang `node`, `deno`, `bun` | `from`/`undefined` keywords; `${…}` in template literals stays unstyled code |
  | `TypeScriptFileViewer` | `.ts`, `.mts`, `.cts` | keywords such as `type`, `readonly`, `as`, `keyof`, `satisfies`; types `unknown`, `never`, `object`, `symbol`, `bigint`; decorators |
  | `JsxFileViewer` | `.jsx` | as JavaScript; line rules add JSX tags (`tag`: `<Name` and its `>`/`/>` when the tag is complete on its line or its attributes continue on the next lines, `</Name>`, fragments, a `>`/`/>` alone on a line) and attributes with a value (`attribute`). Code like `a <b && c > d` or `b="x"` stays code, and strings are never restyled. Known gaps: text between tags is lexed as code (e.g. `for` shows as a keyword); boolean attributes are unstyled |
  | `TsxFileViewer` | `.tsx` | as TypeScript plus JSX like `JsxFileViewer`; generics (`useState<T>(`, `<T>(x: T) => T`, `<T,>`) are no tags. Known gap (also in `.ts`): after `= <` the lexer reads E4X markup to the end of the line, so the rest of a generic arrow function's line is unstyled |
  | `JsonFileViewer` | `.json`, `.jsonc` | keys as `property`; `//` comments allowed (`/* */` are not recognised) |
  | `XmlFileViewer` | `.xml`, `.xsd`, `.xsl`, `.xslt`, `.fxml`, `.iml` (IntelliJ modules) | none needed; DTDs/processing instructions styled as `annotation`, entities as `number`, CDATA as `string` |
  | `HtmlFileViewer` (source side of the preview, [previews.md](previews.md)) | `.html`, `.htm`, `.xhtml` | none needed; `<!DOCTYPE>` as `annotation`, entities as `number`, embedded `<script>` and `<style>` highlighted as JavaScript and CSS |
  | `SingleFileComponentFileViewer` | `.vue`, `.svelte` | as HTML; tag names the lexer does not know (`<template>`, components like `<MyCard>`) are made `tag` by the same whole-tag rules as JSX. Known gaps: `<script lang="ts">` is lexed as JavaScript; template expressions (`{{ … }}`, `{…}`, `{#if}`) are plain text |
  | `YamlFileViewer` | `.yaml`, `.yml` | the lexer only knows comments and quoted strings; line rules add keys (`property`), numbers, `true`/`false`/`null`/`~`, anchors/aliases/tags (`annotation`) and `---`/`...`. Known gap: block scalar content (after `\|`/`>`) is not shown as string |
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
  | `IgnoreFileViewer` | `.gitignore`, `.dockerignore`, `.npmignore`, `.prettierignore`, `.eslintignore`, `.helmignore` (by extension, so `Python.gitignore` too) | no lexer; line rules: `#` comments at the start of a line, a leading `!` as `keyword`, unescaped `**`, `*`, `?` as `operator` |
  | `GitBlameIgnoreRevsFileViewer` | `.git-blame-ignore-revs` | no lexer; line rules: `#` comments, commit hashes (7 to 64 hex digits) as `number` |
  | `SshConfigFileViewer` | `sshd_config`, `ssh_config` | no lexer; line rules: `#` comment lines, the first word (the option) as `keyword`, what `Host`/`Match` apply to as `type` |
  | `CaddyfileFileViewer` | `Caddyfile`, `Caddyfile.*`, `.caddyfile` | no lexer; line rules: `#` comments, site addresses and snippets (unindented lines opening a block) as `type`, quoted and backtick strings, directives as `keyword`, `@matchers` and `{placeholders}` as `annotation`. Known gaps: a site address without a block shows as a directive; placeholders inside quotes stay string |
  | `EnvFileViewer` ([env-files.md](env-files.md)) | `.env`, `.env.*`, `*.env` | keys as `property`, values as `string` (the lexer's numbers and `true`/`false` stay `number` and `keyword`), `export` as `keyword`; a comment starts at a `#` after a blank only, so `a#b` is a value. Values are masked |

- `ImageFileViewer` shows image files (png, jpg, gif, webp, bmp, ico up to 50 MB), although they are binary
  ([previews.md](previews.md)). It reads and base64-encodes the whole file on the JavaFX thread.
- Preview viewers (`PreviewFileViewer`) show highlighted, editable source beside a rendered `WebView`, with a Source /
  Split / Preview toolbar; the preview renders the saved text: Markdown ([markdown-preview.md](markdown-preview.md)) and
  HTML, SVG, CSV/TSV, cookie jars (`CookiesFileViewer`, `.cookies`), notebooks, Mermaid (`MermaidFileViewer`, `.mmd`,
  `.mermaid`) and PEM certificates and keys (`PemFileViewer`, `.pem`, `.crt`, `.cer`, `.key`, only with PEM text) in
  [previews.md](previews.md). Their diffs are highlighted like their source.
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
  `windowService(type)` gives a service of the window, e.g. the HTTP client's session, or `null`
  ([ADR 0026](../adr/0026-http-client-window-session.md)).
- For viewers of other modules (`CodeEditors.kt`): `CodeEditors.open(file, highlighter, lineComment, blockComment)` creates the editable code view
  as a `CodeEditor` (`EditableFileView` and `GutterColumns` with `text`, `textProperty`, `caretLine`, `moveCaretTo`),
  `CodeEditors.display()` a read-only highlighted `CodeDisplay` (texts over 1 MiB stay plain). Viewers that show files
  as text implement `SourceFileViewer` (`sourceHighlighter`) to get change markers and highlighted diffs. Highlighters
  for embedded code: `Highlighters.forLanguage(name)`; `SyntaxHighlighter`, `HighlightSpan`, `TokenStyle` and
  `isTextFile` are public. `HttpFileViewer` (`.http`, `.rest`, module `http-client`) uses them
  ([http-client.md](http-client.md)).
- To add a viewer, implement `FileViewer` as a `@Component` in any module below `dev.sluide`. For a language with an
  RSyntaxTextArea lexer, add its highlighter to `RSyntaxHighlighter`'s companion, extend `CodeFileViewer` like
  `PythonFileViewer` does, and add a row to `CodeFileViewersTest` and a `<Language>HighlightingTest`. Add its names
  to `RSyntaxHighlighter.forLanguage` too: that list decides which fenced code (` ```py `) in Markdown and which
  notebook languages are highlighted. It knows `java`; `kotlin` `kt` `kts`; `python` `py`; `javascript` `js` `mjs`
  `cjs`; `jsx`; `typescript` `ts`; `tsx`; `json` `jsonc`; `xml` `svg`; `html`; `vue` `svelte`; `yaml` `yml`; `css`;
  `scss`; `sass`; `less`; `markdown` `md`; `mdx`; `sql`; `bash` `sh` `shell` `zsh` `ksh`; `powershell` `ps1` `pwsh`;
  `dockerfile` `docker` `containerfile`; `makefile` `make` `mk`; `ini` `cfg` `conf` `editorconfig` `gitconfig`;
  `gitignore`; `ssh-config` `sshconfig` `ssh_config` `sshd_config`; `caddyfile` `caddy`; `mermaid` `mmd`;
  `dotenv` `env`.
- Not yet implemented: encodings other than UTF-8, and views of binary files other than images (`.xlsx`, `.pptx`,
  SQLite). TOML (`uv.lock`), Bicep and KQL open as plain text: they have no RSyntaxTextArea lexer
  ([ADR 0029](../adr/0029-file-name-and-shebang-matching.md)).

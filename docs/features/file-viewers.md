# File Viewers

**Module:** `modules/file-viewers` (package `dev.sluide.fileviewers`)

- `FileViewer` is the extension point: `canOpen(file)` decides cheaply whether the viewer can show a file, and
  `open(file)` creates a read-only `FileView` for it. A `FileView` has the JavaFX `node` and `reload()`, which
  re-reads the file and keeps the scroll position and caret; the editor calls it when the file changes on disk.
- `FileViewers` asks every `FileViewer` bean in turn and uses the first that agrees. `DefaultTextFileViewer` is always
  asked last ([ADR 0004](../adr/0004-file-viewer-selection.md)). Returns `null` if no viewer agrees.
- `DefaultTextFileViewer` shows any text file in a non-editable `TextArea`. A file is text if its
  first 8 KiB contain no NUL byte and are valid UTF-8 (`isTextFile`); empty files are text, files over 10 MiB are not.
- Code viewers extend `CodeFileViewer(extensions, highlighter)` and show text files with syntax highlighting and line
  numbers, read-only. They render a RichTextFX `CodeArea`, colour the tokens from a `SyntaxHighlighter`
  (`RSyntaxHighlighter` = RSyntaxTextArea lexers, [ADR 0005](../adr/0005-syntax-highlighting.md)) and are styled by
  `syntax.css`. Token classes: `keyword`, `type`, `builtin`, `string`, `number`, `comment`, `annotation`, `property`, `tag`, `attribute`, `operator`.
- Highlighted languages, with the corrections for outdated lexers (configured in `RSyntaxHighlighter`'s companion):

  | Viewer | Extensions | Corrections to the lexer |
  |--------|------------|--------------------------|
  | `PythonFileViewer` | `.py`, `.pyw`, `.pyi` | `async`/`await`/`nonlocal` keywords, `print`/`exec` builtins, string prefixes like `f` belong to the string |
  | `KotlinFileViewer` | `.kt`, `.kts` | `package` keyword; multi-line raw strings (`"""…"""`). Known gap: ranges like `1..2` show as two numbers |
  | `JavaFileViewer` | `.java` | none needed (text blocks, records, sealed types work) |
  | `JavaScriptFileViewer` | `.js`, `.mjs`, `.cjs` | `from`/`undefined` keywords; `${…}` in template literals stays unstyled code |
  | `TypeScriptFileViewer` | `.ts`, `.mts`, `.cts` | keywords such as `type`, `readonly`, `as`, `keyof`, `satisfies`; types `unknown`, `never`, `object`, `symbol`, `bigint`; decorators |
  | `JsxFileViewer` | `.jsx` | as JavaScript; line rules add JSX tags (`tag`: `<Name` and its `>`/`/>` when the tag is complete on its line or its attributes continue on the next lines, `</Name>`, fragments, a `>`/`/>` alone on a line) and attributes with a value (`attribute`). Code like `a <b && c > d` or `b="x"` stays code, and strings are never restyled. Known gaps: text between tags is lexed as code (e.g. `for` shows as a keyword); boolean attributes are unstyled |
  | `TsxFileViewer` | `.tsx` | as TypeScript plus JSX like `JsxFileViewer`; generics (`useState<T>(`, `<T>(x: T) => T`, `<T,>`) are no tags. Known gap (also in `.ts`): after `= <` the lexer reads E4X markup to the end of the line, so the rest of a generic arrow function's line is unstyled |
  | `JsonFileViewer` | `.json`, `.jsonc` | keys as `property`; `//` comments allowed (`/* */` are not recognised) |
  | `XmlFileViewer` | `.xml`, `.xsd`, `.xsl`, `.xslt`, `.fxml` | none needed; DTDs/processing instructions styled as `annotation`, entities as `number`, CDATA as `string` |
  | `SingleFileComponentViewer` | `.vue`, `.svelte` | as HTML; tag names the lexer does not know (`<template>`, components like `<MyCard>`) are made `tag` by the same whole-tag rules as JSX. Known gaps: `<script lang="ts">` is lexed as JavaScript; template expressions (`{{ … }}`, `{…}`, `{#if}`) are plain text |
  | `YamlFileViewer` | `.yaml`, `.yml` | the lexer only knows comments and quoted strings; line rules add keys (`property`), numbers, `true`/`false`/`null`/`~`, anchors/aliases/tags (`annotation`) and `---`/`...`. Known gap: block scalar content (after `\|`/`>`) is not shown as string |
  | `MdxFileViewer` | `.mdx` | headings and bold as `keyword`, emphasis as `type`, inline code as `string`, links and images as `attribute`, inline HTML as markup with component names as `tag`, `import`/`export` lines as `keyword`; fenced code with a known language highlighted as that language (`FencedCodeHighlighter`), other fences `string` |
  | `CssFileViewer` | `.css` | uses the LESS lexer (handles nesting and units better); at-rules (`@media`, `@supports`, `@layer`, …) as `keyword`; custom properties (`--gap`) and `#id`s as `annotation`. Selectors are `type`, properties and pseudo-classes `keyword`. Known gap: some nested selectors show as `keyword` |
  | `ScssFileViewer` | `.scss` | as CSS; `@mixin`/`@include`/`@use` (lexed as LESS variables) as `keyword`; `$variables`, also inside `#{…}`, as `annotation` |
  | `LessFileViewer` | `.less` | as CSS; `@variables` as `annotation`, known CSS at-rules stay `keyword` |
  | `SassFileViewer` | `.sass` | indented syntax; without braces the lexer styles every word as `type`, so that is dropped and line rules add properties (`keyword`), `=mixin`/`+mixin`, `$variables` and selector lines (`type`). Known gaps: a property without a space after the colon (`margin:0`) and continuation lines of multi-line values look like selectors; `url(//…)` starts a comment |

- `ImageFileViewer` shows image files (png, jpg, gif, webp, bmp, ico up to 50 MB), although they are binary
  ([previews.md](previews.md)).
- Preview viewers (`PreviewFileViewer`) show highlighted source beside a rendered `WebView`, with a Source / Split /
  Preview toolbar: Markdown ([markdown-preview.md](markdown-preview.md)) and the others in [previews.md](previews.md).
  Their diffs are highlighted like their source.
- `MarkdownFileViewer` (`.md`, `.markdown`) is no code viewer: it shows the Markdown source, highlighted like the
  code views, beside a rendered preview ([markdown-preview.md](markdown-preview.md)). Fenced code in the source is
  highlighted as its language by `FencedCodeHighlighter` when it names a known one (` ```ts `, `~~~python`).
- Diff views: `FileViewers.openDiff(file, TextDiff)` shows a file side by side with an original version (left) in a
  `DiffFileView`: two read-only RichTextFX code areas with titles from the `TextDiff`, the same highlighting as the
  file's code viewer (plain for `DefaultTextFileViewer`; other viewers get no diff, `null`). `LineChange`s are
  `REMOVED` (grey, left only), `MODIFIED` (blue, both sides) or `ADDED` (green, right only), coloured on the line and
  its number (`diff-*` classes in `syntax.css`). Filler rows keep both sides aligned, line numbers are the real ones,
  and both sides scroll together. `show(diff)` re-reads the file with a new diff. Used for git diffs
  ([git-diff.md](git-diff.md)).
- Views set no font of their own: they inherit the editor font that `EditorArea` sets on their node
  ([settings.md](settings.md)). `syntax.css` makes the RichTextFX code area inherit it too, since RichTextFX's own
  stylesheet sets a monospace family on `.code-area`.
- A `FileView` can open other files and web pages through its `host` (`FileViewHost`: `openFile`, `openWebPage`),
  which whoever shows the view sets; the editor area does ([editor-tabs.md](editor-tabs.md)). Views that open nothing
  ignore it. The Markdown preview uses it for links.
- To add a viewer, implement `FileViewer` as a `@Component` in any module below `dev.sluide`. For a language with an
  RSyntaxTextArea lexer, add its highlighter to `RSyntaxHighlighter`'s companion, extend `CodeFileViewer` like
  `PythonFileViewer` does, and add a row to `CodeFileViewersTest` and a `<Language>HighlightingTest`.
- Not yet implemented: dark theme, other encodings, image or binary views.

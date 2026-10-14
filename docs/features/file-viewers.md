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
  | `JsonFileViewer` | `.json`, `.jsonc` | keys as `property`; `//` comments allowed (`/* */` are not recognised) |
  | `XmlFileViewer` | `.xml`, `.xsd`, `.xsl`, `.xslt`, `.svg`, `.fxml` | none needed; DTDs/processing instructions styled as `annotation`, entities as `number`, CDATA as `string` |
  | `HtmlFileViewer` | `.html`, `.htm`, `.xhtml` | none needed; embedded `<script>`/`<style>` are highlighted as JavaScript/CSS. Known gap: unquoted attribute values look like attribute names |
  | `YamlFileViewer` | `.yaml`, `.yml` | the lexer only knows comments and quoted strings; line rules add keys (`property`), numbers, `true`/`false`/`null`/`~`, anchors/aliases/tags (`annotation`) and `---`/`...`. Known gap: block scalar content (after `\|`/`>`) is not shown as string |
  | `CssFileViewer` | `.css` | uses the LESS lexer (handles nesting and units better); at-rules like `@media` as `keyword` instead of `string`; custom properties (`--gap`) and `#id`s as `annotation`. Selectors are `type`, properties and pseudo-classes `keyword`. Known gap: some nested selectors show as `keyword` |
  | `ScssFileViewer` | `.scss` | as CSS; `@mixin`/`@include`/`@use` (lexed as LESS variables) as `keyword`; `$variables`, also inside `#{…}`, as `annotation` |
  | `LessFileViewer` | `.less` | as CSS; `@variables` as `annotation` |
  | `SassFileViewer` | `.sass` | indented syntax; without braces the lexer styles every word as `type`, so that is dropped and line rules add properties (`keyword`), `=mixin`/`+mixin`, `$variables` and selector lines (`type`). Known gap: continuation lines of multi-line values look like selectors |

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
- To add a viewer, implement `FileViewer` as a `@Component` in any module below `dev.sluide`. For a language with an
  RSyntaxTextArea lexer, add its highlighter to `RSyntaxHighlighter`'s companion, extend `CodeFileViewer` like
  `PythonFileViewer` does, and add a row to `CodeFileViewersTest` and a `<Language>HighlightingTest`.
- Not yet implemented: dark theme, other encodings, image or binary views.

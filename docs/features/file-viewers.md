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
- `FileViewers` asks every `FileViewer` bean in turn and uses the first that agrees. `DefaultTextFileViewer` is always
  asked last ([ADR 0004](../adr/0004-file-viewer-selection.md)). Returns `null` if no viewer agrees.
- `DefaultTextFileViewer` shows any text file as plain text in an editable `CodeView` without highlighting. A file is text if its
  first 8 KiB contain no NUL byte and are valid UTF-8 (`isTextFile`); empty files are text, files over 10 MiB are not.
- Code viewers extend `CodeFileViewer(extensions, highlighter, comment)` and show text files with syntax highlighting
  and line numbers in an editable `CodeView`. It renders a RichTextFX `CodeArea`, colours the tokens from a `SyntaxHighlighter`
  (`RSyntaxHighlighter` = RSyntaxTextArea lexers, [ADR 0005](../adr/0005-syntax-highlighting.md)) and is styled by
  `syntax.css`; its banners (file changed or deleted on disk) and find bar by `code-view.css`. Edited text is highlighted again in
  the background once typing pauses ([ADR 0020](../adr/0020-editing-and-saving-files.md)). Token classes: `keyword`, `type`, `builtin`, `string`, `number`, `comment`, `annotation`, `property`, `tag`, `attribute`, `operator`.
  The viewer's `comment` parameter (a `CommentSyntax`) says how Shortcut+/ comments out lines; preview viewers take
  one for their source too ([text-editing.md](text-editing.md)).
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
  | `HtmlFileViewer` (source side of the preview, [previews.md](previews.md)) | `.html`, `.htm`, `.xhtml` | none needed; `<!DOCTYPE>` as `annotation`, entities as `number`, embedded `<script>` and `<style>` highlighted as JavaScript and CSS |
  | `SingleFileComponentFileViewer` | `.vue`, `.svelte` | as HTML; tag names the lexer does not know (`<template>`, components like `<MyCard>`) are made `tag` by the same whole-tag rules as JSX. Known gaps: `<script lang="ts">` is lexed as JavaScript; template expressions (`{{ … }}`, `{…}`, `{#if}`) are plain text |
  | `YamlFileViewer` | `.yaml`, `.yml` | the lexer only knows comments and quoted strings; line rules add keys (`property`), numbers, `true`/`false`/`null`/`~`, anchors/aliases/tags (`annotation`) and `---`/`...`. Known gap: block scalar content (after `\|`/`>`) is not shown as string |
  | `MdxFileViewer` | `.mdx` | headings and bold as `keyword`, emphasis as `type`, inline code as `string`, links and images as `attribute`, inline HTML as markup with component names as `tag`, `import`/`export` lines as `keyword`; fenced code with a known language highlighted as that language (`FencedCodeHighlighter`), other fences `string` |
  | `CssFileViewer` | `.css` | uses the LESS lexer (handles nesting and units better); at-rules (`@media`, `@supports`, `@layer`, …) as `keyword`; custom properties (`--gap`) and `#id`s as `annotation`. Selectors are `type`, properties and pseudo-classes `keyword`. Known gap: some nested selectors show as `keyword` |
  | `ScssFileViewer` | `.scss` | as CSS; `@mixin`/`@include`/`@use` (lexed as LESS variables) as `keyword`; `$variables`, also inside `#{…}`, as `annotation` |
  | `LessFileViewer` | `.less` | as CSS; `@variables` as `annotation`, known CSS at-rules stay `keyword` |
  | `SassFileViewer` | `.sass` | indented syntax; without braces the lexer styles every word as `type`, so that is dropped and line rules add properties (`keyword`), `=mixin`/`+mixin`, `$variables` and selector lines (`type`). Known gaps: a property without a space after the colon (`margin:0`) and continuation lines of multi-line values look like selectors; `url(//…)` starts a comment |

- `ImageFileViewer` shows image files (png, jpg, gif, webp, bmp, ico up to 50 MB), although they are binary
  ([previews.md](previews.md)). It reads and base64-encodes the whole file on the JavaFX thread.
- Preview viewers (`PreviewFileViewer`) show highlighted, editable source beside a rendered `WebView`, with a Source /
  Split / Preview toolbar; the preview renders the saved text: Markdown ([markdown-preview.md](markdown-preview.md)) and the others in [previews.md](previews.md).
  Their diffs are highlighted like their source.
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
  notebook languages are highlighted.
- Not yet implemented: encodings other than UTF-8, and views of binary files other than images.

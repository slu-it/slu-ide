# File Viewers

**Module:** `modules/file-viewers` (package `dev.sluide.fileviewers`)

- `FileViewer` is the extension point: `canOpen(file)` decides cheaply whether the viewer can show a file,
  `open(file)` creates a read-only JavaFX node for it.
- `FileViewers` asks every `FileViewer` bean in turn and uses the first that agrees. `DefaultTextFileViewer` is always
  asked last ([ADR 0004](../adr/0004-file-viewer-selection.md)). Returns `null` if no viewer agrees.
- `DefaultTextFileViewer` shows any text file in a non-editable, monospaced `TextArea`. A file is text if its
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

- To add a viewer, implement `FileViewer` as a `@Component` in any module below `dev.sluide`. For a language with an
  RSyntaxTextArea lexer, add its highlighter to `RSyntaxHighlighter`'s companion, extend `CodeFileViewer` like
  `PythonFileViewer` does, and add a row to `CodeFileViewersTest` and a `<Language>HighlightingTest`.
- Not yet implemented: dark theme, other encodings, image or binary views.

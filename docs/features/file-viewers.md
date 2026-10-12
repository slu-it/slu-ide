# File Viewers

**Module:** `modules/file-viewers` (package `dev.sluide.fileviewers`)

- `FileViewer` is the extension point: `canOpen(file)` decides cheaply whether the viewer can show a file,
  `open(file)` creates a read-only JavaFX node for it.
- `FileViewers` asks every `FileViewer` bean in turn and uses the first that agrees. `DefaultTextFileViewer` is always
  asked last ([ADR 0004](../adr/0004-file-viewer-selection.md)). Returns `null` if no viewer agrees.
- `DefaultTextFileViewer` shows any text file in a non-editable, monospaced `TextArea`. A file is text if its
  first 8 KiB contain no NUL byte and are valid UTF-8 (`isTextFile`); empty files are text, files over 10 MiB are not.
- `PythonFileViewer` shows `.py`, `.pyw` and `.pyi` text files with syntax highlighting and line numbers,
  read-only. Corrections for its outdated lexer: `async`/`await`/`nonlocal` are keywords, `print`/`exec` are builtins,
  and string prefixes like `f` belong to the string.
- Code viewers extend `CodeFileViewer(extensions, highlighter)`. It renders a RichTextFX `CodeArea`, colours the
  tokens from a `SyntaxHighlighter` (`RSyntaxHighlighter` = RSyntaxTextArea lexers, [ADR 0005](../adr/0005-syntax-highlighting.md)),
  and is styled by `syntax.css`. Token classes: `keyword`, `type`, `builtin`, `string`, `number`, `comment`,
  `annotation`, `operator`.
- To add a viewer, implement `FileViewer` as a `@Component` in any module below `dev.sluide`. For a language with an
  RSyntaxTextArea lexer, extend `CodeFileViewer` like `PythonFileViewer` does.
- Not yet implemented: highlighting for other languages, dark theme, other encodings, image or binary views.

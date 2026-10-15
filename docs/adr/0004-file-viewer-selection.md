# 0004. File viewers are asked in turn, the default text viewer last

- Status: accepted; partly superseded by [0020](0020-editing-and-saving-files.md) (views of text are editable) and
  [0029](0029-file-name-and-shebang-matching.md) (of the viewers that agree, the best name match wins; shebangs)
- Date: 2026-10-12

## Context
Opened files are shown read-only in tabs. Different file types will need different views later (e.g. highlighting
for Kotlin vs. JSON), and binary files must not be opened at all for now. Adding a new view should not require
changes in the code that opens files.

## Decision
- Views live in their own module `modules/file-viewers`. Every view implements `FileViewer`
  (`canOpen(file)`, `open(file)`) and is a Spring bean.
- `FileViewers` asks the viewers in turn whether they can open a file and uses the first one that agrees.
  `DefaultTextFileViewer` is always asked last, regardless of Spring's bean order, so specific viewers win.
- If no viewer can open a file, the file is not opened.
- `DefaultTextFileViewer` accepts text files only. A file counts as text if its first 8 KiB contain no NUL byte and
  are valid UTF-8; files over 10 MiB are refused so the UI cannot freeze.
- The tab area is a separate module `modules/editor` that depends on `file-viewers` as a deliberate API. This keeps
  the views free of tab handling.

## Consequences
- A new view means adding one `FileViewer` bean; the explorer and the editor area stay unchanged.
- Specific viewers must keep `canOpen` cheap, since it is called on every open.
- Code viewers also open files without an extension by their shebang: only after no viewer took the file by name
  (so names win), with the file read once for all of them, still before `DefaultTextFileViewer`
  ([ADR 0029](0029-file-name-and-shebang-matching.md), [file-viewers.md](../features/file-viewers.md)).
- Text files in other encodings (e.g. Latin-1) are not opened yet.
- Views are created on the JavaFX thread; very slow file systems block the UI while a file is read.

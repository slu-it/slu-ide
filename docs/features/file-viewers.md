# File Viewers

**Module:** `modules/file-viewers` (package `dev.sluide.fileviewers`)

- `FileViewer` is the extension point: `canOpen(file)` decides cheaply whether the viewer can show a file,
  `open(file)` creates a read-only JavaFX node for it.
- `FileViewers` asks every `FileViewer` bean in turn and uses the first that agrees. `DefaultTextFileViewer` is always
  asked last ([ADR 0004](../adr/0004-file-viewer-selection.md)). Returns `null` if no viewer agrees.
- `DefaultTextFileViewer` shows any text file in a non-editable, monospaced `TextArea`. A file is text if its
  first 8 KiB contain no NUL byte and are valid UTF-8 (`isTextFile`); empty files are text, files over 10 MiB are not.
- To add a viewer, implement `FileViewer` as a `@Component` in any module below `dev.sluide`.
- Not yet implemented: syntax highlighting, other encodings, image or binary views.

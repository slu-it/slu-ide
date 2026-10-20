# 0064. JSON preview

- Status: accepted
- Date: 2026-10-20

## Context
JSON files (`.json`, `.jsonc`, `Pipfile.lock`) only had a highlighted source. Large or deeply nested files are hard to
read as text; a formatted view whose objects and arrays fold, with Collapse All and Expand All, makes them easy to
survey.

## Decision
- **A `Preview` in the shared `PreviewFileView`** ([ADR 0017](0017-webview-previews.md)): Source, Split and Preview
  beside the highlighted source, like CSV and Markdown. `JsonFileViewer` is a `PreviewFileViewer` (and a
  `NamedFileViewer` for `Pipfile.lock`), so diffs, the generated-file banner and editing stay those of the source.
- **Nested `<details>` from a streaming Jackson parse** (`JsonRenderer`): Java comments and trailing commas are allowed
  and dropped, numbers are shown as spelled, a byte order mark is skipped, key order and duplicate keys are kept. An
  error shows a notice and the tree up to it. At most `MAX_VALUES` values are rendered (the rest is left out with a
  note), so huge files do not freeze the preview.
- **Rendered on save** like every preview ([ADR 0020](0020-editing-and-saving-files.md)), not while typing.
- **Opens in Split, the source focused:** the formatted view is at hand while the file stays editable at once (the
  default `PreviewMode.SPLIT`; a non-zooming preview in Split focuses its source, ADR 0050).
- **Folds survive re-renders:** before the page is replaced, the closed containers are read from it
  (`sluide.folds.collapsed()`) as JSON Pointers and passed to the next render, so saving and reloading from disk keep
  them (the page keeps the pointers it could not find a container for, so an invalid or empty save in between loses
  none). Duplicate keys share a pointer and fold together.
- **`Preview.folds`:** previews with foldable sections get Collapse All and Expand All (`FoldControls`) on the toolbar
  while the preview shows.
- **Scroll sync:** containers and values carry their source line (`data-line`).

Rejected: a native `TreeView` like the environment-file editor ([ADR 0057](0057-visual-environment-editor.md)),
which has no scroll sync, needs a third copy of the mode toolbar and shows a tree instead of the JSON text; rendering
while typing (against ADR 0020); a read-only formatted code view with code folding (the code view has no folding).

## Consequences
- JSON gets the same toolbar, scroll sync and save behaviour as the other previews, and the fold buttons are available
  to future previews.
- `PreviewFileViewer.kt` was split by responsibility ([ADR 0054](0054-keep-source-files-small.md)) into `Preview.kt` and
  `PreviewToolbar.kt`.
- `JsonFileViewer` moved to `dev.sluide.fileviewers.json` and is no longer a `CodeFileViewer`; the HTTP client's
  environment files still go to their own viewer by name.
- Added to [../index.md](../index.md).

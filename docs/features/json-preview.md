# JSON preview

**Module:** `file-viewers` — `JsonFileViewer` and `JsonPreview` / `JsonRenderer` in the shared `PreviewFileView`
([previews.md](previews.md)); decision in [ADR 0064](../adr/0064-json-preview.md)

JSON files show their highlighted source with a formatted, foldable view beside it.

- **Files:** `.json`, `.jsonc` and Pipenv's `Pipfile.lock`. The HTTP client's environment files
  (`http-client.env.json`, `http-client.private.env.json`) keep their [visual editor](http-client-env-editor.md),
  notebooks (`.ipynb`) their [rendered view](previews.md).
- **Modes:** the toolbar's **Source**, **Split** and **Preview**. A file opens in **Split**, with the focus in the source
  so it can be edited at once ([ADR 0050](../adr/0050-focus-when-a-file-tab-is-shown.md)); Source hides the formatted
  view, Preview shows it alone.
- **What it shows:** the JSON indented, one member or element per line, keys, strings, numbers and `true`/`false`/
  `null` in the colours of the source's highlighting, numbers as written in the file. Empty objects and arrays are
  plain `{}` and `[]`; every other object and array is a node with a triangle. Closed, a node shows
  `{ … } 3 keys` or `[ … ] 1 item` after its key. Key order and duplicate keys are kept.
- **Folding:** click a node to close or open it. **Collapse All** and **Expand All** on the left of the toolbar (while
  the preview shows) close or open every node.
- **Folds are kept:** when the file is saved or changes on disk, the preview is rendered again and
  every node that was closed stays closed (nodes are identified by their JSON Pointer, so duplicate keys fold
  together; a node that is new or moved opens). An invalid, empty or cut-off save in between does not lose the folds
  either; they come back when the text is valid again, but a node missing from a valid save is forgotten.
- **Scroll sync:** in Split the preview scrolls with the source like the other previews.
- **Updates on save**, not while typing; the preview shows the saved text.
- **JSONC:** `//` and `/* */` comments and trailing commas are accepted and left out of the formatted view (the source
  keeps them). A byte order mark is ignored.
- **Errors:** invalid JSON shows the error above what could be read before it.
- **Large files:** at most 100 000 values are shown; a note below says so. The source always shows everything.

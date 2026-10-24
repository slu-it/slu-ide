# YAML preview

**Module:** `previews` — `YamlFileViewer` and `YamlPreview` / `YamlRenderer` in the shared `PreviewFileView`
([previews.md](previews.md)); decision in [ADR 0079](../adr/0079-yaml-preview.md)

YAML files show their highlighted source with a formatted, foldable view beside it, like [JSON](json-preview.md) and
[XML](xml-preview.md).

- **Files:** `.yml` and `.yaml`. GitHub Actions workflows (directly in `.github/workflows`) keep their
  [graph view](github-actions-graph.md).
- **Modes:** the toolbar's **Source**, **Split** and **Preview**. A file opens in **Split**, the focus in the source
  so it can be edited at once ([ADR 0050](../adr/0050-focus-when-a-file-tab-is-shown.md)).
- **Zoom:** the formatted view zooms as a document: [previews.md](previews.md#zooming-documents).
- **What it shows:** one entry or item per line in block style, indented by 2, in the colours of the source's
  highlighting: keys, quoted and block scalars, booleans and null, numbers, anchors, aliases, tags and directives.
  Flow collections (`{a: 1}`, `[x, y]`) are shown expanded; empty ones stay `{}` and `[]`. Scalars show as written
  (quotes, escapes, `0x1F`); multi-line scalars and block scalars (`|`, `>`) are indented under their key. Anchors and
  aliases show as written and are never expanded (`<<: *base`). Duplicate keys are kept. Comments are left out.
- **Collections:** a non-empty mapping or sequence is a node with a triangle; closed, it shows `… 3 keys` (`1 key`) or
  `… 2 items` (`1 item`). A list item that is a mapping starting with a plain entry shows compactly
  (`- name: web … 3 keys`). Keys that span lines or are collections show as `? key` / `: value`.
- **Several documents** in one file are separated by `---` lines; directives (`%YAML`, `%TAG`) are shown. Documents do
  not fold themselves, only their contents.
- **Folding:** click a node to close or open it. **Collapse All** and **Expand All** on the left of the toolbar (while
  the preview shows) close or open every node.
- **Folds are kept** when the file is saved or changes on disk, by the node's path (`/0/spec/containers/1`: the
  document's index, then keys and item indices); a new or moved node opens. An invalid, empty or cut-off save in
  between does not lose the folds; a node missing from a valid save is forgotten.
- **Copy Formatted:** the button after the fold buttons (while the preview shows) puts the formatted text, as the view
  shows it, on the clipboard: block style, indented with 2 spaces, flow collections expanded, scalars as written,
  comments dropped, `---` between documents, a trailing newline (a block scalar that ended the file without one gets
  `-` chomping, `|-`, so its value stays the same). It reads back as the same YAML. It is the saved text,
  so unsaved edits are not included, and there is no node limit. If the saved file is invalid or empty (or holds only
  comments) nothing is copied and "Not copied: <reason>" (the error notice's text, or "The file is empty") shows beside
  the button; "Copied" and the message go away after 3 seconds, on the next click and when the file is rendered again.
- **Scroll sync:** in Split the preview scrolls with the source like the other previews (an entry is placed at its
  key's line).
- **Updates on save**, not while typing. A byte order mark is ignored.
- **Errors:** invalid YAML shows `Line N, column M: …` (with the line the unclosed or misindented part started on)
  above what could be read before it. Tabs used for indentation are errors, as in YAML.
- **Safe by design:** tags never create objects and aliases are never expanded, so YAML bombs and type gadgets do
  nothing.
- **Large files:** at most 100 000 nodes are shown, and nesting deeper than 200 levels is an error; a note or notice
  says so. The source always shows everything.

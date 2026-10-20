# XML preview

**Module:** `file-viewers` — `XmlFileViewer` and `XmlPreview` / `XmlRenderer` in the shared `PreviewFileView`
([previews.md](previews.md)); decision in [ADR 0065](../adr/0065-xml-preview.md)

XML files show their highlighted source with a formatted, foldable view beside it, like [JSON](json-preview.md).

- **Files:** `.xml`, `.xsd`, `.xsl`, `.xslt`, `.fxml` and `.iml`. SVG files keep their image preview, HTML files
  their [page preview](previews.md), notebooks their rendered view.
- **Modes:** the toolbar's **Source**, **Split** and **Preview**. A file opens in **Split**, the focus in the source
  so it can be edited at once ([ADR 0050](../adr/0050-focus-when-a-file-tab-is-shown.md)).
- **What it shows:** one node per line, indented, in the colours of the source's highlighting: tags, attribute names,
  attribute values, comments, processing instructions and the DOCTYPE. CDATA shows as CDATA. Text is trimmed and
  whitespace-only text left out; the `<?xml?>` declaration is left out. Prefixes and `xmlns` attributes show as written.
- **Elements:** one with child elements or mixed content is a node with a triangle; its start tag with all attributes
  stays visible when it is closed, followed by `… </name> 3 children` (`1 child`). Text-only and empty elements are a
  single line (`<version>1.0</version>`, `<x/>`).
- **Folding:** click a node to close or open it. **Collapse All** and **Expand All** on the left of the toolbar (while
  the preview shows) close or open every node.
- **Folds are kept** when the file is saved or changes on disk, by the element's path
  (`/project[1]/dependencies[1]/dependency[2]`: the index counts earlier siblings of the same name); a new or moved
  element opens. An invalid, empty or cut-off save in between does not lose the folds; an element missing from a valid
  save is forgotten.
- **Scroll sync:** in Split the preview scrolls with the source like the other previews (an element is placed at the
  line its start tag starts on).
- **Updates on save**, not while typing. A byte order mark is ignored.
- **Errors:** invalid XML shows `Line N, column M: …` above what could be read before it; an undeclared entity is an
  error.
- **Safe by design:** a DOCTYPE is shown as written but never loaded or expanded, so external entities (XXE) and
  entity bombs do nothing.
- **Large files:** at most 100 000 nodes are shown, and nesting deeper than 1000 levels is an error; a note or notice
  says so. The source always shows everything.

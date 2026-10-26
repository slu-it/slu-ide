# XML preview

**Module:** `previews` — `XmlFileViewer` and `XmlPreview` / `XmlRenderer` in the shared `PreviewFileView`
([previews.md](previews.md)); decision in [ADR 0065](../adr/0065-xml-preview.md)

XML files show their highlighted source with a formatted, foldable view beside it, like [JSON](json-preview.md).

- **Files:** `.xml`, `.xsd`, `.xsl`, `.xslt`, `.fxml`, `.iml`, `.plist`, `.csproj`, `.fsproj`, `.vbproj`,
  `.props`, `.targets`, `.xaml`, `.resx`, `.nuspec` and `.wsdl`, and `.config` files whose text starts with `<`
  (commit diffs go by name only, so `.config` files show there as plain text). SVG files keep their image preview, HTML files
  their [page preview](previews.md), notebooks their rendered view.
- **Modes:** the toolbar's **Source**, **Split** and **Preview**. A file opens in **Split**, the focus in the source
  so it can be edited at once ([ADR 0050](../adr/0050-focus-when-a-file-tab-is-shown.md)).
- **Zoom:** the formatted view zooms as a document: [previews.md](previews.md#zooming-documents).
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
- **Copy Formatted:** the button after the fold buttons (while the preview shows) puts the formatted text, as the view
  shows it, on the clipboard: indented with 2 spaces, comments, processing instructions, CDATA and the DOCTYPE kept,
  the `<?xml?>` declaration dropped, a trailing newline. Every text segment is trimmed and put on its own lines, so
  whitespace-sensitive content (mixed content, `xml:space="preserve"`) changes. Character and entity references are
  written decoded, except what the formatter must escape (`&`, `<`, `]]>`, and in attribute values `"`, tab and line
  breaks, which would read back as spaces). It is the saved text, so unsaved edits are not included, and there is no
  node limit. If the saved file is invalid or empty nothing is copied and "Not copied: <reason>" (the error notice's
  text, or "The file is empty") shows beside the button; "Copied" and the message go away after 3 seconds, on the next
  click and when the file is rendered again.
- **Scroll sync:** in Split the preview scrolls with the source like the other previews (an element is placed at the
  line its start tag starts on).
- **Updates on save**, not while typing. A byte order mark is ignored.
- **Errors:** invalid XML shows `Line N, column M: …` above what could be read before it; an undeclared entity is an
  error.
- **Safe by design:** a DOCTYPE is shown as written but never loaded or expanded, so external entities (XXE) and
  entity bombs do nothing.
- **Large files:** at most 100 000 nodes are shown, and nesting deeper than 250 levels is an error; a note or notice
  says so. The source always shows everything.

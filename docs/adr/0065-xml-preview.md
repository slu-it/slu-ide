# 0065. XML preview

- Status: accepted
- Date: 2026-10-20

## Context
XML files (`.xml`, `.xsd`, `.xsl`, `.xslt`, `.fxml`, `.iml`) only had a highlighted source. Build files, schemas and
layouts nest deeply; the formatted, foldable view that JSON got ([ADR 0064](0064-json-preview.md)) helps here as well.

## Decision
- **The second foldable preview, on shared parts.** `XmlFileViewer` is a `PreviewFileViewer` with `XmlPreview`
  (Split, source focused, scroll sync, `Preview.folds`). What was JSON-only moved to shared names: `fold-tree.js`
  (`sluide.folds.collapsed()`, the `#fold-state` element), `fold-tree.css` (`.fold-tree`, `.fold-note`, `.fold-error`,
  body class `fold-page`), `FoldState` (reads the old page's folds, writes `#fold-state`) and `HtmlPage.escape`.
- **JDK StAX (`XMLInputFactory`), streaming**, no new dependency. A jsoup XML parse was rejected: it is lenient and
  would silently repair malformed XML instead of reporting it. A DOM parse was rejected: a streaming parse keeps
  memory bounded (`MAX_NODES`, and nesting is capped at 1000 levels). `newDefaultFactory()` is used so that a StAX
  implementation on the class path can never replace the hardened JDK parser.
- **No XXE:** `SUPPORT_DTD` and `IS_SUPPORTING_EXTERNAL_ENTITIES` are off, `javax.xml.accessExternalDTD` is empty and
  there is no resolver. A DOCTYPE is shown as written (taken from the source, because the parser's own text is cut
  short at some declarations) but never loaded or expanded; entities it declares are undeclared, so a reference to
  one is an error, and a billion-laughs document cannot expand. The parser is namespace-unaware (prefixes and `xmlns`
  attributes show as written) and not coalescing (CDATA stays CDATA). Since JDK 24 even the built-in entities
  (`&amp;` …) count against `jdk.xml.maxGeneralEntitySizeLimit` and `jdk.xml.totalEntitySizeLimit` (100,000 each), so
  valid files with more references failed; both are set to 0, which is safe because with DTDs off only built-in and
  character references can occur.
- **Kept and dropped:** comments, processing instructions, CDATA and the DOCTYPE are kept; the `<?xml?>` declaration
  and whitespace-only text are dropped; text is trimmed. Text and attribute values are escaped as XML again (`&`, `<`
  and, in attributes, `"`), so those built-in entities show as written and character references show decoded.
- **Elements:** one with child elements or mixed content is a `<details>` (summary: the start tag with all its
  attributes; closed: `… </name> 3 children`); text-only and empty elements are single lines and do not fold.
- **Paths:** `data-path` is XPath-like with an index at every step (`/project[1]/dependencies[1]/dependency[2]`);
  the index counts earlier siblings of the same name, so later changes do not renumber it. As in JSON, an element
  that moves or is new opens, and folds of a missing element are forgotten on a valid save.
- **Errors:** a notice `Line N, column M: …` (the parser's `ParseError at …` prefix removed; an unclosed element names
  its start line; a file that ends inside markup, such as a tag, comment, CDATA section, processing instruction or
  reference, says so) above the part that parsed, with open elements closed.
- **Scroll sync:** an element's `data-line` is the line its start tag starts on
  (StAX reports where it ends; the start is found from the source).

## Copy Formatted
A toolbar button (`Preview.copiesFormatted`, `CopyFormattedControls`) puts the formatted text on the clipboard. It
reuses the XML parse of the view (no second parser) through `XmlText`, which writes the tree as plain text without the
page's cap. It copies the saved text, which is what the view shows; unsaved edits are not included. On a parse error or
an empty file nothing is copied: the clipboard stays untouched and a message beside the button says "Not copied:
<reason>", with the error notice's text or "The file is empty".

## Consequences
- XML opens in Split with the source focused, like JSON; `.svg`, `.html` and notebooks keep their own viewers.
- JSON and XML share one fold script, stylesheet and state; a further foldable preview needs only a renderer.
- `XmlFileViewer` moved to `dev.sluide.fileviewers.xml` and is no longer a `CodeFileViewer`.
- Documents whose internal DTD subset contains `]>` inside a quoted value or a comment are reported as errors
  (the JDK parser skips the DTD textually).
- Added to [../index.md](../index.md).

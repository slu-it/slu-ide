# Previews

**Module:** `file-viewers` — `PreviewFileViewer` / `PreviewFileView` (shared with the
[Markdown preview](markdown-preview.md)) and one viewer per format; decisions in
[ADR 0016](../adr/0016-markdown-preview.md), [ADR 0017](../adr/0017-webview-previews.md),
[ADR 0018](../adr/0018-html-preview-server.md) and [ADR 0033](../adr/0033-zooming-images-and-diagrams.md)

Every preview shows the highlighted source beside the rendered file in a `WebView`, with a toolbar for **Source**,
**Split** and **Preview**. The source can be edited; the preview shows the saved text and updates when the file is
saved or changes on disk; links open in the system browser or a Thoreau tab
and never navigate the preview (`FileViewHost`, [file-viewers.md](file-viewers.md)).

## Zooming images and diagrams (`ZoomControls`)
Images, SVG files, Mermaid files and Bicep resource graphs zoom; Markdown and notebook previews do not.
- **Toolbar** (on the left; in previews only while the preview shows, not in Source): zoom out, the scale in percent,
  zoom in, **100%** and **Fit** (selected while fitted). Tooltips name the shortcuts.
- **Keys** (while the preview or its zoom controls have the focus, never in the source beside it; the zoom buttons and
  the Source / Split / Preview toggles never take the focus): Shortcut + `+` (also `=` and numpad `+`) zooms in,
  Shortcut + `-` zooms out, Shortcut + `0` shows 100%, Shortcut + `9` fits.
- **Focus** ([ADR 0050](../adr/0050-focus-when-a-file-tab-is-shown.md)): when its tab is opened or chosen, an
  image, a preview shown alone (SVG, notebook, PEM) or a zooming preview in Split (Mermaid, Bicep) focuses its web
  view, so the keys work at once; typing in the source beside a diagram needs a click there. Other previews in Split
  (Markdown, HTML, CSV) and previews in Source (Bicep by default) focus their source. Renaming or moving an image to a
  name of the same type keeps its view and the focus; to another type (e.g. png to jpg) the tab gets a new view, which
  takes the focus if the old view had it (a rename in the file tree leaves it there). The zoom percentage has a fixed
  width (that of "1600%"), so the buttons beside it do not move.
- Zoom in and out go to the next of 10, 25, 33, 50, 67, 75, 100, 125, 150, 200, 300, 400, 600, 800, 1200, 1600%,
  keeping the middle of the view in place. **Shortcut+wheel** and **pinching** on a trackpad zoom smoothly around the
  pointer. Zooming stays within 10%–1600%.
- **Fit** shrinks the image or diagram until all of it shows, and never enlarges it: a small one shows at 100%, a huge
  one below 10% (zooming out from there keeps that scale). While fitted it follows the size of the view. Every view
  opens fitted.
- A **click** (on an image's page anywhere, on a diagram's page on the diagram) goes from fitted to 100% (at the
  pointer) and from any other scale back to fit; the pointer is a magnifier with + while fitted, with − otherwise.
- The zoom (fitted or the scale) is kept when the file is saved, changes on disk or the theme changes.
- A zoomed image or diagram scrolls to every edge.

## HTML (`.html`, `.htm`, `.xhtml`) — `HtmlFileViewer`
- The page is served by a local server (`PreviewServer`, on `127.0.0.1`) with the files of its project, so its
  relative styles, images and scripts work, also those above its folder (`../css`, as on Javadoc pages). It keeps the
  author's styles in every theme. Source is highlighted as HTML.
- **What the page can reach:** the project folder's files, without hidden files and folders (`.git`, `.env`), and
  nothing outside it, not even through symbolic links. A file the project folder does not serve (outside it, opened
  without a project, in a hidden folder such as `.github`, or behind a symbolic link leading out) gets the folder it
  really is in instead; for a file in the home folder that is the whole home folder, which the warning names.
- **Scripts:** off by default. The **Scripts** toggle in the toolbar runs them for this tab (the page reloads), after
  a warning: its scripts can read those files and send them to the web. Every newly opened tab starts with scripts
  off again.
- **Navigation:** a page other than the file itself never shows. Within 2 seconds after a mouse press, a key press
  or a drop onto the preview (a link, a submitted form, a dropped file), the target opens elsewhere — web pages in the
  system browser, files in a tab — and the file is shown again; links to other files open them in a tab, also links
  climbing out of the served folder. The input counts once, for the first navigation after it, but any of it does:
  a click only meant to focus the preview lets a page's own navigation in the next 2 seconds open its target.
  Without such input (a meta refresh, a script setting `location` or reloading the page, a client-side router
  changing the address) the preview stops with a notice and **Show the page again**; a change of the file on disk
  shows it again too. So a page can neither open things on its own nor reload in a loop. Anchors (`#part`) stay.
- The server answers a folder's URL (`docs/`; `docs` is redirected there) with its `index.html`, as web servers do,
  e.g. for frames and scripts; a folder without one is not found, and folders are never listed. A clicked link to a
  folder opens nothing, since no tab shows a folder.
- If the server cannot start, the preview says so instead of staying blank.
- Even with scripts off, the page loads what it references, including remote images, styles and frames.
- On a change on disk the page reloads at its scroll position. Changes to the files it uses (CSS, images) show on the
  next reload.

## SVG (`.svg`) — `SvgFileViewer`
- Opens in **Preview**: the image on a checkered background (transparency shows; light or dark with the theme),
  fitted to the view and zoomable (see above). Source is highlighted as XML.
- The SVG is shown as an `<img>` (`data:` URI), so its scripts never run.
- **Export** beside the zoom controls writes the SVG as a PNG at 100 %, 200 % or 300 % beside it:
  [svg-png-export.md](svg-png-export.md).

## Images (png, jpg/jpeg, gif, webp, bmp, ico) — `ImageFileViewer`
- Image files up to 50 MB open although they are binary: the image on the same checkered page as SVG, with the zoom
  controls above (see above) and its pixel size and file size below. No source side; a changed file is shown again,
  at the same zoom.
- The whole file is read and encoded into the page (base64, a third larger) on the JavaFX thread, when it opens and
  on every change: a large image holds up the UI for a moment.
- Modified images open normally, never as a diff.

## CSV and TSV (`.csv`, `.tsv`) — `CsvFileViewer`
- A table beside the plain text: the first row is the header (sticky); click a header to sort by it, again for
  descending (numbers as numbers, empty cells last). Side by side, the table scrolls with the source.
- Parsing follows RFC 4180: quoted fields with delimiters, line breaks and `""`. The delimiter is a tab for `.tsv`;
  for `.csv` a comma, or `;` if the first line has more of those — then numbers sort with decimal commas (`1.234,5`).
  Blank lines are skipped; short rows are padded; a leading byte order mark (Excel's "CSV UTF-8") is ignored.
  Scroll sync keeps working after sorting.
- At most 10,000 rows are shown, with a note saying how many there are.

## Cookie jars (`.cookies`) — `CookiesFileViewer`
- IntelliJ's `http-client.cookies` (tab-separated) as a sortable table beside the text, like CSV: the first line
  `# domain path name value date` is the header without its `# ` and shows as a comment in the source. The table
  scrolls with the source.
- Quotes are part of the values (`"dark"` stays quoted; `CsvRenderer` with `quotes = false`).

## Jupyter notebooks (`.ipynb`) — `NotebookFileViewer`
- Opens in **Preview**: the notebook as Jupyter shows it, with its stored outputs; Source shows the JSON. Nothing is
  run. No scroll sync (JSON lines do not match cells).
- Markdown cells render like Markdown files (math, diagrams, attached images). Code cells are highlighted in the
  notebook's language (`language_info`, else the kernel's, else Python) with `In [n]:`.
- Outputs, the richest form first: PNG/JPEG/SVG images, HTML (sanitised like Markdown, e.g. pandas tables), Markdown,
  plain text; streams (stderr tinted), and errors with their traceback (ANSI colours removed).
- HTML outputs that sanitise to nothing (plotting libraries store mostly scripts) fall back to their plain text.
- A file that is no valid JSON, or not shaped like a notebook, shows "Not a valid notebook: …".

## Mermaid (`.mmd`, `.mermaid`) — `MermaidFileViewer`
- Opens in **Split**: the highlighted source beside the diagram, drawn by the bundled Mermaid like ` ```mermaid `
  fences in Markdown ([markdown-preview.md](markdown-preview.md)): `securityLevel: 'strict'`, the light or dark
  Mermaid theme, drawn again when the theme changes. No scroll sync.
- The diagram is zoomable (see above): at first fitted, so at its actual size if it fits, else scaled down until it
  fits below the note above it. A theme change and a reload keep the zoom; the page scrolls when it is larger.
- A diagram that does not parse shows Mermaid's message under "Syntax error" (e.g. `Parse error on line …`, the line
  as Mermaid counts it, or an unknown diagram type) instead of the diagram. An empty file shows nothing.
- The source goes into the page as text only, never as HTML.
- Highlighting: the diagram type on its first line (after comments and front matter) and its direction (also
  `flowchart-elk`); statements (`subgraph`, `end`, `classDef`, `participant`, …) at the start of a line unless a
  shape or link follows; `%%` comments and `%%{init: …}%%` directives; the `---` lines around front matter as
  annotations; strings; links and arrows (`-->`, `-.->`, `-. text .->`, `==>`, `->>`, `-x`, `<|--`, `||--o{`, …) as
  operators; `|labels|` as strings. ` ```mermaid ` and ` ```mmd ` fences in Markdown sources are highlighted the same
  way (only ` ```mermaid ` is drawn). Shortcut+/ comments out with `%%`.
- Known gaps: statement words used as node names without a link on their line, and arrows inside node text, are
  styled anyway.

## Bicep resource graph (`.bicep`) — `BicepFileViewer`
- Opens in **Source** (highlighted, [file-viewers.md](file-viewers.md)); **Preview** or **Split** draws the saved
  file's resources and modules as a Mermaid `flowchart LR` (`BicepGraph`), shown like a Mermaid file: zoomable and
  fitted at first, drawn again on a theme change, Mermaid's message if it fails. No scroll sync.
- Nodes: resources (symbol and the last segment of the type, without API version), modules (subroutine shape, symbol
  and path; a long path keeps its end), child resources nested in a body, `existing` ones dashed, loops marked
  "(loop)". Params, vars and outputs are not drawn.
- Edges go from a resource or module to each one its body uses (`dependsOn`, `storage.id`, `parent::child`, spreads
  `...x`), also through `var`s and chains of them. Only the edge to a parent (by nesting or `parent: x`) is labelled
  "parent". Comments and strings do not count, interpolations do.
- A child's body starts at its `{` (or the `[` of a loop), so `= if (…) {` children keep theirs. A short name is first
  a child of the enclosing resources (innermost first, up to 32 levels), then a top-level declaration; a child's
  name used outside its parent gives no edge. A body ends at the next top-level declaration (`assert`, `extends`, …
  too); a declaration keyword at column 0 also ends it and resets the bracket depth, so unbalanced brackets do not
  swallow the rest (an unindented nested resource counts as top-level).
- At most 200 nodes and 400 edges (Mermaid draws at most 500 edges and 50,000 characters), with a line above the
  diagram saying what was left out. A file without resources or modules shows "No resources or modules in this file."
- Labels keep only ASCII letters, digits, blanks and `_-./:@(),`; every other character becomes an entity code
  (`#35;`), so a name can neither leave its label nor start a Mermaid statement; ids are `r_<n>`, and
  `securityLevel: 'strict'` stays. Nodes are no links (no `click`).

## PEM certificates and keys (`.pem`, `.crt`, `.cer`, `.key`) — `PemFileViewer`
- Opens only files with `-----BEGIN ` in their first 8 KiB: binary (DER) certificates are no text and are not
  opened, a `.key` without PEM text opens as plain text. BEGIN and END lines must be lines of their own.
- Opens in **Preview**: one section per block, in file order (`PemRenderer`). No scroll sync. The source is
  highlighted: BEGIN/END lines as keywords, headers such as `Proc-Type:` as properties.
- Certificates (`CERTIFICATE`, `TRUSTED CERTIFICATE`, `X509 CERTIFICATE`): subject and issuer (RFC 2253, the issuer
  marked self-signed when the certificate verifies with its own key), serial number, valid from and until (UTC),
  public key algorithm and size, signature algorithm, alternative names (types with byte values "(not shown)"), key
  usage, extended key usage, CA flag with path length, SHA-256 fingerprint. A badge: Valid, "Expires in N days"
  (within 30 days), Expired or Not yet valid. In chains, each one as "Certificate 1 of 2: example.com" (its common name).
- Keys show only their type and whether they are encrypted, never key material: PKCS #8 with algorithm and size,
  encrypted PKCS #8 with its scheme, OpenSSL's `RSA`/`EC`/`DSA PRIVATE KEY` encrypted as `Proc-Type` says with the
  cipher from `DEK-Info`, OpenSSH keys with key type and cipher, `PUBLIC KEY` with algorithm and size.
- Certificate requests: subject and public key (a minimal DER reader plus the JDK, no library). Other labels are
  "Not shown in detail"; a block that cannot be read shows its label and the error; a note counts BEGIN lines
  without an END line.
- All text is HTML-escaped. Styles in `pem.css`, after `preview.css`, with `body.dark` rules.

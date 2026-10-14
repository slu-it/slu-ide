# Previews

**Module:** `file-viewers` — `PreviewFileViewer` / `PreviewFileView` (shared with the
[Markdown preview](markdown-preview.md)) and one viewer per format; decisions in
[ADR 0016](../adr/0016-markdown-preview.md) and [ADR 0017](../adr/0017-webview-previews.md)

Every preview shows the highlighted source beside the rendered file in a `WebView`, with a toolbar for **Source**,
**Split** and **Preview**. It updates when the file changes on disk; links open in the system browser or a SluIde tab
and never navigate the preview (`FileViewHost`, [file-viewers.md](file-viewers.md)).

## HTML (`.html`, `.htm`, `.xhtml`) — `HtmlFileViewer`
- The page is loaded from disk, so its relative styles, images and scripts work. It keeps the author's styles in
  every theme. Source is highlighted as HTML.
- **Scripts:** off by default. The **Scripts** toggle in the toolbar runs them for this tab (the page reloads), after
  a warning: the page is loaded from disk, so its scripts can read other files on this computer and send them to the
  web. Every newly opened tab starts with scripts off again.
- **Navigation:** a page other than the file itself never shows. Right after a click or key press in the preview
  (a link, a submitted form), the target opens elsewhere — web pages in the system browser, files in a tab — and the
  file is shown again. Without such input (a meta refresh, a script setting `location`, a client-side router changing
  the address) the preview stops with a notice and **Show the page again**; a change of the file on disk shows it
  again too. So a page can neither open things on its own nor reload in a loop. Anchors (`#part`) stay.
- Even with scripts off, the page loads what it references, including remote images, styles and frames.
- On a change on disk the page reloads at its scroll position. Changes to the files it uses (CSS, images) show on the
  next reload.

## SVG (`.svg`) — `SvgFileViewer`
- Opens in **Preview**: the image on a checkered background (transparency shows; light or dark with the theme),
  fitted to the view; a click switches to actual size and back. Source is highlighted as XML.
- The SVG is shown as an `<img>` (`data:` URI), so its scripts never run.

## Images (png, jpg/jpeg, gif, webp, bmp, ico) — `ImageFileViewer`
- Image files up to 50 MB open although they are binary: the image on the same checkered page as SVG (click for
  actual size), with its pixel size and file size below. No source side; a changed file is shown again.
- Modified images open normally, never as a diff.

## CSV and TSV (`.csv`, `.tsv`) — `CsvFileViewer`
- A table beside the plain text: the first row is the header (sticky); click a header to sort by it, again for
  descending (numbers as numbers, empty cells last). Side by side, the table scrolls with the source.
- Parsing follows RFC 4180: quoted fields with delimiters, line breaks and `""`. The delimiter is a tab for `.tsv`;
  for `.csv` a comma, or `;` if the first line has more of those — then numbers sort with decimal commas (`1.234,5`).
  Blank lines are skipped; short rows are padded; a leading byte order mark (Excel's "CSV UTF-8") is ignored.
  Scroll sync keeps working after sorting.
- At most 10,000 rows are shown, with a note saying how many there are.

## Jupyter notebooks (`.ipynb`) — `NotebookFileViewer`
- Opens in **Preview**: the notebook as Jupyter shows it, with its stored outputs; Source shows the JSON. Nothing is
  run. No scroll sync (JSON lines do not match cells).
- Markdown cells render like Markdown files (math, diagrams, attached images). Code cells are highlighted in the
  notebook's language (`language_info`, else the kernel's, else Python) with `In [n]:`.
- Outputs, the richest form first: PNG/JPEG/SVG images, HTML (sanitised like Markdown, e.g. pandas tables), Markdown,
  plain text; streams (stderr tinted), and errors with their traceback (ANSI colours removed).
- HTML outputs that sanitise to nothing (plotting libraries store mostly scripts) fall back to their plain text.
- A file that is no valid JSON, or not shaped like a notebook, shows "Not a valid notebook: …".

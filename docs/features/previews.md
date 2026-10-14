# Previews

**Module:** `file-viewers` — `PreviewFileViewer` / `PreviewFileView` (shared with the
[Markdown preview](markdown-preview.md)) and one viewer per format; decisions in
[ADR 0016](../adr/0016-markdown-preview.md), [ADR 0017](../adr/0017-webview-previews.md) and
[ADR 0018](../adr/0018-html-preview-server.md)

Every preview shows the highlighted source beside the rendered file in a `WebView`, with a toolbar for **Source**,
**Split** and **Preview**. The source can be edited; the preview shows the saved text and updates when the file is
saved or changes on disk; links open in the system browser or a SluIde tab
and never navigate the preview (`FileViewHost`, [file-viewers.md](file-viewers.md)).

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
  fitted to the view; a click switches to actual size and back. Source is highlighted as XML.
- The SVG is shown as an `<img>` (`data:` URI), so its scripts never run.

## Images (png, jpg/jpeg, gif, webp, bmp, ico) — `ImageFileViewer`
- Image files up to 50 MB open although they are binary: the image on the same checkered page as SVG (click for
  actual size), with its pixel size and file size below. No source side; a changed file is shown again.
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

## Jupyter notebooks (`.ipynb`) — `NotebookFileViewer`
- Opens in **Preview**: the notebook as Jupyter shows it, with its stored outputs; Source shows the JSON. Nothing is
  run. No scroll sync (JSON lines do not match cells).
- Markdown cells render like Markdown files (math, diagrams, attached images). Code cells are highlighted in the
  notebook's language (`language_info`, else the kernel's, else Python) with `In [n]:`.
- Outputs, the richest form first: PNG/JPEG/SVG images, HTML (sanitised like Markdown, e.g. pandas tables), Markdown,
  plain text; streams (stderr tinted), and errors with their traceback (ANSI colours removed).
- HTML outputs that sanitise to nothing (plotting libraries store mostly scripts) fall back to their plain text.
- A file that is no valid JSON, or not shaped like a notebook, shows "Not a valid notebook: …".

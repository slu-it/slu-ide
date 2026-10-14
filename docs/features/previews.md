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
- **Scripts:** off by default. The **Scripts** toggle in the toolbar runs them for this tab (the page reloads); every
  newly opened tab starts with scripts off again.
- **Navigation:** any page other than the file itself (a clicked link, a submitted form, a script setting
  `location`) is turned back; web pages open in the system browser, files in a tab. Anchors (`#part`) stay.
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

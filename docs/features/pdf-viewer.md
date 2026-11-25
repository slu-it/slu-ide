# PDF viewer

**Module:** `modules/pdf` (package `dev.sluide.pdf`: `PdfFileViewer`; package `dev.sluide.pdf.engine`: `PdfDocument`,
`PdfSetup`; package `dev.sluide.pdf.view`: the view, page column, workers, toolbar, zoom, password prompt); decision in
[ADR 0192](../adr/0192-pdf-viewer.md)

`.pdf` files open read-only in a tab: all pages stacked in one scrolling column, rendered by Apache PDFBox as images,
with a toolbar above. Nothing is fetched from the network and nothing is stored.

## Opening
- Opening a PDF returns at once; "Loading…" shows until the document is open. Closing the tab drops results that
  arrive late.
- The view opens the file twice, once per thread: one document renders the pages (and reads the outline), the other
  extracts their text. PDFBox documents are not thread-safe (ADR 0192).
- The page column gets the focus, so the scroll keys work at once.
- A changed file (rewritten by another program) is opened again: the position (page and height on it) and the zoom
  stay, the pages render anew. If it can no longer be opened, its notice replaces the pages, and the pages come back
  once a later change makes it readable. If the explorer moves the file to another `.pdf` name, the tab follows it.

## The page column
- Every page is a white sheet with a thin edge and a shadow on the pane's background, in both themes, separated by an
  8 px gap. A page shows blank until its image is rendered.
- Pages render lazily, off the FX thread: the visible pages first, nearest the middle of the view, then two pages
  before and after them. Only the pages around the view keep a sheet in the scene; images of pages far away are
  dropped once the images of the view hold more than about 75 million pixels (about 300 MB). Renders of pages no
  longer wanted are dropped from the queue, and a render already running stops at its next drawing step.
- Pages render at the zoom times the screen's output scale (crisp on HiDPI screens). A page image is at most 16 million
  pixels; a larger page renders at the largest scale within that and is shown enlarged.
- **Current page**: the page under the vertical middle of the view (a gap counts to the page above). The page field
  shows it.
- A page that cannot be rendered shows "Could not show page N." in its place; the other pages render as usual. Running
  out of memory while rendering does the same for that page and drops the images of the pages away from the view.

## Toolbar
From left to right: a slot for the side panel's toggle, the **page field** ("12" and "/ 340"), a slot for the search's
toggle; on the right **Open in Default App** (opens the file in the system's app for PDFs) and the zoom group. No
control takes the focus by Tab; clicks leave the focus where it was, except in the page field.
- **Page field**: Shortcut+G anywhere in the view focuses it with the number selected. Enter jumps to the page typed
  (kept within 1 and the page count) and gives the focus back to the pages; Escape, or a text that is no whole number,
  puts the current page back and gives the focus back too. While it has the focus, Space, Escape, the arrows and
  Shortcut+A/C work in the field, not on the pages.

## Zoom
- The zoom group of the previews: zoom out, the level, zoom in, **100%**, and the toggles **Fit Width** and **Fit
  Page**, which show the fit in effect. Shortcut+Plus/Minus step through the zoom levels, Shortcut+0 is 100 %,
  Shortcut+9 is Fit Page. Shortcut+wheel and pinching zoom around the pointer and do not scroll.
- The view opens at **Fit Page**: the current page whole in the view. Fit Width fits its width. In a fit mode a resize
  fits again; any other zoom leaves the fit mode and keeps its scale on resize. A zoom keeps the point under the
  pointer (or the middle of the view) in place; while the pages render at the new scale, the old images are shown
  scaled.

## Password
- A PDF with a user password asks for it: a dialog "Open PDF" with "<file> is password-protected.", a password field,
  Open and Cancel. Open tries the password off the FX thread (the button is disabled meanwhile); a wrong one shows
  "Wrong password." and keeps the dialog open. Cancel leaves the notice "This PDF is password-protected." with an
  **Enter Password…** button that asks again.
- The password stays in memory while the tab is open, to open the text document and to reload; it is never written
  anywhere, and is zeroed when the tab closes. A changed file whose password changed asks again.
- PDFs with only an owner password open without asking; their restrictions (copying, extraction) are ignored.

## Notices
Instead of the pages, a centred notice says "Not a valid PDF: <reason>", "This PDF has no pages.", "This PDF is too
large to open." or "This PDF is password-protected."; the toolbar stays, its page field and zoom are disabled.

## Nothing stored
No position, zoom or panel state is remembered; ideas: [ideas/pdf.md](../ideas/pdf.md).

## Selection and links
(added in a later package)

## Outline and thumbnails
(added in a later package)

## Search
(added in a later package)

## Known limits
- AWT (which PDFBox renders with) and JavaFX in one process are not checked on macOS in the sandbox; in the headless
  Linux sandbox the app needs `-Djava.awt.headless=true` ([testing.md](../testing.md)).
- Vector-heavy pages take 1 to 3.5 s to render; the old image (or a blank page) shows meanwhile.
- Pages beyond 16 million pixels are enlarged, not rendered in tiles.
- Keys: [keyboard-shortcuts.md](keyboard-shortcuts.md#pdf-viewer).

# PDF viewer

**Module:** `modules/pdf` (package `dev.sluide.pdf`: `PdfFileViewer`; package `dev.sluide.pdf.engine`: `PdfDocument`,
`PdfSetup`; package `dev.sluide.pdf.view`: the view, page column, workers, toolbar, zoom, password prompt; package
`dev.sluide.pdf.view.side`: the side panel); decision in
[ADR 0192](../adr/0192-pdf-viewer.md)

`.pdf` files open read-only in a tab: all pages stacked in one scrolling column, rendered by Apache PDFBox as images,
with a toolbar above. Nothing is fetched from the network and nothing is stored.

## Opening
- Opening a PDF returns at once; "Loading…" shows until the document is open. Closing the tab drops results that
  arrive late.
- The view opens the file twice, once per thread: one document renders the pages (and reads the outline), the other
  extracts their text. PDFBox documents are not thread-safe (ADR 0192).
- The page column gets the focus, so the scroll keys work at once. Focus asked for while "Loading…" or a notice shows
  goes to the pages once they show, unless the user clicked or typed outside the view meanwhile; it waits for the pages
  to be in the scene, which the split pane does a pulse after the tab's view (`PagesFocus`).
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
large to open." or "This PDF is password-protected."; the toolbar stays, its page field and zoom are disabled and
empty (no page number, page count or zoom level), as while "Loading…" shows.

## Nothing stored
No position, zoom or panel state is remembered; ideas: [ideas/pdf.md](../ideas/pdf.md).

## Selection and links
- **Select** by dragging over the text with the primary button, from the char under the press to the one under the
  pointer; it may span pages and be drawn backwards. A drag that starts beside the text starts at the nearest char of
  that page; a page without text has nothing to select. A double-click selects a word (letters, digits, `_`, `'` and `’`;
  whitespace and punctuation are a word of their own char). **Shortcut+A** (and Select All in the context menu)
  selects every page's text. **Escape**, or a click without a drag (press and release within 3 px), clears it.
- The selection is kept in text offsets, so it survives scrolling and zoom; a reload clears it. It is drawn as one
  rect per line run in the `SELECTION` layer, for the shown pages (a page that scrolls out keeps its rects).
- **Copy** (Shortcut+C, or Copy in the context menu, disabled without a selection) puts the plain text on the
  clipboard: pages joined by a blank line, line ends kept, trailing spaces of each line and the line ends a page's
  text ends with trimmed. The texts of all pages of the range are waited for, then the clipboard is set once.
- **Links** of the shown pages are read in the background (kept while the document is open, cleared on reload). A
  click without a drag on a link follows it: a place in the document scrolls there, a web or mail link opens through
  the host. A press on a link that turns into a drag selects text instead. A tooltip shows the address, or "Page N".
- The pointer is the text cursor over a char, the hand over a link (links win over text), else the default.

## Outline and thumbnails
The **side panel** on the left (package `dev.sluide.pdf.view.side`: `SidePanel`, `OutlinePanel`, `ThumbnailsPanel`,
the FX-free `OutlineFollow` and `ThumbnailCache`) has the tabs **Outline** and **Thumbnails**. The toggle **Side
Panel** at the left of the toolbar shows and hides it; it opens about 220 px wide (less in a narrow window) and keeps
the width it was dragged to while hidden.
- **How it starts**: once the PDF opened, on Outline if the PDF has an outline; else it stays closed with Thumbnails
  selected, and Outline shows "This PDF has no outline.". After that the tab and whether the panel is open are the
  user's for the tab's life: a reload keeps them, nothing is stored.
- **Outline**: the PDF's outline as a tree, its items expanded or collapsed as the PDF marks them. Titles show on one
  line, cut off with an ellipsis, the full title as a tooltip. A click on an item, or Enter on the selected one,
  scrolls the pages to its target and gives the focus to the pages; the arrow keys only move the selection. An item
  without a target that can be found is dimmed and does nothing.
- **Thumbnails**: the pages as small images, 120 px wide (at most 480 px high), each with its number below; a page
  shows blank until its image arrives, and stays blank if it cannot be rendered. A click on a page, or Enter on the
  selected one, scrolls the pages to it and gives them the focus.
- **Following the pages**: both lists select the current page's entry and scroll themselves to show it, without
  scrolling the pages. In the outline that is the item whose target page is the latest at or before the current page
  (of several on that page, the last in document order, so a section on its chapter's first page wins); if it is
  hidden in a collapsed item, that item is selected instead, and following never expands anything. Before the first
  target nothing is selected. After a pick in either list the picked entry stays selected until the pages move away
  from the page the jump left them on (a scroll, the page field, a link, a search hit, a zoom) or the user scrolls
  them, also when the pages could not scroll it to the top.
- **Rendering**: thumbnails render on the same thread as the pages, after the pages in view and those around them,
  at 120 px times the screen's output scale. Only the cells shown render, and only while the Thumbnails tab shows in
  the open panel; renders of cells scrolled away are dropped from the queue, and one already running stops at its next
  drawing step. At most 200 thumbnail images are kept, the least recently used dropped first.
- A reload rebuilds the outline from the new file and renders the thumbnails shown anew; closing the tab stops their
  renders.

## Search
Package `dev.sluide.pdf.view.search`, modelled on the ebook reader's search ([ebook-reader.md](ebook-reader.md)).
- **Opening**: the magnify toggle in the toolbar (tooltip "Search (Shortcut+F)") or Shortcut+F anywhere in the view,
  the page field included, opens the Search panel on the right (about 320 px wide the first time, then at the width it
  was left at), with its field focused and its text selected; a query still in the field is searched again.
- **Query**: case-insensitive, and a run of whitespace in the query matches any run of whitespace in the text, a line
  end included; leading and trailing whitespace is ignored, and a match does not run from one page onto the next. It
  needs at least two non-blank characters and is searched 300 ms after typing, or at once on Enter; a newer query
  replaces an older one, whose results and progress are dropped.
- **Texts**: the search reads the plain text of every page from the view's text document (kept for all pages while the
  document is open). While they come, the panel says "Searching… page 120 of 500"; a second query on the same document
  finds them there. The matching runs on a thread of its own. While the PDF loads or a notice shows, nothing is
  searched; the query is searched once the document is open.
- **Results**: "1 match", "41 matches", "1000+ matches" (the list stops at 1000), "No matches", or "This PDF has no
  text." when no page has any (a scanned PDF). The hits are listed under "Page N" headings, each as a snippet of about
  40 characters either side with the match in bold.
- **Highlights**: the hits on the pages in view are highlighted lightly; a page scrolled into view gets its highlights
  then, and pages scrolled away keep theirs (glyph boxes are read only for the pages shown). A click, or Enter on the
  selected hit, chooses it: it gets a stronger highlight and is scrolled into view; the focus stays in the list.
- **Closing**: changing or clearing the query removes the results and the highlights. Escape in the field clears it, and
  on an empty field closes the panel and gives the focus to the pages. Closing the panel (toggle or Escape) removes
  the highlights and results; the query stays in the field.
- A changed file that is opened again is searched again with the open query.

## Known limits
- AWT (which PDFBox renders with) and JavaFX in one process are not checked on macOS in the sandbox; in the headless
  Linux sandbox the app needs `-Djava.awt.headless=true` ([testing.md](../testing.md)).
- Vector-heavy pages take 1 to 3.5 s to render; the old image (or a blank page) shows meanwhile.
- Pages beyond 16 million pixels are enlarged, not rendered in tiles.
- Dragging past the viewport does not scroll on its own; the wheel works while dragging. A drag over the gap
  between pages or beside a page keeps the last char.
- Search does not join hyphenated line breaks: "hyphen-" at the end of a line and "ated" on the next are not found as
  "hyphenated".
- Keys: [keyboard-shortcuts.md](keyboard-shortcuts.md#pdf-viewer).

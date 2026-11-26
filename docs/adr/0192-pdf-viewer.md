# 0192. PDF viewer: PDFBox page images, one document per thread

- Status: accepted
- Date: 2026-11-25

## Context
A `.pdf` opened in the text editor: the user wants a read-only PDF tab with outline, thumbnails, search, selection and
copy, links, go-to-page and "Open in Default App". A PDF is untrusted input: it may be truncated, encrypted, huge, deeply
nested or carry `javascript:` links. This ADR covers the engine (module `pdf`, package `dev.sluide.pdf.engine`) and the
decisions the view packages build on; the feature page `docs/features/pdf-viewer.md` (written with the view) details
the view.

## Decision
- **Apache PDFBox 3.0.8 renders page images; no WebView, no pdf.js.** PDFBox is pure Java (pdfbox, fontbox, pdfbox-io;
  commons-logging, which Spring already brings, resolves to 1.4.0), adds only `java.desktop` and `java.logging` to the
  runtime, and gives page images, text with glyph positions, the outline and the links from one API. pdf.js in a WebView
  would bring a JavaScript engine, a second rendering stack and the WebView's page rules (ADR 0017) to a viewer that
  needs none of them. Pages are `BufferedImage`s (`ImageType.RGB`) whose pixels become opaque ARGB (`RenderedPage`),
  copied into JavaFX images by the view; no `javafx.swing`.
- **Module `pdf`**, part of the editor family: `pdf` → `file-viewers` (`api`), `background-tasks`; `ide` → `pdf`.
  The engine is FX-free and Spring-free; its API is `PdfDocument`, `PdfOpenResult`, `PdfSetup` and the value types
  (`PageSize`, `PageRect`, `PageTarget`, `OutlineItem`, `LinkTarget`, `PageLink`, `PageText`, `RenderedPage`).
- **One thread per document, two documents per view.** A `PDDocument` is not thread-safe: in the spike, rendering and
  extracting text on one document from two threads gave silently wrong results. A `PdfDocument` is opened, used and
  closed on one thread. The view opens two: one on its render thread (pages, thumbnails, outline, links) and one on its
  text thread (page texts, glyph boxes, search); reopening costs about 70 ms. `PdfDocument.open` loads from the file
  (a `RandomAccessReadBufferedFile`, read as needed), never into a byte array; the file is closed on every failure of
  parsing (PDFBox closes it only on an `IOException`), and the PDFBox document on every failure after it. The pages
  are read once, in page-tree order, when the document opens (20,000 pages in about 80 ms). PDFBox lists pages by
  walking `/Kids` but fetches page i for rendering (`PDPageTree.get`) by descending through the `/Count` values, so a
  lying `/Count` would make page i another page, or none, for the render than for its size and text. Before the pages
  are read, the page tree is made consistent in memory (the document is never saved): every node's `/Count` becomes
  its real number of pages, and a kid that is no dictionary or was met before (a loop) is removed, which also keeps
  PDFBox from logging the loop on every walk. A page tree deeper than 1,000 levels is refused
  (`Invalid("page tree too deep")`) before PDFBox's recursive walks see it, which would overflow the stack.
- **No `Thread.interrupt`.** An interrupt closes the file channel PDFBox reads from (`ClosedByInterruptException`) and
  ruins the document. Stale work is skipped by a generation count; a render in flight is stopped by its `cancelled`
  check, which a `PageDrawer` subclass asks before every content-stream operator and answers by throwing out of the
  drawing loop (`render` then returns `null`, the document stays usable; the check is asked once more at the end, for
  pages without content). Dispose shuts the executors down and closes each document on its own thread.
- **Pixel cap of 16 MP** (64 MB as ARGB) per page image: a render that would be larger is made at the largest scale
  within the cap, reported in `RenderedPage.scale`, and the view scales it up. PDFBox gives each side at least one
  pixel, so the cap also limits the long side alone (`min(sqrt(cap / (w * h)), cap / max(w, h))`): a page 1,000,000
  points wide and 0.01 high stays within it, and a side of 0 points is 1 pixel. PDFBox itself refuses images beyond
  `Int.MAX_VALUE` pixels; a 200-inch page at 300 dpi would need 2.4 GB. Each render has a `PDFRenderer` of its own
  (well under a microsecond to create), so no renderer keeps its last page image.
- **Page failures are `IOException`s.** `render`, `pageText`, `plainText` and `links` turn every runtime exception
  (PDFBox's, and a page that does not exist) into an `IOException` "Could not read page N: ...", so the view catches
  one type. PDFBox draws a page whose content is broken as far as it can: a corrupt Flate stream or a `/Contents` that
  is no stream gives a blank page (text `"\n"` and `""`); an inline image claiming 99,999 by 99,999 pixels throws.
- **Open results.** A user password gives `NeedsPassword` (none given) or `WrongPassword` (`InvalidPasswordException`);
  PDFs with only an owner password open without asking, and their restrictions (copying, extraction) are ignored, so
  copy and search work (decided by the user). Unreadable files give `Invalid(reason)` with PDFBox's message (first
  line, at most 200 chars; "file not found", "cannot read the file" or the file system's reason instead of a message
  that starts with the path); runtime exceptions of the parser count too.
  No pages gives `NoPages`, an `OutOfMemoryError` while opening `TooLarge`.
- **Font cache and logging.** PDFBox scans the system fonts once and keeps the result in `.pdfbox.cache`, by default in
  the home folder. `PdfSetup.init(PdfSetup.defaultCacheFolder())`, called before the first document opens, sets the
  system property `pdfbox.fontcache` to `~/.sluide/cache/pdfbox` (unless set) and creates the folder (PDFBox falls back
  to the home folder when it is missing). Tests set the property to `build/tmp/pdfbox-fontcache` in the test task, and
  `TestPdfs` calls `init` with that folder for JVMs without it (pitest workers). PDFBox warns once per font it replaces
  by a fallback font: `logback-spring.xml` lets `org.apache.pdfbox` and `org.apache.fontbox` log only errors, and
  `init` does the same for `java.util.logging`, which commons-logging uses without SLF4J.
- **Coordinates.** Every engine value is in page points (1/72 inch) from the top-left corner of the page as displayed:
  crop box and rotation applied, as `PDFRenderer` draws it. `pageSizes` are the crop box, width and height swapped on
  pages rotated by 90 or 270 degrees. The view multiplies by its scale.
- **Text and boxes.** One `PDFTextStripper` subclass (`setSortByPosition(true)`, lines and the page ended by `\n`)
  gives both `plainText` and `pageText`, so their strings are equal; `plainText` skips the boxes. `pageText` records,
  for each char `writeString` writes, the glyph box it came from: PDFBox's `DirAdj` values (the glyph turned by its
  text direction, relative to the crop box) turned back by the text direction and then by the page rotation. Chars the extraction
  writes itself (line ends, word gaps) get `null`. A glyph that becomes several chars (a ligature such as U+FB01, by
  PDFBox's NFKC normalisation of presentation forms) gives each of them an equal share of its box. Glyphs entirely
  outside the crop box are dropped from the text (a glyph partly inside stays). Text extraction for search is about
  1 ms per page; glyph boxes take about 1 MB per page, so the view keeps them only for shown pages and pages with hits.
- **Outline and links.** Destinations resolve from explicit destinations (a page dictionary or a page number), named
  ones (name tree and the older `/Dests` dictionary; a value may hold the destination under `/D`) and GoTo actions.
  PDFBox's own walks are not used on these untrusted structures: `retrievePageNumber` loops forever on a `/Parent`
  loop and `findNamedDestinationPage` overflows the stack on a name tree that contains itself. A destination's page
  dictionary is looked up in a map of the page tree's dictionaries (by identity), a page number is taken as it is; the
  name tree is read once, at most 100 levels deep, each node once. The height a destination names becomes
  `PageTarget.y` (clamped to the page): on an upright or upside-down page the top of an XYZ or FitH destination, on a
  page turned by 90 or 270 degrees the left of an XYZ or FitV destination (the left runs from top to bottom there);
  without that value `y` is `null`. What cannot be resolved has no target (outline) or is dropped (links), and so is a
  link without a rectangle. The outline walk stops at 100 levels and 10,000 items, and an item met again (a loop) ends
  its list. Link rectangles go through the same transform as the page image. Web links keep only `http`, `https` and `mailto` (any case); `javascript:`, `file:` and
  the rest are dropped, as in the ebook reader (ADR 0165).
- **The view** (detailed by the feature page): all pages stacked in one scroll pane, rendered lazily around the
  viewport at scale × the screen's output scale, blank placeholders until then; opens at Fit Page with the zoom group
  of the previews (`ZoomSteps`, shared from `file-viewers`); a toolbar with the side-panel toggle, a page field, search,
  Open in Default App; a side panel with Outline and Thumbnails; a search panel; selection and copy on the glyph boxes;
  links; a password dialog; centred notices for invalid, empty, too large and protected PDFs; reload on file changes.
  Nothing is stored (position, zoom, panels).
- **View structure** (package `view`). `PdfFileView` has two `PdfWorker`s, `renderWorker` and `textWorker`, each one
  `NamedThreads` thread that opens, uses and closes its document. Their queues run jobs by priority (`VISIBLE`, `NEAR`,
  `THUMBNAIL`, `TEXT`, `SEARCH`; opening and closing first), and a generation count drops jobs queued before a reopen
  and results delivered after it or after dispose; `cancelQueued` drops queued jobs, `dispose` uses `shutdown()`. A job
  that finds no document reports that to its `onError` (a text that never comes would leave a search waiting); an open
  that throws (a stack overflow) counts as an invalid file, a close that throws is logged.
  `PageTexts` caches the page texts on the text worker (50 with glyph boxes, plain texts all). `PageLayout` (FX-free)
  stacks the pages at a scale with an 8 px gap and 12 px margins and gives current page, visible pages, fits and the
  anchor-preserving scroll of a zoom; `ZoomModel` holds the scale and fit mode. `PageColumn` is the only class that
  knows pixels: a scroll pane with a slot per page around the viewport (`PageSlots`, `PageSlot`), images from
  `PageImages` within a pixel budget of about 75 million. User scrolls (wheel, keys, scroll bar) are told from the
  view's own (scroll to a target, zoom, resize) by a flag around the view's own changes of the scroll values; the
  column is resized at once on a zoom, since the scroll pane keeps the content's pixel offset when its size changes.
  The view's hooks for selection and links, the side panel and the search are fixed: `info`, `currentPage`,
  `shownPages` (the pages in the viewport, empty while none are shown), `scrollTo`,
  `userScrolled`, `pagePointAt`, `pageRectToScene`, `scrollIntoView`, `overlays` (layers `SEARCH_HIT`,
  `SEARCH_CURRENT`, `SELECTION`, in points), `pages`, `pageCursor`, `pageContextMenu`, `toolbar` (`sidePanelSlot`,
  `searchSlot`), `split` (`left`, `right`), the workers, `texts`, `onReload` (every reopen after a document was shown,
  also one back from a notice) and `onDispose`; and `textRects` (FX-free), which turns a char range of a page text into
  one rect per line run, for selection and search highlights, on turned pages too.
- **Thumbnails** (package `view.side`) render on the `renderWorker` at `THUMBNAIL` priority, after the pages in view
  and around it, with the same cancel as the pages: each request is the job's tag and has a `cancelled` flag; the
  requests of cells no longer shown (or of a hidden panel, a reload, the view's dispose) get their flag set, which
  stops a running render at its next drawing step, and `cancelQueued` drops the queued ones. `ThumbnailCache`
  (FX-free) keeps the requests and an LRU of 200 images; the cells shown are read from the list's `VirtualFlow` after
  each of its layouts. The outline's selection follows the current page through `OutlineFollow` (FX-free).
- Selection is kept in text offsets `(page, char)`, not in pixels, so scroll, zoom and a text-less page need nothing
  from it; its rects come from `textRects` for the shown pages only and go into the `SELECTION` overlay layer. Copy
  takes the kept texts of the shown pages and the plain texts of the others, so it never waits behind a search. Links
  of the shown pages are read on the render worker (`NEAR`) and kept per page while the document is open.
- **Search** (package `view.search`). The search reads the plain text of every page through `PageTexts.plainTextOf`
  (text worker, `SEARCH` priority, kept for all pages, so a second query skips the extraction) and matches the texts
  on a thread of its own (`sluide-pdf-search`, made for the first search, `shutdown()` on dispose), never on the text
  worker, whose document the highlights and the selection also need. Matching, snippets and the status are copies of
  the ebook reader's (`PdfSearch` from `BookSearch`, `SearchRunner`, `SearchPanel`, `SearchRows`), adapted to pages,
  as `PdfSplit` copies `ReaderSplit`: modules do not depend on each other's internals. Glyph boxes are read only for
  the pages shown (and the page of a chosen hit), on every change of `shownPages`, since the text cache holds 50
  pages with boxes; the hits become rects through `textRects`. Nothing is searched while `info` is `null` (the text
  worker has no document then and would give every page an empty text); the query is searched again when `info`
  changes and on `onReload`. The logic is FX-free (`SearchFlow` with a `SearchHost` of the view's hooks) and tested on
  the fixtures' real texts.

## Consequences
- The view packages build against an engine that is tested without a toolkit, on PDFs that `TestPdfs` builds with
  PDFBox itself (no binary fixtures).
- Rendering uses AWT (`BufferedImage`, `Graphics2D`): in the headless sandbox the app needs `-Djava.awt.headless=true`
  (`docs/testing.md`, "Running and screenshotting the real app"). AWT and JavaFX in one process on macOS are not
  verified in the sandbox: a check on the Mac is part of the view's real-app check.
- Vector-heavy pages take 1 to 3.5 s per render; the view keeps the old image (or a blank page) until the new one is
  ready.
- Known limits: hyphenated words at line ends are not joined in the text; pages beyond the cap are upscaled, not
  tiled; the boxes of right-to-left text are not checked (PDFBox reorders such words, the boxes follow the glyphs in
  drawing order); forms, annotations other than links, printing and rotation by the user are not supported (ideas in
  `docs/ideas/pdf.md`, written with the view).

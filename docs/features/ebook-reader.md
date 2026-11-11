# Ebook reader

**Module:** `modules/ebooks` (package `dev.sluide.ebooks`: `EpubFileViewer`, `EpubReader`, `BookPageBuilder`; package `dev.sluide.ebooks.view`: the view, Contents panel, toolbar, loading, position,
`SentenceBounds`); decision in
[ADR 0165](../adr/0165-ebook-reader.md)

`.epub` files (EPUB 2 and 3, no DRM) open in a reader tab: the whole book as one scrolling page, a Contents panel on
the left and a toolbar above. Nothing is fetched from the network.

## Opening
- Opening a book returns at once; the file is read and the page built in the background, "Loading…" shows meanwhile.
  Closing the tab ignores a result that arrives late.
- The web view gets the focus, so the scrolling keys (arrows, Page Up/Down, Home/End, Space) work at once.
- A changed file (rewritten by another program) is read again and the position kept; if it can no longer be read, the
  book stays as it is shown. If the explorer moves the book, the tab follows it and the position is saved under the
  new name.

## The page
- The whole book is one page, every chapter a section in reading order, in Thoreau's reading style: serif text in a
  column about 40 em wide, light or dark with the theme (a change of theme applies at once, without reloading).
- **Large books.** If the page would be longer than 50 million characters (images included), the book is shown one
  chapter at a time, with **Previous Chapter** and **Next Chapter** buttons after the Contents toggle. The whole
  book is built once to learn its text, chapters and contents; its page is dropped, and each chapter's page is built
  when it is shown (`chapterRange`), with offsets starting at 0. The view maps them to whole-book offsets, so the
  saved position, the Contents panel, the progress and the hooks of Read Aloud and the search always use whole-book
  offsets. Jumping to a position in another chapter loads that chapter.
- **Book Style** (toolbar toggle, off by default, per tab, not remembered): shows the book's own formatting (its CSS,
  sanitised) instead of Thoreau's reading style. The page is built again and loaded, the top of the view kept.
- **Zoom** of the page: Shortcut+Plus/Minus/0 and Shortcut+wheel while the page has the focus, and the zoom group in
  the toolbar, as in the previews ([previews.md](previews.md)).
- A fixed-layout book is shown as flowing text, with a notice bar on top (close button) saying so.

## Contents
- The left panel (**Contents** toggle, on by default; the divider position survives hiding it) shows the book's table
  of contents as a tree, all expanded; a book without one gets one entry per chapter.
- Selecting an entry (click or arrow keys) jumps there, and the focus goes back to the page. The selection follows the
  reading position: the deepest entry that starts at or before it, scrolled into view. A jump keeps the entry you
  picked until you scroll.

## Position
- The current position is the offset in the book's text at the top of the view. It is recomputed shortly after you
  scroll (150 ms), and after a jump or a link within the book. It drives the Contents selection, the progress label
  ("Chapter title · 37 %", the percentage of the whole text, rounded down) and the saved position.
- **Stored** (plain text, like all per-file project data, [ADR 0101](../adr/0101-shared-data-file.md)): in the file's
  entry of the project data, `"ebook": {"offset": N}`, nothing else. It is written at most once a second, off the FX
  thread, and at once when the tab closes. It follows the file when the explorer moves it.
- On opening, the saved offset is clamped to the text, the page scrolls so that it is at the top of the view (as do
  Book Style, Contents jumps and chapter links) and the sentence around it is marked as
  the **start** (a soft marker, the place Read Aloud will begin).
- **Start mark.** A single click on text (not on a link, not a selection or drag) marks the sentence around the click
  as the start; a click elsewhere moves it. It only marks; it never starts reading.

## Links
- A link within the book scrolls to its target; the Contents panel and progress follow. In a large book a link to
  another chapter loads that chapter.
- `http`, `https` and `mailto` links open in the system browser through the IDE. Other links are removed from the
  page. The web view never shows another page: if it navigates away by any route, the navigation is cancelled and the
  book shown again at the same position.

## Bad books
Instead of the page, the tab shows a centred notice: "This book is DRM-protected and cannot be opened." or "Not a
valid EPUB: <reason>" (not a zip, too large, no table of contents or spine, and so on).

## Read Aloud
(coming)

## Search
(coming)

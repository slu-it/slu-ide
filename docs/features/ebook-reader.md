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
- A fixed-layout book is shown as flowing text, with a notice bar on top (close button) saying so. The same bar says
  "Could not show this chapter: <reason>" (or "This chapter is too large to show.") when a chapter or a Book Style
  change cannot be built; the page stays as it was and the Book Style toggle goes back.

## Contents
- The left panel (**Contents** toggle, on by default; the divider position survives hiding it) shows the book's table
  of contents as a tree, all expanded; a book without one gets one entry per chapter.
- Clicking an entry or pressing Enter on the selected one jumps there, and the focus goes back to the page. The
  arrow keys only move the selection. The selection follows the
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
The reader embeds the Read Aloud toolbar (play/pause, stop, voice, speed, status) after the chapter buttons, and the
setup notice (no model, no espeak-ng) as a banner between the toolbar and the page, as in the editors
([read-aloud.md](read-aloud.md), [ADR 0164](../adr/0164-read-aloud-surfaces.md)). One reading runs at a time, app-wide;
starting one pauses the other. The tab shows the running marker while it reads.
- **Start.** Play reads from the marked start sentence, else from the top of the view. It reads to the end of that
  chapter (at most 1,000,000 chars; a longer chapter is cut at a paragraph break and goes on from there) and then reads
  on with the next chapter (one without text, such as an image, is skipped), until the end of the book; it never
  loops. Stop or an error ends the reading. The start mark follows the sentence being spoken (hidden while reading),
  so Play resumes at the last sentence spoken, also after the end of the book or an error.
- **Highlight.** The sentence and the spoken word are highlighted in the page. The reading position (progress, Contents
  panel, saved position) follows the sentence. While reading, a single click does not move the start mark;
  a double-click on the page restarts at the sentence under it (outside the chapter being read, or far back in it,
  it starts a new reading there) and follows again. A paused reading stays paused when the double-click lands in the
  part being read; a new reading elsewhere starts speaking.
- **Follow.** The page scrolls to keep the reading in view and loads the next chapter of a large book. Scrolling by
  hand (wheel, keys, scrollbar) or moving the page yourself (a Contents entry, Previous/Next, a link, a search hit)
  pauses the following and shows a **Back to reading** button at the bottom right; the button, or scrolling the
  current sentence out of view and back in, resumes it. Every Play follows again; reading on to the next chapter keeps
  your choice.
- **Not spoken.** Footnote markers, images and their alt text, tables and page breaks.
- While the cursor is in a text field of the view (the search), Space and Escape type as usual.
- Keys: [keyboard-shortcuts.md](keyboard-shortcuts.md#ebook-reader).

## Search
The magnify toggle in the toolbar (tooltip "Search (Shortcut+F)") or Shortcut+F anywhere in the view opens the Search
panel on the right (about 320 px wide the first time, then at the width it was left at), with its field focused and its
text selected; a query still in the field is searched again. The
search covers the whole book (`BookSearch`): case-insensitive, and a run of whitespace in the query matches any run of
whitespace in the text, a paragraph break included. It needs at least two non-blank characters and runs off the FX
thread 300 ms after typing, or at once on Enter; a newer query replaces an older one. The panel says "41 matches",
"1000+ matches" (the list stops at 1000) or "No matches", and lists the hits grouped under their chapter, each as a
snippet of about 40 characters either side with the match in bold. A click, or Enter on the selected hit, marks the
match (the `search` mark) and jumps there, in chapter mode loading that chapter; the focus stays in the list. Changing
or clearing the query, or closing the panel, removes the mark, and closing clears the results. Escape in the field
clears the text, and on an empty field closes the panel and gives the focus to the page.

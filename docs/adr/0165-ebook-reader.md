# 0165. Ebook reader: EPUB as one sanitised page with a flat text

- Status: accepted
- Date: 2026-11-11

## Context
Thoreau gets an EPUB reader that can read a book aloud with highlighting ([ADR 0124](0124-read-aloud-speech.md)). Read
Aloud works on one flat text with offsets, a WebView shows rich content ([ADR 0017](0017-webview-previews.md)), and an
EPUB is a zip of XHTML chapters from anyone: it may contain scripts, remote resources, huge entries or zip bombs. This
ADR covers the FX-free core (`ebooks`); the view, the Read Aloud binding and the file viewer follow in later
packages and amend it where needed.

## Decision
- **Module `ebooks`**, package `dev.sluide.ebooks`, part of the editor family. The core is a plain library: it reads
  the file (`EpubReader`) and builds the page (`BookPageBuilder`); no JavaFX, no Spring beans.
- **EPUB 2 and 3 only, no DRM.** A `META-INF/rights.xml`, or an `encryption.xml` with any algorithm other than font
  obfuscation (IDPF, Adobe), gives `EpubResult.Drm`; the book is not opened. Obfuscated fonts are dropped.
  Fixed-layout books are flagged (`fixedLayout`) but shown as reflowable text.
- **One page for the whole book**, every chapter a `<section id="ch<i>">` in spine order. A later package falls back to
  one chapter at a time (`chapterRange`) above a size threshold. The size is that of the page (`BookPage.html.length`,
  images included), not only of the text; the view package decides the threshold (about 20 MB was assumed).
- **Sanitising and no network.** The page is written by Thoreau from an allow-list of tags and attributes, not copied
  from the book: scripts, frames, objects, forms and event attributes never reach it, `javascript:` and unknown link
  schemes are dropped with the link text kept, only `http`, `https` and `mailto` links stay (the view opens them in the
  browser). Images and fonts come from the zip as data URIs (one base64 text per resource and build); remote and
  missing ones are removed. The media type of a manifest item must be a plain `type/subtype`, else the resource is
  unusable, and the reader keeps only images, fonts and style sheets in memory. Every attribute value is escaped. Book
  CSS is rewritten in one pass: `@import`, `image-set()` and the like go, a string ends at a newline, escapes outside
  strings (never inside) are removed, unterminated parts are dropped, `url(...)` is inlined or becomes `none`, and a
  final check fails closed (no CSS). A Content-Security-Policy in the page (`default-src 'none'`, data images and
  fonts, inline style, and `script-src` with the SHA-256 of the one script the caller passes) refuses everything else:
  other scripts, inline handlers, `eval` and `new Function`. It does not cover navigation (`location = ...`, link
  clicks, `meta refresh` is dropped by the builder): the view has to block or route it.
- **The offset contract.** Every text node of the page is a `<span data-o="N">` whose text equals
  `BookPage.text.substring(N, N + length)`. Whitespace is normalised, block ends put a blank line into the text (a `br`
  a line break) and these separators are in no span; images and `alt` text add nothing. `skip` lists the ranges that
  are not read aloud (note references, tables, page breaks); `anchors` map every page id to an offset; `chapters` and
  `toc` are offsets too. The JS of the view and Read Aloud both rely on this and nothing else about the markup.
- **Depth.** Elements nested deeper than 200 are flattened: their text and line and block breaks stay, their tags go.
- **Limits.** At most 10,000 zip entries, 512 MiB inflated in total and 64 MiB per entry, counted while inflating, not
  from the headers; entry names are only keys and never touch the file system. The package files are parsed with a
  hardened XML parser (no external DTDs or entities, nothing fetched).
- **Thoreau style or book style.** By default the book's CSS, `style` attributes and classes are dropped and Thoreau's
  reading style applies (serif, a 40em column, light and dark palettes, footnotes smaller). "Book style" keeps the book's
  CSS, sanitised as above, plus the same mark rules. The marks for the sentence, word, start and search are drawn
  only as CSS highlights (`::highlight(...)` rules, `CSS.highlights`); there is no class-based fallback.

## The view (amendment)
- **Chapter mode.** The book is measured once, off the FX thread, by a build without embedded images (see "Fix round"
  below). If the page would be longer than 50,000,000 characters (the threshold, a constructor parameter, instead of the
  20 MB first assumed: the page is a string in memory and in the engine), the view keeps the whole-book `text`, `chapters`, `toc`, `skip` and `anchors` without the html and builds
  each chapter's page on demand with `chapterRange = i..i`. `ShownPage.shift` (whole-book chapter start minus the
  local start) maps local offsets to whole-book ones; the saved position (offset and, in chapter mode, the chapter:
  a chapter without text has no offset of its own), the Contents panel, the progress and the
  hooks of Read Aloud and the search use whole-book offsets only.
- **Navigation.** The page's script (the text marks plus a click handler) cancels clicks on links itself: `#anchor`
  links scroll with `scrollIntoView` (the page's address is not a real one; an id the page does not have, a link to
  another chapter of a large book, is looked up in the whole book's `anchors` and loads that chapter), `http`, `https` and `mailto` links go to
  the host (`openWebPage`), the web view never navigates. As a second line, a change of the engine's location to
  anything but the page itself (empty, `about:blank` and its anchors) is cancelled by loading the page again at the
  same position; a link target is opened as well.
- **Page and hooks.** Only the shown page holds html: `LoadedBook.page` is the whole book without it (text, skip, chapters,
  contents, anchors). `marks` and `webView` belong to the shown page, whose offsets are local in chapter mode; the view
  offers `toLocal` and `toWhole`, and `markRange` clips and maps whole-book ranges to it.
- **Position and start mark.** The position is the top offset of the view, recomputed after scrolling (debounced), a
  jump or an anchor; `TextMarks.scrollToTop` puts an offset at the top of the view (`reveal` centres it). The scroll
  positions the app's own scrolls cause are told apart from the user's by the position the script expects (the echo
  of its own scroll), not by a time window, so a wheel or key right after still counts; a jump from the Contents panel
  sets the target itself, so that the follow logic cannot undo it.
  The saved offset (per-file project data, field `ebook`) is debounced and flushed on dispose. A click on text is
  reported by the script and marks the sentence (`SentenceBounds`) as `start`; named marks are shown again after
  every load of a page. Read Aloud moves the `start` mark to each sentence it speaks (exactly the spoken range, whose
  bounds differ from `SentenceBounds`) and hides it while the reading is locked, so Play resumes at the last sentence
  spoken, whichever way the reading ended (within the open tab: a reopened book marks `SentenceBounds` around the
  saved position).

## Consequences
- The view and Read Aloud need only `BookPage`; they are tested against fixtures without a toolkit.
- Books that depend on scripts, remote fonts or media lose them. Fixed-layout books read as text.
- Everything is read into memory; the page of a large book is large, hence the planned chapter-at-a-time fallback.
- Reading a book is reading project files, not running them: no trust check ([ADR 0053](0053-project-trust.md) applies
  to what the project configures).
- Further ideas are in [../ideas/ebooks.md](../ideas/ebooks.md).

## Fix round (amendment)
- The page's one script is in the `<head>` (its hash is over the emitted text, unchanged in principle), so the click
  handler exists before a large body has been parsed and no link navigates early. Marks are only `::highlight(mark-*)`
  rules, no `.mark-*` classes: with Book Style a book's own `class="mark-word"` would paint. `mark-start` uses the
  longhands (`text-decoration-line`, `-color`, `-thickness`): the shorthand with a thickness is dropped by this WebKit
  and the underline did not show (found by a pixel test, `EbookPagePaintUiTest`).
- Chapter mode is decided from an estimate: `BookPageBuilder.build(inline = false)` embeds no images or fonts (an `img`
  without `src`, CSS `url()` as `none`) with the same text, skip ranges, chapters, contents and anchors; the estimate is
  its `html.length` plus 4/3 of the bytes of the referenced resources (per reference). The inline page is built only for
  a book that stays one page, and its html lives in `BookLoad.Ready.first` for the view to show, not in `LoadedBook`.
- Text directly inside `table`, `thead`, `tbody`, `tfoot` and `tr` (or other markup that is not table structure) gets a
  row and cell (`<tr><td>`, in a `tr` a `<td>`) of its own, because HTML parsers move it before the table and the
  offsets would stop rising in document order.

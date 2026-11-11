# 0164. Read Aloud over another module's surface

- Status: accepted; see [0124](0124-read-aloud-speech.md), [0128](0128-read-aloud-in-editors.md), [0084](0084-testability-logic-out-of-controls.md)
- Date: 2026-11-11

## Context
Read Aloud reads in its own tab and in place in plain-text and Markdown editors ([ADR 0128](0128-read-aloud-in-editors.md)).
An e-book reader (a coming `ebooks` module) renders a book in a WebView and wants the same reading over its own
text: the tool's toolbar and setup notice, one playback app-wide, the shared voice and speed, and the sentence and
word highlight, which only the WebView can draw. Its text is not a `TextEditor` or an `ExtensibleCodeEditor`, so
neither surface of [ADR 0128](0128-read-aloud-in-editors.md) fits; chapters follow each other, and the reader wants
to skip parts of the text (footnote markers, page numbers) without changing what it shows.

The building blocks (`ReadAloudModel`, `ReadingBinding`, `ReadingSurface`, `ReadAloudToolbar`, `SetupNotice`) are
`internal` to `speech`. Options compared:
- **Make them public:** the other module would drive the model, the binding and the controls itself, and every
  internal change (the `ReadAloudUi` record, the styled overlay ranges, the model's entries) would become an API change.
- **A small public wrapper:** the other module implements a surface interface and gets controls to place; the
  internals stay internal.

## Decision
A small public wrapper in package `dev.sluide.speech`:
- **`ReadingText(shown, start, end, skip)`:** the plain text a reading covers. It reads from the sentence that contains
  `start` up to `end`, exclusive; a sentence that ends right at `start` is not read. The chars in the `skip` ranges are
  not spoken and separate words; the ranges may be unsorted, overlap or reach outside the text. The 1 MiB limit of the
  tool measures `start` to `end`. A range with nothing to speak from `start` on is "Finished" at once (an empty
  chapter), so a continuous reading goes on.
- **Bounded preparation:** only the text from the paragraph break before `start` (a gap with two or more line breaks
  that are not skipped, a hard sentence break) is prepared, at most 64 KiB back
  (`SpeechText.paragraphStart`). The sentences from `start` on come out as when preparing from 0.
- **`ReadAloudSurface`:** what the view provides, on the FX thread: `readingText()` (asked at every Play; null shows
  "Nothing to read."), `lock(locked)`, `highlight(sentence, word)` (`start until end` ranges in the shown text, null
  clears) and `reveal(start, end)`; each only when it changes.
- **`ReadAloudControls`**, from the window bean's `ReadAloud.controls(surface)`: the embedded toolbar (with
  `speech.css`) and the setup notice (not visible and not managed without problems) for the caller to place, the
  `activity` for its tab marker, `play`, `togglePause`, `stop`, `jumpTo(offset)`, `handleKey(event)` (the editor keys:
  Shortcut+Enter plays while idle, Space and Escape while reading), `onFinished` and `dispose`.
- **`onFinished`** fires once when the reading reached `end` on its own (or found nothing to speak), not after Stop or
  an error, after everything of the finished reading is applied, so it may call `play()` for the next chapter.
- **`jumpTo(offset)`** works anywhere in `[0, end)` of the last `ReadingText` while reading: inside the prepared part
  it restarts there as in the editors; before it, it reads the same text again from `offset`. Either way a paused
  reading stays paused, so a double-click on an earlier paragraph of the page behaves like one on the current.
- **`SpeechText.preparePlain(shown, skip)`:** the plain preparation of `SpeechText.prepare` with skipped ranges. A
  surface's text is never prepared as Markdown.
- Each controls instance has its own `ReadAloudModel` on the window's shared host, like an editor's reading: voice and
  speed are the shared preferences, and starting one reading pauses the others app-wide (`PlaybackCoordinator`).
- The wrapper reuses the internals: `ReadAloudModel.playReading`, `ReadingBinding` behind an adapter to the public
  surface, and `ReadAloudEditorControls` (the toolbar and notice of [ADR 0128](0128-read-aloud-in-editors.md)).

## Consequences
- Another module reads aloud without depending on the internals; `speech` can change them freely behind the wrapper.
- The caller owns placing the toolbar and notice, mapping its own text to `ReadingText`, drawing the highlight, mouse
  handling (a double-click calls `jumpTo`) and disposing; `speech` knows nothing of WebViews.
- The decisions live in plain classes ([ADR 0084](0084-testability-logic-out-of-controls.md)): `ReadAloudControlsTest`
  runs them with a fake surface, a fake host and fake controls.
- "Sentence N of M" counts from the paragraph of `start`, not from the top of the text. A start more than 64 KiB after
  the last paragraph break may begin in the middle of a sentence.

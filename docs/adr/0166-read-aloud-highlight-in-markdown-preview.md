# 0166. Read Aloud's highlight in the rendered Markdown preview

- Status: accepted; amends [0128](0128-read-aloud-in-editors.md); see [0016](0016-markdown-preview.md), [0084](0084-testability-logic-out-of-controls.md), [0141](0141-editor-api-for-language-features.md)
- Date: 2026-11-11

## Context
Read Aloud in a Markdown editor highlights the spoken sentence and word in the source only
([ADR 0128](0128-read-aloud-in-editors.md)): it overlays the layer `read-aloud` with `StyledRange`s styled
`speech-sentence` and `speech-word`. In Preview mode the source is hidden, so nothing shows what is being read, and in
Split the preview only follows by the scroll sync. `previews` must not know `speech`, and the rendered, sanitised HTML
did not say which source text a word of the page came from.

## Decision
- **Seam in `file-viewers`, feature-neutral:** `CodeEditor.addOverlayListener { layer, ranges -> }` (the editor that
  views embed, `CodeEditors.open`) is called on the FX thread after every change of an overlay layer's ranges: set
  (clamped to the text), cleared, cleared by an edit or a reload, and the moved ranges of a following layer
  ([ADR 0141](0141-editor-api-for-language-features.md)). Setting the same ranges again tells nothing.
- **Source offsets on rendered runs:** for a file's preview (`MarkdownRenderer.render`), a second parser keeps the
  source spans of inlines too (`IncludeSourceSpans.BLOCKS_AND_INLINES`; the outline, export and notebooks keep the
  parser with block spans only), and front matter is parsed as blank lines of its own length, so that input indexes
  are source offsets. There every text and inline code is a `<span data-o="N">` run (`SourceOffsetRenderer`), N the
  source offset of its first char: the page contract of the web kit's `TextMarks`
  ([previews.md](../features/previews.md#web-kit-for-other-modules)), with source offsets instead of offsets in the
  page's text, so that gaps are allowed. A backslash escape or an entity starts a new run, so that the chars after it
  keep their offsets. The first text of a task item starts after its box (`[ ] `, `[x] `), which the task list
  extension cuts from the literal but not from the source span; for other texts whose source starts with something
  the literal leaves out, the start of the literal is searched in the source. Fenced code, math, diagrams, front
  matter, image alt texts and HTML have no runs.
- **Only the runs carry offsets:** one wrong offset would mislead `TextMarks`' search over all runs, so the file's
  HTML must not bring any. The renderer writes the runs' offsets under an attribute name of that rendering only
  (`data-o-` and a random suffix), leaving the file's HTML as written. Before sanitising, every `data-o` attribute
  of the parsed page is removed (however the file spelled it, as jsoup reads it) and the rendering's attribute is
  renamed `data-o`; the sanitiser keeps `data-o` on `span`. The page embeds `TextMarks.SCRIPT` after `preview.js`.
  Export, notebooks and the other pages that use the renderer render without runs, and lose the file's `data-o`
  too.
- **The mirror:** `PreviewHighlightMirror` in `previews`, for previews whose `Preview.mirrorsHighlights` (Markdown),
  makes each style class of the overlay layers a `TextMarks` mark of that name (`speech-word` →
  `::highlight(mark-speech-word)`), covering all ranges of that class; clearing a layer clears its marks. Only the
  classes that `preview.css` styles are mirrored (`MIRRORED_STYLES`: `speech-sentence`, `speech-word`, in the colours
  of `speech.css`): other layers on a Markdown source (a language server's diagnostics, semantic tokens or
  occurrences) would not show anyway, and would make the preview scroll. A mark that appears before one already shown
  sets both again, so that the later one (the word) paints over the earlier one (the sentence).
- **Scrolling:** in Preview mode the last range of a changed layer (the word) is revealed when it is out of view; in
  Split nothing is revealed, as the scroll sync from the source moves the preview.
- **Re-rendering:** when the view's own page has loaded (a save, a reload, Show the page again), the current marks are
  set again. Disposing the view stops the mirror.
- **Unsaved changes:** the preview shows the saved text, the overlay offsets are those of the current text: while the
  source has unsaved changes nothing is marked.

## Consequences
- Any feature that overlays a layer on a Markdown source can show it in the preview by adding its class to
  `MIRRORED_STYLES` and `preview.css`; nothing else needs to change, and `speech` did not.
- Reading a Markdown file with unsaved changes highlights the source only.
- A run whose rendered text differs from its source in another way than an escape or an entity (a line ending in a
  code span) keeps the offsets of its first part only; marks inside it may land a little off.
- Text in HTML written in the file, and image alt texts, are read aloud but not marked in the preview.

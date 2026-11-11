# 0128. Read Aloud inside the plain-text and Markdown editors

- Status: accepted; amends [0108](0108-code-editor-extensions-and-context-menu.md), [0124](0124-read-aloud-speech.md); amended by [0130](0130-soft-wrap.md), [0166](0166-read-aloud-highlight-in-markdown-preview.md); see [0110](0110-tools-menu.md), [0084](0084-testability-logic-out-of-controls.md), [0083](0083-zooming-document-previews.md)
- Date: 2026-11-05

## Context
The user wants Read Aloud inside the editors that show text (plain text and Markdown first), not only in its own tab:
controls in the editor's toolbar, the text locked while it reads, sentence and word highlighted in it. The modules must
not couple for this: `file-viewers` and `previews` must not know `speech`.

Options compared:
- **(A) Grow the [ADR 0108](0108-code-editor-extensions-and-context-menu.md) extension point** (`ExtensibleCodeEditor`)
  with feature-neutral capabilities; `speech` contributes through its existing `ReadAloudExtension`. No new module
  edge, and any other feature can use the capabilities.
- **(B) A speech-specific window service** that the viewers look up: `file-viewers` and `previews` would name speech
  concepts, and the service would need the same view access anyway.
- **(C) `speech` wraps the viewers in its own `FileViewer`:** works for plain text only. A Markdown preview already has
  a toolbar, so the wrapper would add a second toolbar row, and the preview's modes and Export stay out of reach.
- **(D) A `previews → speech` dependency:** couples the WebView module to ONNX Runtime, and plain text stays impossible,
  because `file-viewers → speech` would be a cycle (`speech → file-viewers`).

## Decision
Option A. `ExtensibleCodeEditor` gets these members, all on the FX thread, with interface defaults so fakes compile:
- **Reading the editor:** `plainText` (a file without a language viewer), `text` (whole text, `\n` breaks, unsaved
  edits included; it builds the string, so not per keystroke), `caretOffset`, `selection`, `onTextChanged(action)`
  (every edit or reload, without building the text).
- **Placing UI:** `addToolbarItems(nodes)` (an `AutoCloseable` removes them), `addBanner(node)` (above the text, below
  the find bar), `addStylesheet(url)` (so an extension's overlay classes reach the text area).
- **`lock()`:** read-only until every returned lock is closed. Edits are ignored, `canReplaceText` is false, Replace and
  `FileViewHost.editText` are refused; caret, selection, copy, find and scrolling work.
- **Highlight:** `overlay(layer, ranges)` replaces one named layer over highlighting, find matches and masks, survives
  background re-highlighting, and is cleared by an edit or a reload; `reveal(start, end)` scrolls without moving the
  caret or taking the focus.
- **Keys and activity:** `addEventFilter(type, filter)` on the text area; `addActivity(activity)` makes the view's
  `FileView.activity` show the running marker (RUNNING wins over FAILED over NONE).

`CodeEditor.toolbarItems` is the read-only list of what extensions added, in order. A standalone `CodeView` (opened by a
viewer itself) shows them in its own toolbar row, hidden while empty. A view that embeds a code editor places the items
itself and forwards `activity`: previews put them after their left controls (TOC first), in every mode, and
`PreviewFileView.activity` passes on the source's.

Read Aloud in the editors:
- **Which editors:** every plain-text fallback file (`DefaultTextFileViewer`: `.txt`, `.log`, `LICENSE`, ...) and
  Markdown (`.md`, `.markdown`). Code files and other previews keep the old behavior: "Read Aloud" in the context menu
  sends the text to the tool tab.
- **One reading per editor**, each with its own position, on the window's shared host; starting one pauses the running
  one app-wide (`PlaybackCoordinator`, as with the media player and the tool tab). `EditorReading` drives one editor.
  Voice and speed are the shared preferences (`SpeechPreferences`) and are re-read at every Play.
- **Controls:** those of the tool tab (Play/Pause, Stop, accent, gender, speed, status), in every mode, Source included.
- **Play:** with a selection it reads exactly the selection and stops at its end; without one, from the caret's sentence
  to the end. It reads the current text, unsaved edits included, with no save question. The tool tab reads a selection
  only, too, as it shares the model.
- **Lock:** while preparing, speaking or paused the editor is locked, so the highlight offsets stay valid. Stop or
  Escape unlocks.
- **Keys** (editor-local, as in the tool tab): Shortcut+Enter plays (free there: neither view is runnable), Space
  pauses and resumes and Escape stops while reading, a double-click jumps to the sentence. Escape in the find field
  still closes the find bar.
- **Context menu** in these editors: "Read Aloud" and "Read Selection Aloud" read in place. The explorer's "Read Aloud"
  still opens the tab.
- **Setup:** the `SetupNotice` is a banner while the model or espeak-ng is missing; the status reads "Setup needed."
  while idle, which shows in the Markdown preview mode where the banner hides with the source.
- **1 MiB:** over 1 MiB (the selection's size counts when there is one) Play refuses with the status "The text is larger
  than 1 MiB."
- **Reload:** a change on disk while reading stops the reading, then the usual reload runs. Closing the tab or window
  stops it without a question. A renamed file keeps reading.
- **Highlight:** the source only. In Split the preview follows through the scroll sync, as `reveal` scrolls the source.
  (The rendered Markdown preview highlights too since [ADR 0166](0166-read-aloud-highlight-in-markdown-preview.md).)
- **Export moves right** in every preview that has it (before the zoom and the mode toggles) and is hidden in Source,
  to make room for the items on the left ([ADR 0083](0083-zooming-document-previews.md); [ui-conventions](../ui-conventions.md)).

## Consequences
- Other views that embed a code editor (Compose, Dockerfile, Gradle, HTTP, the env editor) don't place `toolbarItems`
  or forward `activity` yet: an extension's items don't show there. `EnvFileView` and `GitignoreFileView` show none.
  Read Aloud in them is in [ideas](../ideas/speech.md).
- A selection is prepared without its surroundings: Markdown inside a selection is cleaned without the context of the
  lines around it.
- Interface defaults keep fakes compiling but hide a missing override in the real view, so the real view's members are
  tested in `CodeEditorExtensionsUiTest`; the decisions live in plain classes ([ADR 0084](0084-testability-logic-out-of-controls.md)).
- Any feature can use the capabilities (toolbar items, banner, lock, overlay layer, activity) without a module edge.
- The highlight is in the source only; the rendered preview is not highlighted (until
  [ADR 0166](0166-read-aloud-highlight-in-markdown-preview.md)).

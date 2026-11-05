# 0130. Soft wrap in the file editors

- Status: accepted; amends [0128](0128-read-aloud-in-editors.md); see [0070](0070-window-services.md), [0101](0101-shared-data-file.md), [0084](0084-testability-logic-out-of-controls.md), [0054](0054-keep-source-files-small.md)
- Date: 2026-11-05

## Context
Long lines (prose in plain text and Markdown, minified JSON, long log lines) run off the right edge of the editors and
need horizontal scrolling. RichTextFX's `CodeArea` can wrap at the visible edge (`wrapText`). The questions were
where the switch lives (per editor, per file, per window or user-wide), how a view finds it, where its control goes
in the views that embed an editor, and what of the editor depends on lines being one row high.

## Decision
- **One user-wide live switch**, off by default: `SoftWrap` (module `file-viewers`, root package, a Spring singleton)
  with `enabled: BooleanProperty` (FX thread). Toggling it in any editor wraps or unwraps every open editor in every
  window at once. It is kept in `~/.sluide/shared.json` ([ADR 0101](0101-shared-data-file.md)) as
  `"editor": {"softWrap": true}`: only `true` is written, off removes the key and an `editor` left empty; other fields
  of `editor` stay. Missing or invalid values, or an `editor` that is no object, read as off. Read on first use,
  written off the FX thread, changes within 300 ms coalesced, a pending write flushed when the app quits (as
  `MediaPreferences` and `SpeechPreferences`). One switch, because wrapping is a reading preference of the user, not
  a property of a file: a per-file or per-tab switch would have to be found and set again in every tab, and editors
  that differ surprise more than they help. New module edge `file-viewers → project-data`.
- **Window service:** `MainWindow` registers the bean as a window service ([ADR 0070](0070-window-services.md)), as
  the views of singleton viewers reach window-scoped and shared beans only that way. A code view binds to it on the
  first host that has it (later hosts change nothing) and unbinds on dispose, so closed editors don't stay reachable
  from the singleton. Without a host (tests, embedded editors before attach) the view toggles a local property.
- **Controls:** per editor an icon-only, non-focusable toggle (`MaterialDesignW.WRAP`, tooltip "Soft Wrap
  (Shortcut+Alt+Z)"), a **Soft Wrap** check item in the editor's own context-menu items (before the extensions'
  separator) and the editor-local key **Shortcut+Alt+Z** (consumed; it was free). The logic is in
  `code/SoftWrapToggle.kt`, not in `CodeView` (near the size limit of [ADR 0054](0054-keep-source-files-small.md)).
- **`CodeEditor.softWrapButton`:** the toggle of an embedded editor. The embedding view places it first in its own
  toolbar, before its own left controls, and hides it while the source side is hidden; the editor never sets its
  visibility. Every view that embeds a code editor places it (previews, shell scripts, Gradle, Docker, HTTP client),
  each in its own change.
- **Standalone row always shows:** a code view with a toolbar of its own (code and plain-text viewers, now also the
  `.gitignore` view) always shows its row, Soft Wrap first, then the extensions' toolbar items; [ADR 0128](0128-read-aloud-in-editors.md)
  hid it while empty. The `.env` view has Soft Wrap on the far left of its toolbar, which now shows for every `.env`
  file; Show values keeps its rule.
- **Gutter:** in a wrapped (tall) row, column nodes sit at the top beside the first line; the line number was already
  at the top, the change marker spans the row.
- **Scope:** the file editors only (`CodeView`). Not the side-by-side diff views (their rows must stay aligned line by
  line), not `CodeDisplay` (read-only output such as HTTP responses and inspect JSON), not terminal output.
  `TextAreaEditor` (tool tabs) always wraps already.

## Consequences
- Read Aloud's highlight and `reveal` work on wrapped text: they use character bounds and style spans, not line
  heights (tested in `CodeEditorExtensionsUiTest`).
- Known limit: the Markdown scroll sync is line-based, so inside one long wrapped paragraph the preview does not
  follow the source row by row.
- The `.gitignore` view now shows the extensions' toolbar items in its row too (ADR 0128 listed it among the views
  without them).
- Views of other modules show no Soft Wrap control until they place `softWrapButton`; their editors still follow the
  switch and the key and menu item work in them.
- Listed in [../index.md](../index.md); feature page [soft-wrap.md](../features/soft-wrap.md).

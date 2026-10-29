# 0108. Code editor extensions and the code editor's context menu

- Status: accepted
- Date: 2026-10-29

## Context
The Coloring tool ([ADR 0109](0109-coloring-tool.md)) adds two things to code editors: color swatches in the gutter
and an "Open in Coloring" item for the color under the caret. Neither fits the existing hooks:
- `GutterColumns.addGutterColumn` is per view; only the code that creates or wraps a `CodeView` can add a column (the
  `.gitignore` dots, [ADR 0080](0080-gitignore-line-effects.md), and the HTTP run icons wrap their view). Swatches are
  wanted in CSS, SCSS, Vue, TypeScript files and in the source sides of the HTML and SVG previews, which are made by
  `CodeFileViewer`, `CodeEditors.open` and several modules' views.
- Code editors have no context menu at all, so there is nowhere to put an item.
- `file-viewers` must not depend on the module that knows colors, and viewers are singletons shared by all windows.

Every view that embeds a `CodeView` (the code viewers, the previews' source sides, `.http`, Gradle, shell script,
`.env` and `.gitignore` views, `CodeEditors.open`) passes its `FileViewHost` on to it; editors in dialogs
(`CodeEditors.text`) have no host.

## Decision
- **Extension point** (module `file-viewers`, root package): `CodeEditorExtensions`, a window service
  ([ADR 0070](0070-window-services.md)) that `MainWindow` registers and fills with `add(extension)`. A
  `CodeEditorExtension` is `fun extend(editor: ExtensibleCodeEditor): AutoCloseable?`. `ExtensibleCodeEditor` extends
  `GutterColumns` with the file shown (following moves), the line count and a line's text, the shown lines and their
  changes, and `addContextMenuItems((EditorMenuTarget) -> List<MenuItem>)`; `EditorMenuTarget` is the line and column
  the menu is for. RichTextFX stays out of the API, as for `CodeEditor`.
- **Attached in `CodeView`**: when a `CodeView` first gets a host that has the service, it calls every extension once;
  a later host changes nothing. The handles are closed when the view is disposed. One hook covers every embedding
  view without touching the viewers; dialog editors get no extensions. Extensions run on the FX thread and restrict
  themselves to the file types they want.
- **Context menu** in every code editor: Undo, Redo | Cut, Copy, Paste, Select All, then the extensions' items after a
  separator; rebuilt each time it shows, with items disabled that cannot act (nothing selected, read-only text,
  nothing to undo). A secondary press outside the selection moves the caret to the pressed position first, as in
  IntelliJ and VS Code; inside the selection it keeps the selection. Opened by keyboard (Menu key, Shift+F10), the
  menu is for the caret. The items show the editing keys, which keep working through the editor's own key bindings
  ([ADR 0022](0022-editor-key-bindings.md)).
- `CodeView` stays under 600 lines ([ADR 0054](0054-keep-source-files-small.md)): the following of change markers
  moves to `ChangeMarkerFollower`, the extensions and the menu are their own files.

## Consequences
- A feature adds gutter columns or menu items to all code editors with one `add` call in `MainWindow`, without
  wrapping viewers; a view hosted before the service is registered gets no extensions, so `MainWindow` registers it
  with the other window services, before any tab is opened.
- Extensions must keep their gutter refreshes rare: `refreshGutter` rebuilds every shown row, including other columns.
- Listed in [../index.md](../index.md).

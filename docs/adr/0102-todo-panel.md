# 0102. TODO panel

- Status: accepted, amended by [0103](0103-todo-folders.md) (folders)
- Date: 2026-10-28

## Context
Users want to keep notes on what is left to do, both for one project and across projects, next to the code and without
a separate tool. [ADR 0090](0090-per-project-metadata-store.md) gave modules a per-project data file, and
[ADR 0101](0101-shared-data-file.md) a shared data file for data that belongs to no project. The notes carry Markdown
(lists, links, images, checkboxes), so they need a renderer; the previews' renderer is a WebView, too heavy for one
card per TODO.

## Decision
- **Two scopes.** A TODO is a one-line summary and a Markdown description. Shared TODOs belong to the user and are kept
  in the `todos` array of the shared data file `~/.sluide/shared.json`; project TODOs in the `todos` array of the
  project's data file. The array is the display order. Entries carry a random UUID `id`; fields this version does not
  know, and entries that are no objects or have no `id`, are kept on every write. New TODOs go to the top. There is no
  done state: a TODO is deleted (after a confirmation) when it is done. Moving a TODO between the scopes in the dialog
  writes the target first, then removes the source, so a failure loses nothing.
- **One `Todos` for the application.** `Todos` (module `todos`, a singleton bean over `ProjectDataStore`) does all
  reads and writes and notifies a listener per change; every window's panel follows it, so a TODO added in one window
  shows in the others at once.
- **A window part on the right.** `TodoPanel` (a window bean) is the last item of the main split, full height. It is
  collapsed to a narrow stripe by default (and in every new window); View → TODOs (Alt+6, Cmd+6 on macOS) or a click on
  the stripe expands it to Shared and Project tabs of cards. `ide` wires it in `WindowTodos` (ADR 0068): the divider
  arithmetic, the menu item, the editor area for files, the system browser for links, the editor font for the dialog.
  The width the user gave it and the selected tab are kept only while the window is open; the window's width goes to
  the editor, not the panel.
- **Markdown is rendered natively** into JavaFX nodes: commonmark's AST becomes `TextFlow`/`Text`, `CheckBox` and
  `ImageView`, so a card needs no WebView. The supported subset is paragraphs, headings, emphasis, strikethrough, inline
  and fenced code (unhighlighted), block quotes, bullet, numbered and task lists, rules, links, autolinks and images;
  tables, syntax highlighting, math and Mermaid are not rendered (their source is shown). Task checkboxes edit the
  source and save it. Links and images are confined to the project: relative or absolute paths below the project folder
  open in the editor or load; `http`/`https` (and `mailto` links) go to the system browser; other schemes and paths
  outside the folder do nothing. Shared TODOs have no folder, so they only link to the web.
- **Reordering by plain mouse drag** on a card's header (press, drag, release; Escape cancels; with folders and an
  insertion line since [ADR 0103](0103-todo-folders.md)), not platform drag and drop, which leaves a stale pointer grab on Linux ([ADR 0073](0073-explorer-multi-selection-and-moving.md)).
- **The dialog's description editor is `CodeEditors.text`** of `file-viewers` with the Markdown highlighter, in the
  editor font, inside the dialog's Write | Preview switch. `ide` supplies it through `TodoPanel.descriptionEditor`, so
  `todos` does not depend on `file-viewers` ([ADR 0047](0047-module-dependencies-and-shared-modules.md)); without it the
  dialog uses a plain text area (tests).

Rejected:
- A WebView per card: heavy for a long list and it needs height measuring to size each card.
- The previews' renderer through a new dependency: `todos` and `previews` are unrelated feature modules (ADR 0047).
- A separate TODO file per scope: the project data and the shared data file already exist, with locking and
  round-tripping of unknown fields (ADR 0090, 0101).
- Pinning the collapsed panel with the divider at 1.0, like the git log: the `SplitPane` squeezed the stripe below its
  width and moved the explorer's divider, hence the stripe's own width and `afterFit` of `fitExplorer`.
- A done state: finished TODOs would pile up; deleting is one click and asks first.

## Consequences
- The renderer is small and limited: HTML, tables and the like show as text; extending it means more node builders.
- All windows write the same shared file, so the shared list is rewritten with every change (ADR 0101).
- `MainWindow` gets a third split item: `WindowGitLog.show` replaces the centre in place instead of appending it, and
  `fitExplorer` is followed by the panel's fit because the explorer's divider moves the other.
- Listed in [../index.md](../index.md).

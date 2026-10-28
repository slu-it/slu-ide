# TODOs

**Module:** `modules/todos` (package `dev.sluide.todos`), wired in `ide` by `WindowTodos`
([ADR 0102](../adr/0102-todo-panel.md), folders: [ADR 0103](../adr/0103-todo-folders.md))

A list of things left to do at the right of the window: Shared TODOs for all projects and Project TODOs for the open
folder, as cards with Markdown notes.

- **The stripe:** the panel is collapsed by default, also in every new window, to a narrow stripe at the right edge
  (a chevron and "TODOs" written upwards). A click on it, Enter or Space on it when focused, or View → TODOs (Alt+6,
  Cmd+6 on macOS; not while a terminal has the focus on Linux and Windows) expands the panel to its tabs, which
  takes the focus. The panel's Collapse button (the chevron in its header) or View → TODOs collapses it; if the focus
  was in it, the editor gets it back. The menu item is always enabled.
- **Width and tab:** the expanded panel is 320 px wide at first; the width the user drags it to and the selected tab are
  kept while the window is open (collapsing and expanding again restores them), but not between runs. Resizing the
  window changes the editor's width, not the panel's.
- **Tabs:** "Shared" (the default) and "Project", each with the number of its TODOs. The Project tab is disabled in a
  window without a folder.
- **Cards:** a card shows the summary on one line with buttons to Expand (the chevron; also hides the description
  again), Edit (pencil) and Delete (bin). Expanding shows the description as rendered Markdown; a card without a
  description has no chevron, but its summary lines up with the others. A double click on the summary edits. Add (at
  the top of a tab) opens the dialog for the tab's scope; an empty tab says "No TODOs". New TODOs go to the top. There
  is no done state: delete a TODO when it is done.
- **Folders:** New Folder (beside Add) asks for a name and a scope and puts the folder at the top of the tab. Folders
  are one level deep and sit among the TODOs in any order. A folder's header shows its name and number of TODOs, with
  buttons to collapse or expand it (stored, so it stays after a restart and in every window; new folders are
  expanded), to add a TODO into it (at its top; a collapsed folder expands), to edit it (also by a double click on the
  name: name and Shared | Project, which moves the folder with its TODOs to the top of the other tab) and to delete
  it. An expanded empty folder says "Drag TODOs here". The tab's number counts the TODOs in folders too.
- **The dialog:** a Shared | Project switch (Project is disabled without a folder), a Summary field and a Description
  editor with Write | Preview. Write is a Markdown code editor in the editor font (the keys of code editors apply,
  [keyboard-shortcuts.md](keyboard-shortcuts.md)); Preview renders the text as the card does. The summary is required.
  Changing the switch of an existing TODO moves it to the other scope. The Folder field offers "No folder" and the
  folders of the selected scope; a TODO whose scope and folder stay the same keeps its place, otherwise it goes to the
  top of the chosen folder (or of the root).
- **Delete** asks first, naming the TODO; Cancel is the default button. Deleting a folder with TODOs offers Delete
  All, Keep TODOs (they take the folder's place at the root) and Cancel.
- **Reordering:** drag a card or a folder by its header with the mouse; a line shows where it lands. A TODO lands
  before a card when dropped on its upper half and after it on the lower half, in that card's folder or at the root.
  Dropped on a folder's header it goes to the folder's top (the header lights up when the folder is collapsed), except
  on the header's top quarter (before the folder) and, for a collapsed folder, its bottom quarter (after it). An empty
  folder's "Drag TODOs here" takes TODOs too. Folders move only among the root items. Escape cancels the drag. It is a
  plain mouse drag, not the platform's drag and drop.
- **Markdown:** rendered into JavaFX nodes, without a WebView. Supported: paragraphs, headings, bold, italic,
  strikethrough, inline code, code blocks (plain), block quotes, bullet, numbered and task lists, rules, links and
  images. Not supported (the source is shown as text): tables, syntax highlighting, math, Mermaid, HTML. A task
  checkbox in an expanded card toggles the `[ ]` in the source and saves it at once.
- **Links and images:** `http` and `https` links (and `mailto`) open in the system browser. A relative or absolute path
  below the project folder opens the file in the editor, and as an image it is shown; a path outside the folder, other
  schemes (`file:`, `javascript:` …) and any local path in a Shared TODO do nothing / show no image. Web images are
  loaded from the web.
- **Storage:** Shared TODOs in `~/.sluide/shared.json`, Project TODOs in the project's file below `~/.sluide/projects`
  ([ADR 0090](../adr/0090-per-project-metadata-store.md), [ADR 0101](../adr/0101-shared-data-file.md)), each as the
  array `todos` in display order, folders as entries with their own `todos`. Other windows show a change at once. If a TODO cannot be saved, an error says so and
  the list shows what is stored.
- **Shortcuts:** [keyboard-shortcuts.md](keyboard-shortcuts.md), "TODO panel".

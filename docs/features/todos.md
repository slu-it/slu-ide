# TODOs

**Module:** `modules/todos` (package `dev.sluide.todos`), wired in `ide` by `WindowTodos`
([ADR 0102](../adr/0102-todo-panel.md))

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
  again), Edit (pencil) and Delete (bin). Expanding shows the description as rendered Markdown. A double click on the
  summary edits. Add (at the top of a tab) opens the dialog for the tab's scope; an empty tab says "No TODOs". New
  TODOs go to the top. There is no done state: delete a TODO when it is done.
- **The dialog:** a Shared | Project switch (Project is disabled without a folder), a Summary field and a Description
  editor with Write | Preview. Write is a Markdown code editor in the editor font (the keys of code editors apply,
  [keyboard-shortcuts.md](keyboard-shortcuts.md)); Preview renders the text as the card does. The summary is required.
  Changing the switch of an existing TODO moves it to the other scope.
- **Delete** asks first, naming the TODO; Cancel is the default button.
- **Reordering:** drag a card by its header with the mouse to another place in the list; the cards in between make
  room to show where it lands. Escape cancels the drag. It is a plain mouse drag, not the platform's drag and drop.
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
  array `todos` in display order. Other windows show a change at once. If a TODO cannot be saved, an error says so and
  the list shows what is stored.
- **Shortcuts:** [keyboard-shortcuts.md](keyboard-shortcuts.md), "TODO panel".

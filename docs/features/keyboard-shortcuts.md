# Keyboard shortcuts

All key bindings of Thoreau in one place. Each one is described with its feature on the linked page; this page is the
overview and must list every binding (see [Keeping this page current](#keeping-this-page-current)).

**Shortcut** is Ctrl on Linux and Windows and Cmd on macOS. The bindings are fixed; there is no keymap setting.
The menu accelerators work everywhere in the window, in a focused terminal only those it does not take (see
[Terminal](#terminal)).

## Menus
Accelerators of the menu bar ([open-folder.md](open-folder.md), [settings.md](settings.md),
[scratch-files.md](scratch-files.md), [git-diff.md](git-diff.md), [git-log.md](git-log.md),
[commit-and-push.md](commit-and-push.md), [terminal.md](terminal.md), [editor-tabs.md](editor-tabs.md)).

| Keys | Action |
|---|---|
| Shortcut+Shift+N | File → New Scratch File… |
| Shortcut+O | File → Open Folder… |
| Shortcut+S | File → Save |
| Shortcut+Alt+S | File → Save All |
| Shortcut+W | File → Close Tab (file and diff tabs, not terminals) |
| Shortcut+, | File → Settings… |
| Shortcut+Shift+D | View → Show Changes (side-by-side diff of the current file) |
| Alt+9 (Cmd+9 on macOS) | View → Git Log (expand or collapse) |
| Shortcut+K | Git → Commit… |
| Shortcut+Shift+K | Git → Push… |
| Shortcut+Shift+T | Terminal → New Terminal |

## Code editor
Code views and the editors built on them, e.g. `.http` files ([text-editing.md](text-editing.md),
[ADR 0022](../adr/0022-editor-key-bindings.md)).

| Keys | Action |
|---|---|
| Enter, Shift+Enter | New line, keeping the indentation |
| Tab / Shift+Tab | Indent / outdent, in the file's indent style (also several lines) |
| Shortcut+D | Duplicate the selection or line |
| Shortcut+Y | Delete lines |
| Alt+Shift+Up / Down | Move lines up / down |
| Shortcut+/ or Shortcut+Shift+7 | Comment lines out or back in |
| Shortcut+F | Find |
| Shortcut+R | Replace |
| F3 / Shift+F3 | Next / previous match |
| Enter / Shift+Enter in the find field | Next / previous match |
| Enter in the replace field | Replace the selected match |
| Escape | Close the find bar |
| Shortcut+G | Go to line (`line[:column]`) |
| Shortcut+Z / Shortcut+Shift+Z | Undo / redo |

## Side-by-side diff
[git-diff.md](git-diff.md)

| Keys | Action |
|---|---|
| F7 / Shift+F7 | Next / previous change |
| Escape | Back to editing, when the diff was opened from the editor |

## Previews
All previews and images zoom, only while the rendered view has the focus, not in the source beside it; Fit
(Shortcut+9) only for images and diagrams (SVG, Mermaid, Bicep and workflow graphs), not for documents
([previews.md](previews.md), [ADR 0033](../adr/0033-zooming-images-and-diagrams.md),
[ADR 0083](../adr/0083-zooming-document-previews.md)). The table of contents is the Markdown preview's
([markdown-preview.md](markdown-preview.md)).

| Keys | Action |
|---|---|
| Shortcut+Plus (or =) / Shortcut+Minus | Zoom in / out |
| Shortcut+0 | 100 % |
| Shortcut+9 | Fit (images and diagrams only) |
| Shortcut+mouse wheel, pinch | Zoom |
| Enter / Escape in the table of contents | Jump to the heading / close it |

## HTTP client
[http-client.md](http-client.md), [http-client-env-editor.md](http-client-env-editor.md)

| Keys | Action |
|---|---|
| Shortcut+Enter | Run the request at the caret |
| Click on the run icon beside a request | Run that request |
| Enter or F2 in the environment table | Edit the cell |
| Escape while editing a cell | Cancel the edit |

## Gradle
[gradle.md](gradle.md)

| Keys | Action |
|---|---|
| Enter in the task tree | Run the selected tasks, in tree order |
| Double-click on a task | Run the selected tasks (the clicked one) |
| Shift+click, Shortcut+click in the task tree | Select several tasks to run together |

## File explorer
[file-explorer.md](file-explorer.md)

| Keys | Action |
|---|---|
| Shift+click, Shift+Up/Down | Select a range |
| Shortcut+click | Add or remove an entry from the selection |
| Shortcut+A | Select all |
| Enter | Open the selected files (all regular files of the selection) |
| F5 | Refresh |
| F2 | Rename (only when one entry is selected) |
| Delete or Shortcut+Backspace | Delete the selected entries (one confirmation) |
| Drag entries onto a folder or the empty area | Move them (after a confirmation) |
| Escape while dragging entries | Cancel the drag |

## Terminal
[terminal.md](terminal.md)

| Keys | Action |
|---|---|
| Shortcut+Shift+C | Copy the selection |
| Shortcut+Shift+V | Paste |

All other keys the terminal translates go to the shell. On Linux and Windows these include Ctrl+O, Ctrl+S, Ctrl+W,
Ctrl+K and Alt+9, which then do not reach their menu items; Shortcut+Shift+letter, Ctrl+, and Ctrl+Alt+S still do, and
on macOS every Cmd shortcut does, but Cmd+W leaves a terminal tab open: File → Close Tab is disabled for terminals
([terminal.md](terminal.md)).

## Git log
[git-log.md](git-log.md), [commit-and-push.md](commit-and-push.md), [file-history.md](file-history.md)

| Keys | Action |
|---|---|
| Shortcut+F | Focus the filter text, or the branch search while in the branches pane |
| Shortcut+G | Go to a commit, branch or tag |
| Down / Enter / Escape in Go to | Into the list / go / close |
| Escape in the filter text | Clear the text |
| Down in the filter text | Move into the log |
| Down / Space / Escape in a filter dropdown (Branch, User, Path) | Into the list / toggle the entry / close |
| Shortcut+C in the commit table | Copy the revision numbers |
| Escape in the commit table | Stop comparing |
| Escape in the branch search | Clear it |
| Enter or Down in the branch search | Move into the branch tree |
| Enter in the branch tree | Expand or collapse a group, or filter the log by the branch |
| F2 / Delete or Shortcut+Backspace in the branch tree | Rename / delete the branches (remote branches on their remote) |
| Enter or Shortcut+D on a changed file | Show the diff |
| F4 on a changed file | Open the local file |
| Space in the Commit tab | Include or exclude the file |
| Shortcut+Enter in the Commit tab | Commit |
| Shortcut+Alt+K in the Commit tab | Commit and Push… |
| Enter or Shortcut+D in a file history | Show the diff |
| Shortcut+C in a file history | Copy the revision number |
| Escape in the Push dialog | Close it |

## Settings
[settings.md](settings.md)

| Keys | Action |
|---|---|
| Enter in the font family or size field (Display → Fonts) | Take the typed value, without pressing OK |

## Mouse
| Input | Action |
|---|---|
| Middle-click on a tab (editor area, git log area) | Close the tab |

## Pickers
[scratch-files.md](scratch-files.md), [project-picker.md](project-picker.md)

| Keys | Action |
|---|---|
| Up / Down in the scratch type picker | Choose the type |
| Enter / Escape in the scratch type picker | Create the scratch file / cancel |
| Enter in the project picker | Open the project |
| Delete or Backspace in the project picker | Remove the project from the list |
| Escape in the project picker | Cancel |

## Keeping this page current
Whoever adds, changes or removes a key binding updates this page in the same change, as well as the feature page
that describes it. This applies to menu accelerators, `KeyCodeCombination`s and `KeyCharacterCombination`s,
`InputMap` entries, key handlers on `KeyCode`s and mouse bindings beyond plain clicks. Before adding a binding, check
here that it is not taken in the same context. The rule is in `.claude/rules/key-bindings.md`, and the reviewer
checks it.

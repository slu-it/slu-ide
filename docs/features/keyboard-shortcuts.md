# Keyboard shortcuts

All key bindings of Thoreau in one place. Each one is described with its feature on the linked page; this page is the
overview and must list every binding (see [Keeping this page current](#keeping-this-page-current)).

**Shortcut** is Ctrl on Linux and Windows and Cmd on macOS. The bindings are fixed; there is no keymap setting.
The menu accelerators work everywhere in the window, in a focused terminal only those it does not take (see
[Terminal](#terminal)).

## Menus
Accelerators of the menu bar ([open-folder.md](open-folder.md), [settings.md](settings.md),
[scratch-files.md](scratch-files.md), [git-diff.md](git-diff.md), [git-log.md](git-log.md),
[commit-and-push.md](commit-and-push.md), [terminal.md](terminal.md), [editor-tabs.md](editor-tabs.md), [todos.md](todos.md),
[docker.md](docker.md)).

| Keys | Action |
|---|---|
| Shortcut+Shift+N | File → New Scratch File… |
| Shortcut+Shift+G | File → New Generated Scratch File… |
| Shortcut+O | File → Open Folder… |
| Shortcut+S | File → Save |
| Shortcut+Alt+S | File → Save All |
| Shortcut+W | File → Close Tab (file, diff, tool and terminal tabs; a command tab once its command has exited) |
| Shortcut+, | File → Settings… |
| Shortcut+Shift+D | View → Show Changes (side-by-side diff of the current file); in the explorer with two files selected, Compare Files |
| Alt+F1 (Option+F1 on macOS) | View → Select Opened File (reveals the selected tab's file in the explorer and focuses it; not while a terminal has the focus, it sends Alt+F1 to the shell; many Linux desktops (GNOME, KDE, Xfce) grab Alt+F1 themselves, so it may never reach the app there) |
| Alt+1 (Cmd+1 on macOS) | View → Explorer (expand or collapse the explorer column; not while a terminal has the focus on Linux and Windows) |
| Alt+9 (Cmd+9 on macOS) | View → Git Log (expand or collapse) |
| Alt+6 (Cmd+6 on macOS) | View → TODOs (expand or collapse the TODO panel; not while a terminal has the focus on Linux and Windows) |
| Shortcut+K | Git → Commit… |
| Shortcut+Shift+K | Git → Push… |
| Shortcut+Shift+T | Terminal → New Terminal |
| Alt+7 (Cmd+7 on macOS) | Services → Databases (opens the Databases tab or selects it; not while a terminal has the focus on Linux and Windows) |
| Alt+8 (Cmd+8 on macOS) | Services → Docker (opens the Docker tab or selects it; not while a terminal has the focus on Linux and Windows) |
| Shortcut+Alt+Shift+D | Help → Capture Diagnostics (disabled while a capture runs; [diagnostics.md](diagnostics.md)) |

## Code editor
Code views and the editors built on them, e.g. `.http` files ([text-editing.md](text-editing.md),
[ADR 0022](../adr/0022-editor-key-bindings.md)). Editors in dialogs (`CodeEditors.text`) have the editing keys only:
no find, replace or go to line, and Tab indents instead of moving the focus.

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
| Shortcut+Alt+Z (Cmd+Option+Z on macOS) | Soft wrap on/off (all editors; [soft-wrap.md](soft-wrap.md)) |
| Shortcut+Z / Shortcut+Shift+Z | Undo / redo |
| Shortcut+X / C / V / A | Cut / copy / paste / select all (RichTextFX; also shown in the context menu) |
| Menu key, Shift+F10 | Context menu at the caret |

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

## Media player
View-local keys of an audio or video tab, while the view has the focus; ignored while the file loads or failed
([media-player.md](media-player.md), [ADR 0123](../adr/0123-media-playback.md)).

| Keys | Action |
|---|---|
| Space | Play / pause |
| Left / Right | Back / ahead 10 seconds |
| Up / Down | Volume +10 % / −10 % |
| M | Mute / unmute |

## Read Aloud tab
View-local keys of the Read Aloud tab's text, while it has the focus ([ADR 0124](../adr/0124-read-aloud-speech.md)).
While a reading is preparing, speaking or paused the text is read-only.

| Keys | Action |
|---|---|
| Shortcut+Enter (text editable) | Read from the sentence at the caret, or only the selection if there is one |
| Space (while reading) | Pause / resume |
| Escape (while reading) | Stop; the text is editable again |
| Double-click on the text (while reading) | Jump: restart the reading at that sentence |

## Read Aloud in plain-text and Markdown editors
Editor-local keys of the text area of plain-text and Markdown editors, which have the Read Aloud controls themselves
([read-aloud.md](read-aloud.md), "In the editor"). They are the tab's keys; a handled key is consumed. While reading
with the focus in the text, Escape stops the reading; the find field's own Escape still closes the find bar.

| Keys | Action |
|---|---|
| Shortcut+Enter (text editable) | Read from the sentence at the caret, or only the selection if there is one |
| Space (while reading) | Pause / resume |
| Escape (while reading) | Stop; the editor is editable again |
| Double-click on the text (while reading) | Jump: restart the reading at that sentence |

## Translate tab
View-local keys of the language pickers' popup (Source, Target) of the Translate tab, while its search field has the
focus ([ADR 0126](../adr/0126-translate-tool.md)). Typing filters the list.

| Keys | Action |
|---|---|
| Enter | Pick the selected language (the first after typing; the current one when nothing was typed) |
| Up / Down | Move the selection |
| Escape | Close the popup without picking |

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
| Down in the arguments | Into the argument history (when it shows) |
| Up / Down in the argument history | Choose an entry |
| Enter in the argument history | Put the entry into the arguments |
| Escape in the argument history | Back to the arguments |

## Shell scripts
[shell-scripts.md](shell-scripts.md)

| Keys | Action |
|---|---|
| Shortcut+Enter in a shell script's source | Run the script |
| Enter in the script's arguments | Run the script |
| Down in the arguments | Into the argument history (when it shows) |
| Up / Down in the argument history | Choose an entry |
| Enter in the argument history | Put the entry into the arguments |
| Escape in the argument history | Back to the arguments |

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
| Shortcut+C | Copy the selected entries to the clipboard (files and their paths as text) |
| Shortcut+X | Cut the selected entries (dimmed until pasted) |
| Shortcut+V | Paste into the selected folder, the folder of the selected file, or the project folder: copies, or moves cut entries |
| Escape | Cancel a cut (when no drag runs) |
| Shortcut+Shift+D | Compare the two selected files side by side (exactly two files; otherwise View → Show Changes) |
| Drag entries onto a folder or the empty area | Move them (after a confirmation) |
| Option+drag (macOS) / Ctrl+drag (Windows, Linux) entries onto a folder or the empty area | Copy them instead of moving (after a confirmation) |
| Escape while dragging entries | Cancel the drag |
| Enter or Space on the collapsed explorer's stripe | Expand the explorer |

## Terminal
[terminal.md](terminal.md)

| Keys | Action |
|---|---|
| Shortcut+Shift+C | Copy the selection |
| Shortcut+Shift+V | Paste |

All other keys the terminal translates go to the shell. On Linux and Windows these include Ctrl+O, Ctrl+S, Ctrl+W,
Ctrl+K, Alt+1, Alt+9, Alt+6, Alt+7 and Alt+8, which then do not reach their menu items; Shortcut+Shift+letter, Ctrl+, and Ctrl+Alt+S still do, and
on macOS every Cmd shortcut does, so Cmd+W closes the terminal tab (asking first while programs run), and a tab
running a command only once it has exited ([terminal.md](terminal.md)). Alt+F1 (Option+F1 on macOS) goes to the shell on all platforms, so View → Select Opened
File does not react to it while a terminal has the focus.

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
| Shortcut+Enter in the Edit Commit Message dialog | Change the message (when it differs; Enter is a newline) |

## TODO panel
[todos.md](todos.md)

| Keys | Action |
|---|---|
| Alt+6 (Cmd+6 on macOS) | Expand or collapse the panel (View → TODOs; not while a terminal has the focus on Linux and Windows) |
| Enter or Space on the focused stripe | Expand the panel |
| Tab | Reach the buttons of the cards and folders and the Collapse button |
| Escape while dragging a card or folder | Cancel the drag |
| Enter in the delete question | Keep the TODO or folder (Cancel is the default button) |
| Enter in the dialog's Summary field | Add or save the TODO |
| Enter in the folder dialog's Name field | Add or save the folder |
| Escape in the dialog | Cancel it |
| The editing keys of code editors in the dialog's description field | As in the other editors of dialogs, see [Code editor](#code-editor) |

## Coloring tool
[coloring.md](coloring.md)

| Keys | Action |
|---|---|
| Escape in a color field (input, second color, gradient stop, mix) | Restore the text of the field's color and clear the invalid mark |
| Enter in a color field | Select all the text |

## Docker tab
[docker.md](docker.md)

| Keys | Action |
|---|---|
| F5 | Refresh: reload the engine's containers, images, volumes and networks |
| Shortcut+C in the log list | Copy the selected lines (select several with Shift- or Ctrl-click; also Copy in its context menu); there is no Shortcut+F, the Filter field narrows the lines |

## Databases tab
[databases.md](databases.md)

| Keys | Action |
|---|---|
| Up / Down on the connection cards | Select the previous / next connection |
| Enter on a connection card | Connect the selected connection (when it is not connected) |
| Delete or Shortcut+Backspace on a connection card | Delete the connection (asks; Cancel is the default button) |
| Escape while dragging a connection card | Cancel the drag |
| Shortcut+Enter in a console | Run the selection, the only statement, or choose in a popup: the statement at the caret or all statements |
| Enter / Escape in the statement chooser | Run the chosen entry / close |
| Shortcut+F2 in a console | Cancel the running statement |
| Shortcut+Alt+Shift+Enter in a console | Commit (Manual mode) |
| Shortcut+Alt+E in a console | Query history of the connection with a filter field; Down moves into the list, Up on the first entry returns to the filter, Enter inserts the entry (on a new line when the caret line has text), Escape closes |
| Ctrl+Space in a console | Completion (literal Ctrl on every platform; also opens by itself after a typed `.` or two letters); Up / Down choose, PageUp / PageDown page, Enter or Tab insert, Escape closes |
| Shortcut+C in a result grid | Copy the selected cells as tab-separated text |
| Shortcut+C in the Output list | Copy the selected lines |
| Enter or F2 in an editable result grid | Edit the cell; Enter keeps the value, Escape drops it |
| Shift+Enter in a result grid | Open the cell in the value viewer |
| Shortcut+Enter in an editable result grid | Submit the edits (also in an open cell editor, whose text is taken first) |
| Enter in the WHERE, ORDER BY or page size field | Reload the data with it |
| F5 in the object tree | Refresh the tree (expansion is kept) |
| Enter or double-click in the object tree | Open the data of a table or view; expand or collapse other nodes |

## Minesweeper
[minesweeper.md](minesweeper.md). No menu key; the keys work while the board has the focus, and not with Shortcut, Alt or Ctrl.

| Keys | Action |
|---|---|
| Arrow keys | Move the cursor cell |
| Space, Enter | Open the cursor cell, or chord it on an opened number |
| F | Flag or unflag the cursor cell |
| F2 | New game |
| P | Pause or resume |
| Left, right, middle mouse button | Open or chord, flag, chord |

## Word Guess
[word-guess.md](word-guess.md). No menu key; the keys work while the grid has the focus, and not with Shortcut, Ctrl or Meta. Letters are also typed with Alt/Option held (no Alt+letter binding exists, and some layouts type letters with it); Enter, Backspace and F2 act only without modifiers (Shift aside).

| Keys | Action |
|---|---|
| Letters (language alphabet; Ä, Ö, Ü in German) | Type into the active row (`KEY_TYPED`) |
| Backspace | Delete the last letter |
| Enter | Submit the row; after the round, the next word |
| F2 | Next word |

## Doubles
[doubles.md](doubles.md). No menu key; the keys work while the board has the focus, and not with Shortcut, Ctrl, Meta or Alt.

| Keys | Action |
|---|---|
| Arrow keys, W A S D | Move |
| U, Backspace | Undo the last move |
| F2 | New game |
| Mouse drag, trackpad swipe (macOS) | Move |

## Sudoku
[sudoku.md](sudoku.md). No menu key; the keys work while the board has the focus, and not with Ctrl, Meta or Alt (with Shortcut only Z and Shift+Z count).

| Keys | Action |
|---|---|
| Arrow keys, W A S D | Move the selection |
| 1 to N | Place a digit (a note in Notes mode) |
| Shift+digit | Toggle a note |
| 0, Backspace, Delete | Erase the digit, or the notes of a cell without one |
| N | Notes mode on or off |
| H | Hint (+30 s) |
| P | Pause or resume |
| F2 | New game |
| U, Shortcut+Z | Undo |
| Shift+U, Shortcut+Shift+Z | Redo |

## Settings
[settings.md](settings.md)

| Keys | Action |
|---|---|
| Enter in the font family or size field (Display → Fonts) | Take the typed value, without pressing OK |

## Mouse
| Input | Action |
|---|---|
| Middle-click on a tab (editor area, git log area) | Close the tab |
| Drag a tab header onto the other side's tab row, or onto the right half of the editor area | Move the tab to that side, opening the right side ([editor-tabs.md](editor-tabs.md)) |
| Drag a TODO card by its header | Reorder the TODOs, also into, out of and between folders ([todos.md](todos.md)) |
| Drag a TODO folder by its header | Reorder the folders among the TODOs ([todos.md](todos.md)) |
| Double-click on a TODO card's summary | Edit the TODO ([todos.md](todos.md)) |
| Double-click on a TODO folder's name | Edit the folder ([todos.md](todos.md)) |

## Pickers
[scratch-files.md](scratch-files.md), [project-picker.md](project-picker.md)

| Keys | Action |
|---|---|
| Up / Down in the scratch type picker | Choose the type |
| Enter / Escape in the scratch type picker | Create the scratch file / cancel |
| Up / Down in the generated scratch file picker | Choose the generator (the group headers are skipped) |
| Enter / Escape in the generated scratch file picker | Create the generated scratch file / cancel |
| Enter in the project picker | Open the project |
| Delete or Backspace in the project picker | Remove the project from the list |
| Escape in the project picker | Cancel |

## Keeping this page current
Whoever adds, changes or removes a key binding updates this page in the same change, as well as the feature page
that describes it. This applies to menu accelerators, `KeyCodeCombination`s and `KeyCharacterCombination`s,
`InputMap` entries, key handlers on `KeyCode`s and mouse bindings beyond plain clicks. Before adding a binding, check
here that it is not taken in the same context. The rule is in `.claude/rules/key-bindings.md`, and the reviewer
checks it.

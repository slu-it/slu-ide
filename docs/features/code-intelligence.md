# Code intelligence in the editors

**Module:** `modules/code-intelligence` (package `dev.sluide.codeintelligence`: `CodeIntelligence`, `InstallPrompts`;
the binding in `binding`, the banners and the restart item in `server`, the popups in `ui`, navigation in
`navigation`, completion and signature help in `completion` and `signature`, diagnostics, quick fixes and semantic
colours in `diagnostics`, `quickfix` and `semantic`, edits of other files in `edits`, rename in `rename`, file
structure and go to symbol in `symbols`); wired per window by
`ide/.../codeintelligence/CodeIntelligenceWiring`, the Navigate menu by `ide/.../codeintelligence/NavigateMenu`;
decisions in [ADR 0145](../adr/0145-code-intelligence-module.md), [ADR 0146](../adr/0146-navigation.md),
[ADR 0147](../adr/0147-completion-signature-help-and-docs.md),
[ADR 0148](../adr/0148-diagnostics-quick-fixes-semantic-highlighting.md) and
[ADR 0149](../adr/0149-rename-file-structure-go-to-symbol.md)

Code editors of files a language server serves get code intelligence from it: for now Python (`.py`, `.pyi`, `.pyw`)
through ty ([python.md](python.md)). Only files inside the window's project folder are served; a window without a
folder, scratch files and files outside the project get none. Highlighting and editing work as before in every state
below, with or without a server.

## With ty installed
- Opening a Python file opens it on the window's Python server, which starts with the first such editor (one server
  per window, ended when the window closes). Every edit (typing, undo, Replace All, reloads) and every save reaches the
  server as it happens, so what it answers is about the text in the editor, saved or not.
- Renaming a file to or from `.py` in the explorer connects or disconnects its editor; moving it within the project
  keeps it connected under the new name. Moving it out of the project disconnects it.
- The server hears about the project's files on disk as they change (ADR 0142); after a `pip install` or other changes
  outside the project, **Restart Python Language Server** makes it read them again.

## Banners
One at a time, above the text of a Python editor:
- **"Code intelligence for Python needs ty (14 MB download)."** with **Install**, **Not Now** and **Don't Ask Again**
  while ty is not installed. Install downloads it as a background task of the window (Settings → Languages → Python
  shows the same install). **Not Now** hides the question in this window until Thoreau restarts; **Don't Ask Again**
  hides it in every window for good (stored as `codeIntelligence.installDeclined` in `~/.sluide/shared.json`); Settings
  still installs.
- **While it installs:** a progress bar and **Cancel**.
- **"ty could not be installed: …"** with **Retry** and **Not Now** after a failed download.
- **"… is not an executable file. Change it in Settings → Languages → Python."** with **Not Now** only when the custom
  ty path does not work, and likewise "No ty build for this platform. Change it in …": installing again cannot help.
- **"The Python language server stopped: …"** with **Restart** when ty crashed after three restarts or could not start.
- **"The Python language server waits for this project to be trusted."** for a server that runs project code and
  waits for the trust question (ty does not).

Once ty is installed (from the banner, Settings, an update or a custom path), the servers of open Python editors start;
a server that ran restarts with the new ty.

## Project status line
A bar below the text of a bound editor says which project the file belongs to, when the language server's spec has
something to say (`workspaceStatus`) and only while the server runs: for Python `Python project:
deployables/backend_one · .venv · Python 3.12`, or `No Python project · default Python` (the texts:
[python.md](python.md)). It follows the file's project when `pyproject.toml`, `uv.lock` or the venv changes, and goes
away when the server stops or fails. A line with a project folder is a link: a click selects the folder in the explorer and
expands the column. The tooltip shows the absolute paths. `WorkspaceStatusFeature`; ADR 0151.

## Navigation
- **Go to definition:** **Shortcut+B** at the caret, **Navigate → Go to Definition**, **Go to Definition** in the
  context menu (at the right-click), or **Shortcut+click** on a name. Holding **Shortcut** over a name underlines it
  and shows the hand cursor, while the server runs. One declaration is jumped to: in the same file the caret moves
  there, another file opens at it, also outside the project (site-packages, ty's own copies of the standard library).
  The declaration's line is shown at the top of the editor unless it is already fully in view (also in a tab just
  opened, once it is laid out, and for Back and Forward); near the end of the file the scroll stops short.
  Several (overloads, `posixpath.py` and `ntpath.py`) are offered in a list at the name: `file.py:LINE`, the path
  (relative to the project, absolute outside it) and the line's text; **Enter** or a double-click jumps. None says
  "No declaration found" at the name until the next key or click. On macOS Ctrl+click still opens the context menu.
- **Find usages:** **Alt+F7** at the caret, **Navigate → Find Usages** or **Find Usages** in the context menu lists the
  usages of the name (without its declaration) at it: "Finding usages…" while the server answers, then those in the
  file first, then by path and line, with each line's text. One usage is jumped to; none says "No usages found".
- **Occurrences:** the caret resting on a name for 0.3 s highlights its occurrences in the file, a warmer background
  where it is assigned; moving off the name clears them.
- **Back and Forward:** **Navigate → Back** (Shortcut+Alt+Left) returns to where the last jump started, also in
  another file, and **Forward** (Shortcut+Alt+Right) goes the way back again. The window remembers 50 places each way;
  places in deleted files are skipped. A new jump forgets the way forward.
- When the server cannot answer, the keys show why at the caret ("Go to Definition is not available (server
  starting)"), the context menu items say it in their label, and the Navigate menu shows them disabled, as it does
  when no Python editor has the focus.

## Completion and documentation
Decision in [ADR 0147](../adr/0147-completion-signature-help-and-docs.md).
- **Completion** opens by itself after a typed `.` (and the other characters the server names; ty also `"` and `'`)
  and, 0.1 s after the last key, while typing a name (from its first letter, not in numbers); **Ctrl+Space** (Ctrl on
  macOS too) opens it at the caret, showing "Searching…" until the server answers. Undo, redo, paste and a selection
  never open it. While it shows, typing narrows the list at once, best matches first: names starting with what you
  typed, then those whose parts start with it (`gcw` finds `get_cwd`), then those containing its letters in order; at
  most 200. The server is asked again as you type, so the list fills in.
- Each row shows an icon for the kind (function, class, variable, module…; ty's attributes count as attributes), the
  name and, at the right, its type or signature, shortened. Beside the list: the selected item's signature and
  documentation.
- **Enter** inserts the selected name, **Tab** replaces the whole word under the caret with it. An import the server
  adds with it (ty's auto-import) comes in the same step: one **Shortcut+Z** undoes both. If you typed on after the
  list was asked for, the import is still added when it lies above the place you typed (it usually does).
- It closes on **Escape**, when the caret leaves the word (a space, another `.`), when nothing matches the server's
  latest answer, and when the editor loses the focus.
- **Signature help** shows above the line when you type `(` or `,` in a call, and on **Shortcut+P**: the parameters
  with the current one in bold, "2 of 3" for overloads, and the docs below. It follows as you type in the call and
  closes when the caret leaves it (the server knows no call there any more), on **Escape** and on focus loss. With
  the completion list shown too, the first Escape closes the signature, the next the completion; a completion still
  waiting for its first answer ends with the first Escape, so the answer opens no list.

## Diagnostics, quick fixes and semantic highlighting
Decision in [ADR 0148](../adr/0148-diagnostics-quick-fixes-semantic-highlighting.md).
- **Diagnostics** of the server show as you type (ty checks about 0.3 s after the last keystroke, and again after
  changes to other files): errors with a red wavy underline, warnings amber wavy, information blue dotted; unused or
  unreachable code faded, deprecated names struck through. An underline next to an edit disappears until the server's
  next answer. Each line with an error, warning or information has an icon in a narrow gutter column (the worst one;
  hovering it lists the messages). When the server stops (a crash, Restart), its diagnostics and semantic colours go
  until the new server answers.
- **Quick fixes:** **Alt+Enter**, or **Show Quick Fixes** in the context menu, lists the server's fixes for the caret
  or the selection at the caret (ty: add an import, ignore the rule for this line); preferred ones first, ones that
  cannot be applied now greyed with the reason. **Enter** applies the selected one as one undo step. A fix taken after
  the text changed is refused ("Ask for the quick fixes again"). The menu item is disabled "(server not running)" while
  the server does not run.
- **Edits in other files** (from a fix or a rename): an open editor gets them unsaved, as one undo step; a
  closed file in the project is changed on disk in one step, keeping its line breaks, its permissions and a BOM.
  Nothing changes when one of the files cannot be edited: it changed since the server made the edit or while the
  files were read, it is open with unsaved changes the server never saw (save it first), it is outside the project,
  read-only (also an open library tab that is locked, refused up front), library code while "Open library code
  read-only" is on ([ADR 0150](../adr/0150-library-code-read-only.md); a closed file of a virtual environment,
  `site-packages`, `__pypackages__`, the standard library or typeshed), not UTF-8 or larger than 10 MiB, or the edit
  does not fit its text. If a closed file cannot be written
  after the open editors were changed, the message names the files that changed. Creating, renaming or deleting files
  is not supported yet.
- **Semantic highlighting:** parameters, `self` and `cls`, and builtin constants (`__name__`, `NotImplemented`) get
  subtle colours of their own over the usual highlighting, in the dark and the light theme; they follow about 0.5 s
  after typing. It cannot be turned off yet.

## Rename
Decision in [ADR 0149](../adr/0149-rename-file-structure-go-to-symbol.md).
- **Shift+F6** at the caret, or **Rename…** in the context menu (at the right-click), opens a small field below the
  name with the name selected; it has the focus, so typing replaces the name there, not in the text. **Enter**
  renames everywhere the server finds the name; **Escape**, a click elsewhere or switching windows cancels. The
  editor gets the focus back either way. An empty or unchanged name does nothing. A name Python does not allow
  (`foo bar`, `1abc`, a keyword such as `class`) shows "Not a valid name: …" below the field, which stays open. While
  the field shows, the window's shortcuts (Shortcut+S, Shortcut+W…) do not act.
- Where the server says nothing can be renamed (a keyword, a builtin), "Cannot rename this" shows at the caret. The
  menu item says why it is disabled ("(server starting)", "(not supported)"), and Shift+F6 shows the same at the
  caret.
- A rename only in this file is applied at once: one **Shortcut+Z** undoes it. The caret stays in the renamed name.
- A rename that changes other files asks first: **"Rename “old” to “new”?"** with "N changes in M files" and the files
  with their number of changes, marking open files with unsaved changes and closed files that are written to disk
  (no undo takes those back). **Rename** applies it (open files are changed in their editors and left unsaved, closed
  ones are written, see "Edits in other files" above), **Cancel** changes
  nothing. When a file would refuse the rename (open without code intelligence with unsaved changes, closed and
  outside the project, closed library code, open but read-only, or missing on disk), the dialog says so and offers
  Cancel only.
- If the text of this or another open Python file changed while the server answered or the dialog showed, nothing
  changes ("The text changed. Rename again."). A rename that could not be applied, or only to some files, says so in a message naming the files.

## File structure and symbols
Decision in [ADR 0149](../adr/0149-rename-file-structure-go-to-symbol.md). Both open a search popup: a field with the
focus over a list. Typing filters or searches, **Up**/**Down** (wrapping) and **Page Up**/**Page Down** choose,
**Enter** or a double-click jumps there, **Escape**, a click elsewhere or switching windows closes it and gives the
focus back. While it shows, the window's shortcuts (Shortcut+S, Shortcut+W…) do not act. Jumps are remembered for
**Navigate → Back**.
- **File structure:** **Shortcut+F12** in a Python editor, or **Navigate → File Structure**, lists the file's classes,
  functions and variables at the top of the editor, nested ones indented, the one the caret is in selected. Typing
  filters by name as completion does (names starting with the text first, then parts, then letters in order).
- **Go to symbol:** **Shortcut+Alt+Shift+O** (Cmd+Option+Shift+O on macOS), or **Navigate → Go to Symbol…**, searches the
  symbols of the whole project at the top of the window: type a name, and 0.2 s later the list shows the server's
  matches (at most 200), exact names first, then names starting with it (the same case first, classes and functions
  before variables); each row shows the kind icon, the name, its
  class or module and its file. It works from anywhere in a window with a running Python server, also with the focus
  in the explorer; without one it says "Go to Symbol is not available (no language server running)". A symbol the
  server sends twice (ty does when a project folder lies inside the window folder) shows once.

## Hover
Resting the mouse on a character of a Python editor for half a second, or **F1** at the caret, shows a popup below it
(above when there is no room) with what the features know about that place, stacked; nothing shows while none of them
knows anything. The sections, top to bottom:
- **Diagnostics** at the place, each with its message, its source and code (e.g. "ty · unresolved-reference") and its
  documentation link as text.
- **The server's hover:** type and docs of the name as formatted text: code blocks in monospace, bold, italics, lists;
  links show as plain text.

Moving to another character, typing, scrolling, a click and **Escape** hide it; the mouse may move onto the popup.

## Popups at the caret
Lists that open at the caret (completion, choosers, usages, quick fixes) share one popup: the focus stays in the editor, **Up** and
**Down** choose (wrapping around), **Page Up**/**Page Down** and **Home**/**End** jump, **Enter** (and **Tab** where the
feature allows it) takes the item, **Escape** closes; a click selects, a double-click takes. It shows "Searching…" or
"No results" while it has no items, and hides on a click outside, when the editor scrolls or loses the focus, and when
the window moves or is minimized.

## Context menu
The editor's own items come first, then the features' in this order: **Go to Definition**, **Find Usages**,
**Rename…**, **Show Quick Fixes**, and last **Restart Python Language Server**, which stops the window's Python server
and starts it again. An item that cannot act now says why in its label or is disabled with the reason: "(server
starting)", "(server not running)", "(not supported)"; Restart: "(not started)", "(not installed)", "(project not
trusted)".

## Keys
All keys are listed in [keyboard-shortcuts.md](keyboard-shortcuts.md), "Code intelligence": **F1** and Shortcut+hover
(hover, go to definition link), **Shortcut+click** and **Shortcut+B** (go to definition), **Alt+F7** (find usages),
**Shortcut+Alt+Left**/**Right** (Back, Forward), **Ctrl+Space** (completion), **Shortcut+P** (signature help),
**Alt+Enter** (quick fixes), **Shift+F6** (rename), **Shortcut+F12** (file structure), **Shortcut+Alt+Shift+O** (go to
symbol), and the keys of the popups and fields (Up, Down, Page Up, Page Down, Home, End, Enter, Tab, Escape).

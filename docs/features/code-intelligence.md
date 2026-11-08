# Code intelligence in the editors

**Module:** `modules/code-intelligence` (package `dev.sluide.codeintelligence`: `CodeIntelligence`, `InstallPrompts`;
the binding in `binding`, the banners and the restart item in `server`, the popups in `ui`, navigation in
`navigation`); wired per window by `ide/.../codeintelligence/CodeIntelligenceWiring`, the Navigate menu by
`ide/.../codeintelligence/NavigateMenu`; decisions in [ADR 0145](../adr/0145-code-intelligence-module.md) and
[ADR 0146](../adr/0146-navigation.md)

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

## Context menu
**Restart Python Language Server**, the last item after the editor's own and the features' items (Go to Definition,
Find Usages; see Navigation), stops the window's Python server and starts it again.
Disabled with the reason when there is nothing to restart: "(not started)", "(not installed)", "(project not trusted)".

## Hover
Resting the mouse on a character of a Python editor for half a second, or **F1** at the caret, shows a popup below it
(above when there is no room) with what the features know about that place, stacked; nothing shows while none of them
knows anything. Moving to another character, typing, scrolling, a click and **Escape** hide it; the mouse may move onto
the popup. Which sections it shows (the server's hover with type and docs, the diagnostics there) comes with those
features.

## Popups at the caret
Lists that open at the caret (completion, choosers, usages) share one popup: the focus stays in the editor, **Up** and
**Down** choose (wrapping around), **Page Up**/**Page Down** and **Home**/**End** jump, **Enter** (and **Tab** where the
feature allows it) takes the item, **Escape** closes; a click selects, a double-click takes. It shows "Searching…" or
"No results" while it has no items, and hides on a click outside, when the editor scrolls or loses the focus, and when
the window moves or is minimized.

## Navigation
- **Go to definition:** **Shortcut+B** at the caret, **Navigate → Go to Definition**, **Go to Definition** in the
  context menu (at the right-click), or **Shortcut+click** on a name. Holding **Shortcut** over a name underlines it
  and shows the hand cursor, while the server runs. One declaration is jumped to: in the same file the caret moves
  there, another file opens at it, also outside the project (site-packages, ty's own copies of the standard library).
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
  the completion open too, the first Escape closes the signature, the next the completion.
- **Hover** (and F1) shows the server's type and docs for the name under the mouse, as formatted text: code blocks in
  monospace, bold, italics, lists; links show as plain text.

## Diagnostics, quick fixes and semantic highlighting
Decision in [ADR 0148](../adr/0148-diagnostics-quick-fixes-semantic-highlighting.md).
- **Diagnostics** of the server show as you type (ty checks about 0.3 s after the last keystroke, and again after
  changes to other files): errors with a red wavy underline, warnings amber wavy, information blue dotted; unused or
  unreachable code faded, deprecated names struck through. An underline next to an edit disappears until the server's
  next answer. Each line with an error, warning or information has an icon in a narrow gutter column (the worst one;
  hovering it lists the messages). When the server stops (a crash, Restart), its diagnostics and semantic colours go
  until the new server answers.
- **Hover:** the diagnostics at the place come first in the hover popup, each with its message, its source and code
  (e.g. "ty · unresolved-reference") and its documentation link as text.
- **Quick fixes:** **Alt+Enter**, or **Show Quick Fixes** in the context menu, lists the server's fixes for the caret
  or the selection at the caret (ty: add an import, ignore the rule for this line); preferred ones first, ones that
  cannot be applied now greyed with the reason. **Enter** applies the selected one as one undo step. A fix taken after
  the text changed is refused ("Ask for the quick fixes again"). The menu item is disabled "(server not running)" while
  the server does not run.
- **Edits in other files** (from a fix, and from rename later): an open editor gets them unsaved, as one undo step; a
  closed file in the project is changed on disk in one step, keeping its line breaks, its permissions and a BOM.
  Nothing changes when one of the files cannot be edited: it changed since the server made the edit or while the
  files were read, it is open with unsaved changes the server never saw (save it first), it is outside the project,
  read-only, not UTF-8 or larger than 10 MiB, or the edit does not fit its text. If a closed file cannot be written
  after the open editors were changed, the message names the files that changed. Creating, renaming or deleting files
  is not supported yet.
- **Semantic highlighting:** parameters, `self` and `cls`, and builtin constants (`__name__`, `NotImplemented`) get
  subtle colours of their own over the usual highlighting, in the dark and the light theme; they follow about 0.5 s
  after typing. It cannot be turned off yet.

## Keys
See [keyboard-shortcuts.md](keyboard-shortcuts.md), "Code intelligence".

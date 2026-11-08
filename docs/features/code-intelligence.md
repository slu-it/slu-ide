# Code intelligence in the editors

**Module:** `modules/code-intelligence` (package `dev.sluide.codeintelligence`: `CodeIntelligence`, `InstallPrompts`;
the binding in `binding`, the banners and the restart item in `server`, the popups in `ui`); wired per window by
`ide/.../codeintelligence/CodeIntelligenceWiring`; decision in
[ADR 0145](../adr/0145-code-intelligence-module.md)

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
- **"ty could not be installed: …"** with **Retry** and **Not Now** after a failed install (also a custom ty path that is
  not executable).
- **"The Python language server stopped: …"** with **Restart** when ty crashed after three restarts or could not start.
- **"The Python language server waits for this project to be trusted."** for a server that runs project code and
  waits for the trust question (ty does not).

Once ty is installed (from the banner, Settings, an update or a custom path), the servers of open Python editors start;
a server that ran restarts with the new ty.

## Context menu
**Restart Python Language Server** after the editor's own items stops the window's Python server and starts it again.
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

## Keys
See [keyboard-shortcuts.md](keyboard-shortcuts.md), "Code intelligence".

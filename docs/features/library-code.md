# Library code opens read-only

**Modules:** `library-code` (what counts: `LibraryCode`, the setting `LibraryCodeSettings`), `file-viewers` (the tab:
`LibraryCodeExtension`, `LibraryLockPolicy` in `dev.sluide.fileviewers.library`; the page in
`dev.sluide.fileviewers.settings`); decision in [ADR 0150](../adr/0150-library-code-read-only.md)

A dependency you installed is overwritten by the next install, so an edit there is lost. Files of Python library code
therefore open locked, however they are opened (Go to Definition, the explorer, Back and Forward, any open).

## What counts

Decided by the file's path alone (Python only; the rules are a list, so other ecosystems can be added later):

- inside a virtual environment: an ancestor folder with a `pyvenv.cfg` (`.venv`, `venv`, `.tox/*`, Poetry's);
- under a `site-packages` or `dist-packages` folder;
- under `__pypackages__`;
- the Python standard library (`lib/pythonX.Y`, also outside venvs);
- ty's typeshed stubs.

Not library code: a file whose library folder contains the project folder (the venv or the standard library opened as
a project on purpose), and editable installs, which point at project sources.

## The tab

The tab opens read-only (`ExtensibleCodeEditor.readOnlyLock()`, see [file-viewers.md](file-viewers.md)): typing,
paste, Replace, quick fixes and rename edits refuse and Save does nothing; copy, find and navigation work. A banner
says "`json/__init__.py` is library code (Python standard library): read-only, as changes are lost on the next
install." with **Edit Anyway**.

- **Edit Anyway** unlocks this view until it is closed (the views of a split are locked separately). The banner turns
  into "Editing library code (...): changes are lost on the next install." with **Dismiss**. Both buttons give the
  focus back to the text. There is no way to lock the view again. The banner shows the parent folder and the file name
  (`__init__.py` alone says nothing).
- A moved or renamed file is classified again; a file that stops being library code loses lock and banner.
- A tab with unsaved changes is never locked when the setting is turned on again: it stays unlocked like Edit Anyway,
  so that Save keeps working.
- While locked, the tab shows a lock icon before its name (tooltip "Library code, read-only"), bound to
  `FileView.readOnlyMarked` (`TabLockMarker.kt`, style class `tab-lock`, muted by opacity, so it follows both themes;
  it goes as soon as the flag turns false; [editor-tabs.md](editor-tabs.md)).

## Explorer and closed files

Folders that are library roots in the explorer tree (a folder with `pyvenv.cfg`, `__pypackages__`) get a lock folder
icon and a tooltip ([file-explorer.md](file-explorer.md)). Library files that are not open are never written by rename
or quick fixes, as files outside the project are not ([code-intelligence.md](code-intelligence.md)).

## Setting

Settings → Editor, "Open library code read-only" (`editor.libraryCodeReadOnly`, on by default; applies on OK or
Apply). Off: every open library tab unlocks and loses its banner (also one unlocked with Edit Anyway), and the icons
and the refusal go. On: they lock again, except views unlocked with Edit Anyway or with unsaved changes, which stay
unlocked and show the editing banner (unless it was dismissed before).
While a view is locked, the Convert to UTF-8 button of a Windows-1252 file is disabled (converting would make the view
dirty, which Save then refuses).

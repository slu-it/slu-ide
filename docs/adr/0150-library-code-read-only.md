# 0150. Library code opens read-only

- Status: accepted; see [0146](0146-navigation.md), [0148](0148-diagnostics-quick-fixes-semantic-highlighting.md),
  [0149](0149-rename-file-structure-go-to-symbol.md), [0144](0144-python-via-ty.md), [0008](0008-settings.md),
  [0047](0047-module-dependencies-and-shared-modules.md)
- Date: 2026-11-08

## Context
Go to Definition ([ADR 0146](0146-navigation.md)) opens the files a definition lies in, and for Python these are
often not the user's code: a package in the project's `.venv`, the standard library of the interpreter, or the
typeshed stubs ty keeps in its cache (`~/.cache/ty/vendored/typeshed/<hash>/stdlib/builtins.pyi`). They open as
ordinary editable tabs, so a stray key press changes an installed package, and the change is lost on the next install
or silently breaks the environment. The explorer shows a project's `.venv` like any folder. Rename and quick fixes
([ADR 0148](0148-diagnostics-quick-fixes-semantic-highlighting.md), [ADR 0149](0149-rename-file-structure-go-to-symbol.md))
can write closed files, so they could change library files too.

## Decision
- **What is library code (Python only):** decided by the file's path alone, however it is opened (Go to Definition,
  the explorer, Back/Forward, any `EditorArea.open`). A new foundation module `library-code`
  (`dev.sluide.librarycode`, depends on `settings`) holds the rules: `LibraryCode.locate(file, projectFolder)` gives
  the `LibraryLocation` (root folder and `LibraryKind`) or null. Rules, matched on the file's ancestor folders:
  1. **virtual environment:** the nearest ancestor holding a `pyvenv.cfg` file (`.venv`, `venv`, `.tox/*`, `.nox/*`,
     Poetry's and Pipenv's central venvs); one lookup on the disk per ancestor;
  2. **site-packages:** the nearest ancestor named `site-packages` or `dist-packages` (system, user site, conda,
     pyenv, uv and Homebrew interpreters, pipx);
  3. **`__pypackages__`** (PEP 582);
  4. **standard library:** a `pythonX.Y` folder (also `python3.13t`) in a `lib` or `lib64` folder (pyenv, uv,
     Homebrew frameworks, `/usr/lib/python3.12`), or a Windows-style `Lib` folder beside a `python.exe` (one lookup
     on the disk per `Lib` folder; the other rules only read names);
  5. **typeshed stubs:** a `typeshed` folder in a `vendored` folder (ty's cache, on macOS under
     `~/Library/Caches/ty`), or one whose next folder is `stdlib` or `stubs`.
  - **Precedence:** when several rules match, the first in this order wins, whatever the nesting: a venv's
    site-packages is "virtual environment .venv" (what the user recognises), a `site-packages` inside a standard
    library folder is "site-packages".
  - **Not library code:** a file of which one matching root is the project folder or contains it: the user opened the
    venv, site-packages or standard library as a project, or keeps the project inside a venv, and edits it on purpose.
    Editable installs point at project sources, which match no rule. Paths are made absolute and normalized; symbolic
    links are not followed.
  - Matches inside the project lock too: test data holding a `pyvenv.cfg` or a `lib/python3.12` folder, or a vendored
    `mypy/typeshed/stdlib`, opens read-only; Edit Anyway (or the setting) covers them.
  - The banner wording per rule is `LibraryLocation.description`: "virtual environment .venv", "site-packages" (or
    "dist-packages"), "__pypackages__", "Python standard library", "ty's typeshed stubs" ("typeshed stubs" outside a
    `vendored` folder).
- **Library tab:** opens locked: typing, paste, Replace, quick fixes and rename edits are refused, **Save does
  nothing**; copy, find, navigation and hover work (code intelligence stays as before: bound inside the project only).
  A banner says "`json/__init__.py` is library code (Python standard library): read-only, as changes are lost on the
  next install." with **Edit Anyway**, which unlocks the tab until it is closed; the banner then reads "Editing library
  code (…): changes are lost on the next install." with **Dismiss**. There is no Lock Again. A moved or renamed file
  is classified again.
- **Tab marker:** a lock icon before the tab's name while it is locked, tooltip "Library code, read-only"; gone after
  Edit Anyway.
- **Explorer:** folders inside the tree that are library roots (`LibraryCode.libraryRootKind`: a folder holding
  `pyvenv.cfg`, `__pypackages__`) get a library folder icon (Material `folder-lock` / `folder-lock-open`) and the
  tooltip "Library code (virtual environment): opens read-only"; folders inside them keep their normal icons.
- **Setting:** Settings → Editor, check box "Open library code read-only", on by default, with a short text listing
  the locations; stored as `editor.libraryCodeReadOnly` in `settings.properties` (`LibraryCodeSettings`, a
  singleton; the property on the FX thread, `currentReadOnly()` for other threads). It applies at once: off unlocks
  open library tabs and removes banners, lock icons and explorer icons; on locks them again, except tabs unlocked
  with Edit Anyway.
- **Closed library files** are never written by rename or quick fixes while the setting is on: refused like files
  outside the project ("… is library code"). Open library tabs already refuse the edit through their lock.
- **Out of scope** (`docs/ideas/`): other ecosystems (`node_modules`, Gradle and Maven caches, Cargo, Go; the rules
  are a list, so they can be added), code intelligence in library files outside the project (hover, further
  navigation in the standard library), remembering Edit Anyway beyond the tab.

## Consequences
- An accidental edit of an installed package or the standard library needs a deliberate Edit Anyway first; reading
  and navigating library code is unchanged.
- The decision is by path, so it costs no I/O beyond one `pyvenv.cfg` lookup per ancestor folder (and one
  `python.exe` lookup per `Lib` folder) and can run on any thread; layouts that use other folder names (a Python
  built with an unusual prefix, a vendored copy under another name) are not recognised.
- `library-code` is a foundation module that `file-viewers` (the tab lock and banner), `file-explorer` (the icons)
  and `code-intelligence` (the closed-file refusal) use; it knows nothing of them.
- Not checkable in the Linux sandbox: the macOS locations of ty's cache and of Homebrew framework Pythons; the rules
  cover them by folder names.

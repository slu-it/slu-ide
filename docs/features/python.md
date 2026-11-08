# Python: ty

**Module:** `modules/python` (package `dev.sluide.python`: `TySpec`, `TyInstaller`, `TyUpdates`, `PythonSettings`,
the pin `TyPin` and `TySmokeTest`; the page in the `dev.sluide.python.settings` package); decision in
[ADR 0144](../adr/0144-python-via-ty.md)

Python code intelligence comes from the language server [ty](https://github.com/astral-sh/ty) (Astral), run through
the language-server client of [ADR 0142](../adr/0142-language-server-foundation.md) for files ending in `.py`, `.pyi`
and `.pyw` inside the project folder. ty finds the project's virtual environment itself (`.venv` in the project
folder, `VIRTUAL_ENV`, Conda, else `python3` on the PATH) and runs no program of the project, so it needs no trust
question. Thoreau downloads it only on a click; the editor features are described in
[code-intelligence.md](code-intelligence.md).

## Settings → Languages → Python
- **Status line:** "Installed: ty 0.0.99", "Installed: ty 0.1.0 (this Thoreau was tested with 0.0.99)",
  "Not installed (14 MB download)", "Downloading 42 %", "Unpacking and checking ty…", "Failed: …" (the whole message in
  the tooltip), or "No ty build for this platform".
- **Install** downloads the version this Thoreau was tested with (the pin), checks its size and sha256, unpacks it into
  `~/.sluide/tools/ty/<version>/`, checks that it runs (`ty version`, then one `initialize` answer of `ty server`) and
  only then switches to it. While it runs the button reads **Cancel**. Disabled once ty is installed or when Thoreau has
  no build for the platform (Thoreau's builds: Linux x64 and aarch64, macOS on Apple silicon).
- **Check for Updates** asks GitHub once for ty's latest release (nothing is fetched in the background). The line below
  says "ty 0.0.99 is up to date.", "ty 0.1.2 is available, newer than tested with this Thoreau." with a **Release
  Notes** link (opens the system browser) and **Update**, "ty 0.1.2 is available, but not for this platform.", or why
  the check failed (no connection, GitHub's limit for requests without sign-in, another HTTP status). **Update**
  installs that version like Install, with the size and checksum GitHub lists (or switches back to it without a
  download if it is the kept previous version); the line clears once another version is installed.
- **Install <pin> (tested with this Thoreau)** appears when Thoreau's download is in use and the installed version is
  older than the one this Thoreau was tested with (after an update of Thoreau). It installs that version; the old one
  is kept for Roll Back. Nothing is installed without the click, and it does not appear for a custom path or when the
  installed version is newer (an update the user chose).
- **Roll Back to <version>** (only when a previous version is kept) switches back to the version before the last
  install. One previous version is kept; older ones are deleted after each switch.
- **Delete…** asks first (dialog "Delete ty": "Delete ty 0.1.2 and ty 0.0.99? The files in ~/.sluide/tools/ty are
  removed permanently…", Cancel is the default and focused), then deletes every downloaded version.
- These buttons act at once. An install, update, rollback or delete tells the language-server sessions
  (`ServerInstaller.onCommandChanged`), so running servers can restart with the new executable.
- **ty executable:** "Thoreau's download" or "Custom path" with a field and **Browse…**. A custom path must be the
  absolute path of an executable file; a hint below the field says when it is not ("Use an absolute path.", "… is
  not an executable file"). It is stored on OK or Apply (`python.ty.path` in `~/.sluide/settings.properties`, empty
  for Thoreau's download), wins over the download while it is set, and changing it tells the sessions as well. A custom ty that is not executable leaves Python without a
  server (the language server's state says why). A custom path that points into a project (such as `.venv/bin/ty`)
  runs in every window on open without a trust question and changes with that project's files: it is the user's choice.

## Files and network
- `~/.sluide/tools/ty/`: one folder per installed version, the pointers `current` and `previous` (ADR 0143).
- Hosts: `api.github.com` (Check for Updates), `github.com` and `release-assets.githubusercontent.com` (downloads).
- Pin, checksums and how to bump them: [external-artifacts.md](../external-artifacts.md), section "ty".

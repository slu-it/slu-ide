# 0143. Tool installs in local-models: versioned command-line tools from release archives

- Status: accepted; amends [0125](0125-local-models-module.md); see [0043](0043-git-log-fetch.md),
  [0060](0060-logging-conventions.md), [0061](0061-background-tasks.md), [0115](0115-converting-tool.md)
- Date: 2026-11-07

## Context
Python code intelligence runs the language server ty (Astral), a single native binary that GitHub releases ship per
platform as an archive (`ty-aarch64-apple-darwin.tar.gz` holding `ty-aarch64-apple-darwin/ty`, mode 0755, GNU tar
headers). Thoreau downloads its pinned version on an explicit Install click and later newer ones on an explicit Update;
an update can break, so the previous version must stay for a one-click rollback. The download itself is what
`local-models` already does for model files (part file, size and SHA-256 check, stall watchdog, cancellable task row,
ADR 0125), but a tool also needs unpacking, an executable bit, a check that it runs, and a switch between versions that
never leaves a half-installed tool in use. More tools (other language servers, formatters) will follow, so none of
this belongs to the Python module.

## Decision
- **`local-models` also installs tools.** Public API in `dev.sluide.localmodels`: `ArchiveFormat` (`TAR_GZ`, `ZIP`,
  `PLAIN`), `ToolRelease` (version, URL, bytes, sha256, format, the executable's `/`-separated path in the archive),
  `InstalledTool` (version, absolute executable path), `ToolInstallException`, and `ToolInstaller` (one per tool root,
  app-wide) with the JavaFX properties `state` (the existing `InstallState`), `current` and `previous`, and `install`,
  `cancel`, `rollback`, `deleteAll`, `close`. Nothing in it knows ty.
- **Layout under the root the caller gives** (ty: `~/.sluide/tools/ty`): one folder per version, `<root>/<version>/`,
  with the unpacked archive and a `tool.properties` (version, executable path), the marker that the folder is
  installed, written before the folder moves into place (an archive with its own top-level `tool.properties`, or an
  executable of that name, is refused); two pointer files `<root>/current` and `<root>/previous` holding a folder name. Every pointer and marker
  write goes to a temp file and `ATOMIC_MOVE`s over the old one (a copy of `writeAtomically`, ADR 0115). A pointer
  counts only when its folder exists, has its marker and the executable is a regular, executable file; so `current`
  survives a restart, and a pointer to a deleted folder reads as not installed. A version that already has a folder (a
  reinstall, a leftover) unpacks into `<version>~2` (`~` is not allowed in versions), so an install never replaces a
  folder a pointer may name.
- **Install steps**, on a virtual thread with a cancellable `BackgroundTasks` row that the caller names
  (`taskName(release, percent)`: the download's percent in 5 % steps, null while unpacking and checking; `state` is
  `Downloading(progress)`, then `Downloading(-1.0)`, the indeterminate value of `ProgressIndicator`):
  1. check the release values (version letters, digits and `._+-`, not `current`/`previous`; a 64-digit hex sha256;
     1 byte up to the size cap; an executable path inside the archive);
  2. download with `ModelDownloader` into a fresh `<root>/.install-*` folder;
  3. unpack into `.install-*/.content`, set the executable bits;
  4. run the caller's smoke test `(executable: Path) -> String?` on the executable there (null: works, else the
     reason);
  5. write the marker into `.content` and `ATOMIC_MOVE` it to the version folder, so every version folder is complete;
  6. write `previous` (the old current, unless that is the same version: a reinstall keeps the previous version), then
     `current`;
  7. prune: delete the marked version folders the pointers do not name (by the pointer text, so a named folder that
     does not read as installed for a moment stays), staging folders and pointer temp files left by crashes.

  Until step 6 a failure or a cancel leaves `current`, `previous` and their folders as they were and deletes the
  version folder and the staging folder; a failed `current` write puts `previous` back. A crash between the two pointer
  writes leaves both on the old current version (the rollback target is lost, the tool keeps working). A failed prune
  is logged and retried with the next switch. The smoke test runs on the worker thread and must end on its own (ty's
  caller gives it a timeout); a cancel takes effect when it returns. Messages say what failed: the download's own
  (`Download of <asset> failed: checksum mismatch`), `Cannot unpack <asset>: <reason>`,
  `Version <v> does not work: <reason>`. `state` stays Failed until the next action, and `current` keeps the version in
  use.
- **Rollback** swaps the pointers (`previous` first); a `previous` that names `current`'s folder counts as none.
  **Delete** removes the pointers first, then what prune would delete plus every version folder, then the root if it is
  empty; the caller asks the user before a delete. Prune and delete never touch a name an install does not create
  (marked version folders, `.install-*`, `.current-*.tmp`, `.previous-*.tmp`), so a wrong root loses nothing else. Both run off the FX thread; one action runs at a time.
- **Checksums and size come at runtime** with each `ToolRelease`: from a pinned resource for the version Thoreau was
  tested with, or from the GitHub API's asset `digest` for an update the user asked for. The module pins nothing.
- **Own tar reader** (`TarReader`, about 250 lines on `GZIPInputStream`) instead of Apache Commons Compress: tool
  archives need only ustar and GNU headers with regular files and directories, GNU long names (`L`) and PAX extended
  headers (`x`: `path`, `size`; `g` skipped). Everything else is refused with a readable reason: symbolic and hard
  links, devices, FIFOs, sparse and unknown types, a wrong header checksum, an archive that ends before its end marker,
  more than 8 extended headers before one entry or one over 64 KB. A dependency (and its CVE history) for a few
  hundred lines that refuse more than they accept was not worth it. zip goes through `java.util.zip.ZipFile` (the
  central directory, as unzip reads it); Java creates no links from zip entries.
- **Extraction rules:** an entry path that is absolute, has a `..` segment, a backslash, a NUL or a drive letter is
  refused, and the resolved path must still lie inside the target (a second check); `./` entries name the target
  itself; a path that comes twice is refused; files are created with `CREATE_NEW`, so nothing is overwritten (a file
  under a file fails as a clash). At most 512 MiB unpacked (also the cap of the download) and 10 000 entries, both
  constructor parameters; an entry whose declared size passes the cap is refused before a byte is written, and the
  decompressed tar stream is capped at the data cap plus 64 MiB of headers.
- **Executable bit:** the zip API drops Unix modes and `ModelDownloader`'s part files are owner-only, so the named
  executable always gets mode 0755 after unpacking, as do tar entries with any x bit. Without POSIX modes,
  `File.setExecutable(true)`.
- Logging (ADR 0060): the task's start and end lines, one INFO line per finished install with the version and the
  executable path, WARNING for a failure without a task row, never a URL (redirect URLs carry signatures).

## Consequences
- A language module installs, updates, rolls back and deletes its tool by building `ToolRelease`s; the unpacking
  rules, the layout and the crash behaviour are tested once, here (`TarReaderTest`, `ArchiveExtractorTest`,
  `ToolStoreTest`, `ToolInstallerTest`).
- The tool root is meant for its installer alone: what else is put there stays, but two installers on one root would
  delete each other's staging folders and versions.
- A reinstall of the current version ends in a `<version>~2` folder; the next switch prunes the old one.
- Tar features beyond the list (links in particular) make an archive fail with a clear message; a tool that needs
  them needs a change here.
- Added to [../index.md](../index.md).

# 0144. Python code intelligence through ty, pinned, installed and updated on click

- Status: accepted; amended by [0151](0151-workspace-folders-per-project.md) (project roots, `environment.python` per folder); see [0142](0142-language-server-foundation.md), [0143](0143-tool-installs.md),
  [0053](0053-project-trust.md), [0043](0043-git-log-fetch.md), [0008](0008-settings.md),
  [0067](0067-feature-settings-in-their-module.md), [0003](0003-javafx-platform-jars.md)
- Date: 2026-11-07

## Context
Python is the first language to get code intelligence through the language-server client of ADR 0142. Three servers
were tried on 2026-11-07 against the same project (`rich` with a `.venv`), driven by a small LSP client: definitions
across files and into the venv, references, completion with auto-import, hover, signature help, diagnostics, rename,
symbols, semantic tokens.

| | ty (Astral) | basedpyright | Pyrefly (Meta) |
|---|---|---|---|
| First cross-file answer | 35 ms | 235 ms | ~145 ms |
| Warm completion (pandas) | 17 ms | 75 ms | 5.5 ms |
| Memory on `rich` | ~295 MB | ~800 MB | ~225 MB |
| Install | one native binary, 28–38 MB | 118–155 MB unpacked, needs a Node runtime | one native binary, 29–35 MB |
| Runs | nothing (strace: no process started) | the venv's python | the venv's python |
| Maturity | beta, 0.0.x, 2–3 releases a week | mature (a fork of Pyright) | stable 1.x since May 2026 |
| Pitfalls | no formatting | noisy default diagnostics | references only from opened files; the mypy-config pitfall (`typeCheckingMode`); rejects a `null` configuration answer |

All three were equally precise on the definition tests, and none formats code.

## Decision
- **ty** is Python's server: same precision, the fastest first answer, a single small binary with a sha256 per
  release file, and the only one that runs nothing from the project (Pyrefly answers completions faster and uses less
  memory, but runs the venv's python and finds references only in opened files). The risk of a 0.0.x beta is met by
  pinning the version and updating only on the user's click.
- **Module `python`** (package `dev.sluide.python`, Spring beans), depending only on the foundations
  `language-servers`, `local-models`, `settings` and `background-tasks`:
  - `TySpec` (`LanguageServerSpec`): id `python`, name `Python`, files ending in `.py`, `.pyi`, `.pyw` (any case),
    language id `python`, command `ty server` with the inherited environment, no initialization options, an empty
    object for every `workspace/configuration` section, `runsProjectCode = false`.
  - `TyInstaller` (`ServerInstaller`): a `ToolInstaller` rooted at `~/.sluide/tools/ty`; the custom path of
    `PythonSettings` (`python.ty.path` in `settings.properties`) wins while it is set. Availability: a custom path is
    Ready if it is an absolute path of an executable file, else Failed ("… is not an executable file"); else an
    installed version is Ready (also while an update downloads or after one failed, since it keeps working); else the
    download's state (Installing with its progress, Failed), else Missing with the pinned download's size, or
    Failed "No ty build for this platform". `onCommandChanged` (added to `ServerInstaller` with a default that never
    calls) fires on the FX thread whenever the executable the command names changes: install, update, rollback,
    delete, a custom path set or cleared; the editor's code intelligence restarts its sessions on it.
  - `TyUpdates`: the update check. `PythonSettingsPage`: Settings → Languages → Python
    ([features/python.md](../features/python.md)).
- **Pin:** `modules/python/src/main/resources/dev/sluide/python/ty-release.properties` holds the version (0.0.99) and,
  per Thoreau platform, the asset name, its size and sha256, taken from the GitHub API and checked against each
  asset's `.sha256` file and a download. Install installs this version.
- **Platforms** (ADR 0003): linux-x64 → `ty-x86_64-unknown-linux-musl.tar.gz`, linux-aarch64 →
  `ty-aarch64-unknown-linux-musl.tar.gz` (the static musl builds run on any distribution), mac-aarch64 →
  `ty-aarch64-apple-darwin.tar.gz`. Other platforms (macOS on Intel, Windows) have no pin: Install is disabled, the
  availability says so, a custom path works.
- **Smoke test** before an install switches (ADR 0143): `ty version` (argument list, standard input closed, 10 s) must
  print `ty …` and exit 0; then `ty server` gets one `initialize` request and must answer it within 5 s (messages
  before the answer, such as log lines, progress or the server's own requests, are skipped). Its standard input stays
  open until the answer arrives or the time is up: ty ends when its input ends, sometimes before it wrote the answer
  (about 1 run in 25 when the input was closed right after the request). Both run in the executable's folder and are
  killed with their descendants afterwards.
- **Updates only on click** (network rule, ADR 0043): Check for Updates sends one `GET
  https://api.github.com/repos/astral-sh/ty/releases/latest` (`Accept: application/vnd.github+json`,
  `User-Agent: Thoreau`, no sign-in, 15 s, off the FX thread) and compares `tag_name` with the installed version (the
  pin when none is installed) numerically per dot segment. A newer release offers Update with its release-notes link
  and says when it is newer than the pin ("newer than tested with this Thoreau"); its asset for this platform is
  installed with the size and the `digest` (sha256) GitHub lists, and its download URL must be `https` on
  `github.com` below `/astral-sh/ty/releases/download/`. A leading `v` of the tag is dropped. An update to the version
  that is the kept previous one switches back to it without a download. Prereleases are not offered. GitHub's limit for requests without sign-in (HTTP 403/429 with
  `x-ratelimit-remaining: 0` or "rate limit" in the body) gets its own message.
- **Pin bumps reach existing installs on click:** when Thoreau's download is in use and the installed version is older
  than the pin (a newer Thoreau was installed), the page offers "Install <pin> (tested with this Thoreau)", which
  installs the pinned release (or switches back to it if it is the previous version); the old version stays for Roll
  Back. Never installed without the click; not offered for a custom path or when the installed version is newer than
  the pin (the user updated on purpose).
- **Rollback and delete:** the previous version stays for Roll Back; Delete asks first (Cancel default and focused),
  naming the versions and the folder.
- **Trust** (ADR 0053): ty runs no program of the project without a click (it reads `.venv`, `VIRTUAL_ENV`, Conda and
  finds `python3` on the PATH, but strace showed no process started), so it needs no `ProjectTrustCheck`. A custom
  path is the user's choice, not the project's: one that points into a project (such as `.venv/bin/ty`) runs in
  every window on open without a trust question and changes with that project's files.
- **Network hosts:** `api.github.com` (update check), `github.com`, which redirects the download to
  `release-assets.githubusercontent.com`.
- **Out of scope:** scripts known only by their shebang, files outside the project folder and scratch files (no server
  there, ADR 0142), formatting (ty has none).

## Consequences
- Python code intelligence costs a 13–15 MB download on the user's click and about 300 MB of memory per window with
  Python files open.
- ty can change behaviour between 0.0.x releases; Thoreau only moves when the user updates or the pin is bumped
  (skill `update-external-artifacts`, [external-artifacts.md](../external-artifacts.md)).
- The settings page opens release notes through `ide`'s `SystemBrowser`: `SluIdeApplication` sets
  `PythonSettingsPage.openWebPage` at start-up, since a settings page has no window services.
- The custom path's file is checked again (exists, executable) on every `command()` and, for the availability, each
  time the settings page loads.
- The real ty is tested in `TySmokeTestTest` only when one is installed (`~/.sluide/tools/ty`) or named in
  `SLUIDE_TEST_TY`; the linux-x64 and mac-aarch64 builds were not run in the sandbox (aarch64 Linux).
- Listed in [../index.md](../index.md).

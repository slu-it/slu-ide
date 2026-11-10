# 0151. Python monorepos: one workspace folder per project, status line and missing-venv notice

- Status: accepted; amended by [0160](0160-document-gate-and-kept-diagnostics.md) (the gate is asked with the
  workspace folder, watched folders per gate answer, the status line per file); amends
  [0142](0142-language-server-foundation.md) (several workspace folders per session, the
  spec's workspace hooks, folders watched outside the project), [0144](0144-python-via-ty.md) (project roots and
  `environment.python` per folder), [0141](0141-editor-api-for-language-features.md) (`addStatusItem`),
  [0145](0145-code-intelligence-module.md) (status line, environment notice with Run uv sync),
  [0149](0149-rename-file-structure-go-to-symbol.md) (Go to Symbol deduplicates); see
  [0150](0150-library-code-read-only.md), [0053](0053-project-trust.md), [0089](0089-running-shell-scripts.md),
  [0087](0087-commands-in-an-interactive-login-shell.md), [0084](0084-testability-logic-out-of-controls.md)
- Date: 2026-11-09

## Context
A monorepo (`root/deployables/backend_one|two|three`, each with its own `.venv`, Python version and dependencies)
opened in Thoreau resolved no third-party import, not even after `uv sync` in the sub-project. Thoreau gave ty exactly
one workspace folder, the window folder ([ADR 0142](0142-language-server-foundation.md)), and answered every
configuration request with `{}` ([ADR 0144](0144-python-via-ty.md)). ty looks for `.venv` only in a workspace folder,
so it fell back to the system Python.

Measured with the pinned ty 0.0.99 through a scratch LSP client (2026-11-09):
- One ty process with one workspace folder per backend: each resolves its own venv, and the per-project Python
  version applies (from `requires-python`). With nested folders (window folder plus project folders) the innermost
  wins.
- `workspace/didChangeWorkspaceFolders` works (ty declares `changeNotifications: true`); `workspace/configuration` is
  asked once per workspace folder (`section: "ty"`, `scopeUri` = the folder).
- A file outside every workspace folder gets no diagnostics, silently. With no folders at all ty takes its working
  directory as the workspace. So files without a project need a fallback folder.
- Adding or removing a folder makes ty forget the open documents of that folder: they must be opened again.
- A uv workspace member as folder does not find the workspace's `.venv`; the workspace root (with `uv.lock` and
  `.venv`) does.
- A venv not named `.venv` (e.g. `venv/`) is found when the folder's configuration answer is
  `{"configuration": {"environment": {"python": "<absolute venv path>"}}}`.
- Packages installed into a venv while ty runs are not seen; removing the folder, adding it again and opening its
  files again picks them up without a restart.
- Window folder plus a nested project folder: `workspace/symbol` returns each symbol twice; `references` has no
  duplicates.

## Decision
1. **Project root** of a Python file: walking up from its folder to the window folder (inclusive, never above), the
   nearest folder that holds `uv.lock` or a venv (a child folder with a `pyvenv.cfg` file: `.venv` first, then the
   others alphabetically). This covers uv, uv workspaces (members resolve to the workspace root), pip with a venv, and
   Poetry or PDM with in-project venvs. The venv of every project folder reaches ty through the folder's configuration
   answer (`environment.python`, answered by `scopeUri`), `.venv` too: otherwise an inherited `VIRTUAL_ENV` (Thoreau
   started from a shell with an activated venv) beats each project's `.venv` in ty's lookup order. Folders without a
   venv answer `{}` as before.
2. **Native files** (no project root) are served by the window folder, which is always the first workspace folder,
   with ty's own discovery of the default Python (`VIRTUAL_ENV`, Conda, `python3` on the `PATH`). A window folder that
   is itself a project root is simply that project, as before. Go to Symbol deduplicates its results (uri, range and
   name), since nested folders report symbols twice.
3. **Lazy folders** (`language-servers`, this package's foundation): when a document opens or moves, the session asks
   `LanguageServerSpec.workspaceFolder(file, projectFolder)` on its own folder worker (`sluide-lsp-<id>-folders`, one
   thread per session), never on the FX thread. A folder not yet known goes to the server with
   `workspace/didChangeWorkspaceFolders` (added) right before that document's `didOpen`: the `didOpen`, queued through
   `notify` on the FX thread as before, waits for the answer on the outbound thread, so requests made right after the
   open still reach the server after the `didOpen`. It waits at most `ServerTiming.workspaceFolderWait` (10 s); then
   the window folder serves the document, and when the answer comes after all, the document is opened again under
   its folder (added first if unknown); until the answer comes, `workspaceFolderOf` says the window folder, under
   which the document was opened. `null` (the default), a spec that throws and a folder outside the window folder
   mean the window folder. The
   set of folders is sent whole in `initialize` (window folder first), answered to `workspace/workspaceFolders` from a
   snapshot that never blocks, kept across restarts (Restart Python Language Server, crash restarts; documents are
   opened again as before), and reset when the window folder changes or the session closes. A document opened while
   the server starts adds its folder after `initialize`, with its reopen. `LanguageServerSession.workspaceFolderOf(file)`
   is the folder of an open document (FX thread; `null` for the window folder, before the answer and for files not
   open), `workspaceChanges` fires after a document's folder changed and after a refresh or re-resolution.
4. **Automatic refresh per project**, debounced 1 s per folder (`ServerTiming.workspaceDebounce`). A refresh removes
   the folder and adds it again (two notifications; ty then reads its venv again), then opens the open documents it
   serves again (`didClose`, `didOpen` with their current text), only those, and asks the spec again what to watch for
   it. The window folder is refreshed and watched like any other folder.
   - The folders `LanguageServerSpec.watchedFolders(folder)` names for a workspace folder (for Python its venv's
     `lib/python*/site-packages`, Windows `Lib/site-packages`) are watched non-recursively with the window's
     `FileWatcher`, also outside the project watch; a change there refreshes the folder. The watches end when the
     folder is removed, the window folder changes or the session closes.
   - Every change of the project watch (and of the callers of `filesChanged`), unfiltered by the server's registered
     watchers, reaches `LanguageServerSpec.affectsWorkspaceFolders(path)`, which decides by the path alone on the FX
     thread and may be broad (any folder): it only makes the session ask again, on the folder worker, the folders of
     the open documents below the change's parent and what to watch for the folders that serve them. Nothing goes to
     the server unless an answer changed: a document whose folder changed (the window folder and `null` count as the
     same) is opened again under the new one (added first if unknown); a folder whose `watchedFolders` answer differs
     from what is watched (a venv that appeared, went or was replaced) is refreshed; a project folder that no open
     document resolves to any more after this (its `uv.lock` and venv deleted) is removed
     (`didChangeWorkspaceFolders` removed; also while the server restarts, whose next start then does not get it),
     and the documents ty served from it are opened again under their new folder. The window folder is never
     removed, and folders whose documents were only closed stay (decision 8).
     Hidden and excluded folders (`.venv`, `venv`) that appear in or go from a watched folder are reported as entries
     of it, without being watched.
5. **Status line** at the bottom of a Python editor tab (a status item below the text, `addStatusItem` on the code
   editor in `file-viewers`: hidden while empty, inside the editor node so embedded editors show it too; amends
   [ADR 0141](0141-editor-api-for-language-features.md)), shown only while the file is bound and the server is
   Running: `Python project: deployables/backend_one · .venv · Python 3.12` (the path relative to the window folder,
   the window folder's own name when it is the project; the venv folder's name; major.minor from `pyvenv.cfg`
   `version_info`, else `version`, left out if neither); native files: `No Python project · default Python`. Clicking
   the project path selects the folder in the explorer; the tooltip has the absolute project and venv paths; it is not
   focus-traversable and the native text is not clickable. The text comes from
   `LanguageServerSpec.workspaceStatus(folder, projectFolder)` (`WorkspaceStatus`; `folder` null: not clickable).
6. **Missing-venv notice** (an editor banner like the server banners of
   [ADR 0145](0145-code-intelligence-module.md)): shown when the file's project root has no venv (a `uv.lock` alone),
   or the file is native and a folder with only a `pyproject.toml` (no `uv.lock`, no venv) lies above it inside the
   window folder (the nearest is named; after `uv sync` it is a project root): `No virtual environment for backend_one, so its packages cannot be resolved.` Buttons:
   **Run uv sync** runs `uv sync` in a terminal tab named `uv sync (backend_one)` with that folder as working
   directory; an explicit click needs no trust question ([ADR 0089](0089-running-shell-scripts.md)), the command
   is a constant and the folder only the working directory. **Not Now** hides it for this window and folder,
   **Don't Ask Again** for all projects (stored like the install prompts, id `python.environment`). It goes by itself
   once a venv appears (decision 4). The spec says it through `environmentProblem(file, projectFolder)`
   (`EnvironmentProblem` with an optional `ProblemAction`).
7. **Unchanged:** files outside the window folder are not served; library code is told as in
   [ADR 0150](0150-library-code-read-only.md); ty keeps `runsProjectCode = false` (Thoreau reads only names and
   `pyvenv.cfg`; ty with `environment.python` set is to be confirmed with strace to start no process).
8. **Out of scope** (to [language-servers-later.md](../ideas/language-servers-later.md)): venvs outside the project
   (Poetry and Hatch caches, `UV_PROJECT_ENVIRONMENT`, Conda environments per project), a per-project interpreter
   setting, removing folders whose files were all closed, serving files outside the window folder.

The hooks are defaults on `LanguageServerSpec` (`workspaceFolder`, `watchedFolders`, `affectsWorkspaceFolders`,
`workspaceStatus`, `environmentProblem`): a spec that keeps them behaves as before, with one folder, no watches and no
extra notifications.

## Consequences
- Each project of a monorepo resolves its own venv and Python version in one ty process; `uv sync` or `pip install`
  in a project venv is picked up within about a second after the burst ends, without a restart. On macOS the JDK
  polls ([ADR 0006](0006-file-watching.md)), so changes there arrive up to about 2 s later.
- Every document open waits for the spec's answer on the session's outbound thread: a slow spec delays what this
  session sends after the open, at most `workspaceFolderWait`; other sessions have their own folder worker and
  outbound thread. The answer is a few `stat` calls.
- A refresh opens the folder's documents again, so the server analyses them again; the debounce keeps that to one per
  burst. A re-resolution sends nothing when the answers stayed the same, so a broad `affectsWorkspaceFolders` costs
  only the spec's answers. Folders go only when a re-resolution leaves no open document in them.
- A folder watched outside the project costs one watch registration per venv (`site-packages` only, not recursive).
- Listed in [../index.md](../index.md).

# 0089. Running shell scripts

- Status: accepted
- Date: 2026-10-24

## Context
Shell scripts were plain highlighted source (`ShellFileViewer`). Running one meant a terminal tab, `cd` and typing its
path. The Gradle view already runs commands in an embedded terminal beside the source, with a status line, Stop,
Rerun, a background task row and a tab marker ([ADR 0085](0085-gradle-integration.md)), and that machinery is shared
in `command-runs` ([ADR 0088](0088-shared-command-runs.md)). Scripts should run the same way, from their view and from
the explorer.

Questions to settle: which files are scripts to run (a `.bashrc` is shell code, but not run by hand), how a run starts
(which interpreter, which folder), what happens with unsaved changes and with a script that is not executable, and
whether the project trust ([ADR 0053](0053-project-trust.md)) applies.

## Decision
- **Module `shell-scripts`** (package `dev.sluide.shellscripts`, flat, editor family) with `ShellScriptFileViewer`:
  `.sh`, `.bash`, `.zsh`, `.ksh` by extension (`ExtensionFileViewer`, so diffs by name are highlighted as shell), and
  extensionless text files whose shebang names `sh`, `bash`, `zsh`, `dash` or `ksh` (`ShebangViewer`). It is a
  `SourceFileViewer` and a `RunnableFileViewer`. `ShellFileViewer` keeps only the startup files (`.bashrc`,
  `.bash_profile`, `.zshrc`, `.zprofile`, `.profile`) and no shebangs, so the two viewers never claim the same file and
  the choice does not depend on Spring's bean order ([ADR 0029](0029-file-name-and-shebang-matching.md)).
- **The view** opens as Split since 2026-10-25 (user decision: scripts usually get arguments), not as plain source;
  Run (play icon) on the left of a toolbar and Source | Split | Output on the right.
  Output is a `RunPane` (arguments, status line with Stop and Rerun, the terminal). Run, Shortcut+Enter in the source,
  Enter in the arguments, Rerun, and `RunnableFileView.run()` all run the script and show the output beside the source
  (Output alone stays Output).
- **The command** is `unset -f cd 2>/dev/null; \cd '<folder>' && '<script>' 'arg'…`, every word quoted with
  `ShellWords`, in the user's interactive login shell like every command
  ([ADR 0087](0087-commands-in-an-interactive-login-shell.md)): the script runs **as a file**, so its shebang decides
  the interpreter, as in a terminal. The `cd` is part of the command, since startup files may change the folder. They
  may also define a function or alias named `cd` (zoxide): `unset -f` drops the function and the backslash skips the
  alias, so the builtin runs in bash, zsh and dash alike (`command cd` would look for a program `cd` in zsh).
  Arguments are split like a shell does but passed literally (no `$VAR`, `~` or
  globs); an unclosed quote is shown in the status line and nothing runs.
- **Before a run**, in this order (`ScriptRunFlow`, free of JavaFX, with a `ScriptRunHost`):
  1. a running run of the view is stopped only after asking ("deploy.sh is still running. Stop it and run deploy.sh?"),
     **before saving**: shells read a script while they run it, so saving over a running script would change what it
     runs next;
  2. unsaved changes ask "deploy.sh has unsaved changes." with Save and Run, Run Saved Version and Cancel; saving goes
     through the host (`FileViewHost.save`), as File → Save does;
  3. a script that is not executable asks "deploy.sh is not executable. Make it executable and run?"; OK adds only the
     owner's execute permission (`u+x`) and checks again, and says so if the file still cannot be executed (a
     `noexec` mount, a file system without permissions).
- **No project trust check**: running a script is an explicit action on that script by the user, unlike Gradle, which
  runs what the project's wrapper downloads as soon as the tasks load.
- **Explorer**: the window part `WindowScriptRuns` adds Run to the context menu of exactly one regular file whose viewer
  is a `RunnableFileViewer` (a `FileAction` after Open); it opens or selects the file's tab and calls
  `RunnableFileView.run()` there, with the same questions.
- **Arguments** are kept per open tab only, not stored.
- **Runs need a window**: the view gets the window's `CommandRunSession` through `FileViewHost.windowService`;
  `MainWindow` now registers it ([ADR 0070](0070-window-services.md)). Without it Run is disabled ("Scripts run in an
  IDE window").
- A moved script keeps its view, with its run and arguments, whenever the editor area lets it follow: when the same
  viewer opens the new path, or the tab has unsaved changes. Otherwise the editor area opens the new path anew.

Rejected:
- **Running the interpreter of the shebang** (`bash script.sh`): it would bypass `env` options, ignore a script that is
  not meant to run, and differ from what the same script does in a terminal.
- **Run configurations** like IntelliJ's (stored arguments, folder, environment): far more UI for little gain; the
  arguments field per tab covers the common case.
- **Gating runs by the project trust**: the trust protects against what opening a folder runs by itself, not against
  what the user starts by hand.

## Consequences
- What a script does is exactly what it does in a terminal tab, including what the login shell's startup files print.
- CRLF line endings make the kernel look for `bash\r` ("bad interpreter"); non-POSIX login shells (nushell, xonsh)
  cannot run the quoted command line; without a shebang the login shell decides how to run the file (fish refuses).
- Stop is Ctrl+C, then the terminal is closed after 5 seconds; children that ignore SIGHUP (`nohup`) keep running. With
  dash as login shell Ctrl+C does not end the command ([ADR 0087](0087-commands-in-an-interactive-login-shell.md)).
- The window tests name `ShellScriptFileViewer` among the beans; `MainWindow` takes the `CommandRunSession`.
- Listed in [../index.md](../index.md).

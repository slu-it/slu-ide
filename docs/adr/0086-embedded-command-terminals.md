# 0086. Embedded terminals that run one command per run

- Status: accepted; amended by [0087](0087-commands-in-an-interactive-login-shell.md) (commands run in an interactive
  login shell) and [0088](0088-shared-command-runs.md) (the run machinery is shared in `command-runs`)
- Date: 2026-10-24

## Context
Views of other modules need a terminal of their own, outside the editor tabs: the Gradle view runs tasks below its
task tree and shows a status line (running, finished, failed with the exit code) with Stop and Rerun. It must know
when a run ends and with which exit code, stop it as Ctrl+C would, and show the run's output.
[ADR 0007](0007-terminal.md) only covers terminals as editor tabs: an interactive shell, or a command in its own tab.

Options considered:
- **A persistent interactive shell per view, commands typed into it**: keeps the history of all runs, but a shell
  does not tell when a typed command has ended or how; prompt markers or shell integration would be needed for every
  shell, and a command typed while another runs is queued by the shell.
- **One emulator buffer reused across runs**, a new process writing into the old screen: keeps earlier output above,
  but mixes runs, and the emulator state (alternate screen, modes, colours) of a run leaks into the next.
- **A fresh process and terminal per run**.

## Decision
- `Terminals.embed(folder, command, environment)` returns an `EmbeddedTerminal`: a node that another view shows, the
  `exited` flag and `exitCode` of the process, `isAlive`, `interrupt()` and `close()`.
- Every run is a fresh process (`$SHELL -l -c '<command>'`, as command tabs; `-l -i -c` since
  [ADR 0087](0087-commands-in-an-interactive-login-shell.md)) in a fresh terminal; the caller closes the
  previous run's terminal and replaces its node, so the output is replaced per run. The output stays visible after
  the command ends, until the caller closes the terminal; until then it stays alive and follows the settings.
- The exit code is the process's own, read once it has ended; `exited` turns true on the FX thread after that.
  A terminal closed before its command ended reports no exit code (the hangup's is not the command's).
- `interrupt()` writes ^C (0x03) to the pseudo terminal, which sends SIGINT to the foreground job, as typing Ctrl+C
  does: Gradle cancels its build. `close()` ends the command like closing a tab, with the hangup (SIGHUP) of the
  closed pseudo terminal.
- Embedded terminals follow the font and scroll settings like tabs and are closed with the window's `Terminals`, but
  do not count as open tabs (`openCount`).
- Unlike a tab, an embedded terminal does **not take the focus when its node enters a scene**, only when it is clicked
  (or focused by its view): runs start while the user works elsewhere in the view, and a view shown again must keep
  the focus where its own `focus()` puts it.
- Amends [ADR 0007](0007-terminal.md): terminals can also be shown outside the editor tabs.

## Consequences
- Exit code, Stop and the status line are reliable without shell integration, for any user shell.
- Earlier runs' output is gone once the next run starts; a view that wants a history must keep it itself.
- A new run's terminal leaves the focus where it was (e.g. the task tree a run was started from); typing into the
  command needs a click into the terminal first.

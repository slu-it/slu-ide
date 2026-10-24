# 0087. Commands run in an interactive login shell

- Status: accepted
- Date: 2026-10-24

## Context
Thoreau runs commands in the user's shell: command tabs (Docker sandboxes, [ADR 0010](0010-docker-sandbox.md)),
embedded terminals (Gradle runs and task loads, [ADR 0086](0086-embedded-command-terminals.md),
[ADR 0085](0085-gradle-integration.md)) and the program checks behind them (`loginShellFinds`: `command -v sbx`,
`command -v gradle`). They ran `$SHELL -l -c '<command>'`: a login shell, but not an interactive one. zsh then reads
`.zshenv`, `.zprofile` and `.zlogin`, not `.zshrc`; bash reads its profile, as before. A terminal tab runs `$SHELL -l`
interactively, which also reads `.zshrc`.

Version managers set themselves up in `.zshrc` (SDKMAN's installer adds `sdkman-init.sh` there, so do nvm and
others). On macOS, with SDKMAN's Java, `gradlew` in the Gradle view found no `java` and macOS's `/usr/bin/java` stub
answered "Unable to locate a Java Runtime", while `java --version` worked in a terminal tab.

## Decision
- Every command runs as `$SHELL -l -i -c '<command>'` (`loginShellRunning`): an interactive login shell reads the same
  startup files as a terminal tab, so commands see the same `PATH`, `JAVA_HOME` and so on. This applies to command
  tabs, embedded terminals and `loginShellFinds`; terminal tabs stay `$SHELL -l`.
- Checked with bash and zsh in a pseudo terminal: the command's exit code comes through, and Ctrl+C ends the command
  and the shell (128 + SIGINT) as without `-i`.
- `loginShellFinds` starts its shell in a pseudo terminal of its own (pty4j, like terminals), not with
  `ProcessBuilder`: an interactive shell sets up job control on its controlling terminal, and in the IDE's session it
  would take over the terminal Thoreau was started from, or stop Thoreau with SIGTTIN when it runs in the background
  there. With a terminal of its own the shell leads a new session. Its output is read and dropped; a startup file
  that hangs or waits for input is stopped, with what it started, by the check's timeout.
- Rejected: capturing the interactive shell's environment once per window (`$SHELL -l -i -c env`) and starting
  commands with it: clean output and one slow start per window, but changes to the startup files only count in a new
  window, and parsing and failures add code. Rejected: a JAVA_HOME setting for Gradle: set by hand, and SDKMAN switches
  would not follow.

## Consequences
- Whatever the user's `.zshrc` prints (banners, warnings) shows in command tabs and embedded terminals above the
  command's output, as it does in a terminal tab; a slow `.zshrc` delays every command and check a little.
- Startup files that only suited terminal tabs now affect commands too: zsh without any startup file runs
  `zsh-newuser-install`, which waits for a key (the check then times out and finds nothing; a command waits in its
  terminal); a `.zshrc` or `.bashrc` that `exec`s another program when interactive (tmux, fish) loses the command.
  Guards such as `[[ -n $ZSH_EXECUTION_STRING ]]` or `[[ -n $BASH_EXECUTION_STRING ]]` keep such lines to terminal
  tabs.
- dash as the login shell, run interactively with `-c`, takes Ctrl+C as a return to its prompt instead of ending: the
  Gradle view's Stop then ends the run when its grace period is over, and the tests that send Ctrl+C use bash with a
  home of their own instead of `/bin/sh`.
- Amends [ADR 0010](0010-docker-sandbox.md), [ADR 0085](0085-gradle-integration.md) and
  [ADR 0086](0086-embedded-command-terminals.md).

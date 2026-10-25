# 0088. Shared run machinery for views that run commands

- Status: accepted
- Date: 2026-10-24

## Context
The Gradle view runs tasks (and the load of its task list) one at a time, each in a fresh embedded terminal
([ADR 0085](0085-gradle-integration.md), [ADR 0086](0086-embedded-command-terminals.md)): arguments, a status line
with Stop and Rerun, a question before a run stops the running one, Ctrl+C then a kill after a grace, a background
task row, the tab marker and a warning before the tab closes. All of it lived in the `gradle` module (`GradleRunner`,
`RunPane`, `GradleRuns`, `StopEscalation`, the texts in `RunTexts`, the stop thread in `GradleSession`, shell word
splitting and quoting in `GradleCommand`). A view of shell scripts is to run them the same way, and other views that
run one command per run may follow.

Options considered:
- **Into `terminal`**: it already embeds the terminals, but would then need `background-tasks` and `file-viewers`
  (the tab marker), the run state and a pane of controls, which tabs and other users of `terminal` do not need.
- **The shell-script view depends on `gradle`**: two unrelated features coupled through internals of one of them,
  which the module rules forbid ([ADR 0047](0047-module-dependencies-and-shared-modules.md)).
- **A small module of the editor family** that both use.

## Decision
- New module `command-runs` (package `dev.sluide.commandruns`, flat), part of the editor family; it depends on
  `terminal`, `background-tasks` and `file-viewers` (all `api`), only views that run commands depend on it.
- **`RunJob`**: what a runner runs. Its texts (status line while running and once ended, subject and action of the
  stop question, close warning) default to those of a run of named tasks; `backgroundName` has no default. A view's
  job type overrides what differs (Gradle: the load's texts). `stopQuestion(running, next)` combines two jobs.
- **`RunState`** (`Idle`, `Running`, `Finished`, `Failed` with the exit code, `Stopped`), `RunStatus` (the colour of
  the status line), `StartResult`, and the pure `elapsedText`, `statusOf` and `activityOf`.
- **`RunPane`**: arguments (labelled "Arguments:" since 2026-10-25), status line with Stop and Rerun, the terminal
  slot with a placeholder; the arguments' example prompt and the placeholder are given by the view. Its own stylesheet (`run-pane.css`, classes `run-*`, dark rules as in
  [ADR 0009](0009-themes.md)).
- **`CommandRunner<J : RunJob>`**: one run at a time per view, each a fresh process in a fresh embedded terminal in
  the pane. `replaceRunning(next)` asks before stopping a running job and closes its terminal at once (it counts as
  stopped); `start` calls it first. `last` is the last started job that is rerunnable (Gradle: tasks, not the load).
  Stop is Ctrl+C, then a kill after the grace (5 s). The internal state machine (`Runs`) and the escalation
  (`StopEscalation`) stay free of JavaFX ([ADR 0084](0084-testability-logic-out-of-controls.md)).
- **`CommandRunSession`**: a window bean (`@Scope("window")`) with the window's `Terminals` and `BackgroundTasks` and
  the thread that fires the delayed kills (`Command stop`), shut down when the window closes. `GradleSession` takes it
  instead of the terminals and background tasks.
- **`ShellWords`**: splitting user arguments like a POSIX shell and quoting words, moved from `GradleCommand`.
- Amends [ADR 0085](0085-gradle-integration.md) and [ADR 0086](0086-embedded-command-terminals.md): the Gradle view's
  runs, texts and stop thread now come from `command-runs`; what the user sees is unchanged.
- Amended by [ADR 0091](0091-argument-history-of-runs.md): the `RunPane` shows the history of past arguments below the
  field, `CommandRunner.start` records the arguments of started runs, and `CommandRunSession` hands the views the
  `ArgumentHistories`.

## Consequences
- A new view that runs commands needs a `RunJob` type, a `RunPane` and a `CommandRunner`; it gets the question before
  stopping, Stop, Rerun, status, background row, tab marker and close warning the same way as Gradle.
- The default texts are Gradle's task texts; a view that names its runs differently overrides them in its job type.
- One more module and window bean; the bean lists of the window tests name `CommandRunSession`.

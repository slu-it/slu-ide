# 0054. Keep source files small: split by responsibility

- Status: accepted
- Date: 2026-10-18

## Context
Two classes had grown to about 2000 lines each: `GitLogView` (2248) and `GitCli` (1980). Large classes mix
responsibilities, and they cost every agent session context: agents read them again and again, mostly in ranges, yet
whole. Measured over 194 session transcripts, these two files made up a third of all Kotlin lines read (18 % and
15 %). The next ones were far smaller (`PreviewFileViewer` 5 %, `EditorArea` 3 %).
The "Grep, then read a range" rule in `CLAUDE.md` eases the cost but does not remove it: almost every git task goes
through both files.

## Decision
- **Soft limit.** Production files aim for under about 500 lines. A file over 600 lines is split by responsibility the
  next time it is changed substantially. `.claude/scripts/check-file-sizes.sh` lists the candidates. It only reports:
  it is not a build failure, because a forced split can read worse than a long, cohesive file. Long test classes
  with many cases of one subject are fine.
- **Split a class into files of one responsibility each, without changing behaviour or callers.** One commit per
  extracted part, verbatim where possible.
- **`GitCli`: a process core plus read files by area.**
  - **The core stays in `GitCli.kt`:** the environment ([ADR 0052](0052-hardening-how-git-is-started.md)),
    `available` ([ADR 0013](0013-git-status.md)), the fsmonitor guard, `run`/`runBytes`/`execute`, and the writes
    ([ADR 0041](0041-git-write-operations.md)).
  - **The typed reads become `internal` extension functions of `GitCli`:** `GitCliStatus.kt`, `GitCliLog.kt`,
    `GitCliObjects.kt`, `GitCliRefs.kt`, `GitCliConfig.kt` and `GitCliIndex.kt`.
  - **Package-level helpers:** `GitEnvironment.kt`, `GitProcess.kt`, `GitArguments.kt` and `GitWriteOutput.kt`.
  - **Callers and tests keep calling `git.xxx(...)`.**
  - Rejected: separate reader classes (`GitLogReads(cli)`, …), which would change every wrapper's constructor, the
    Spring wiring and the tests for no gain in behaviour.
- **`GitLogView`: a coordinator plus components.**
  - **The view keeps construction, layout, wiring and the lifecycle,** and stays the only window-scoped bean with an
    unchanged API.
  - **Its parts become `internal` classes in `dev.sluide.gitlog`:**
    `HeaderStatus`, `AreaTabs`, `CommitTable`, `CommitNavigation`, `RefActions`, `CompareBar`, `PendingBar`,
    `CommitDiffs` and `FilterSources`.
  - **They follow the package idiom:**
    - each class is the JavaFX node, or owns it;
    - dependencies, queries and fixed collaborators (e.g. `HeaderStatus`'s `tell`) come in through the constructor;
    - actions that go out to the view's own callbacks, which `MainWindow` may set later, are `var` callbacks that
      the view sets and forwards (`{ onShowDiff(it) }`);
    - nodes the tests need are `internal val`.
  - **Tests reach them through the view's member** (`view.compareBar.headOnlyToggle`), not through forwarding
    properties.

## Consequences
- An agent working on, say, the log query reads `GitCliLog.kt` (about 200 lines) instead of ranges of a 2000-line
  file. `GitCli`'s ADRs keep their names (`GitCli.environment`, `GitCli.write`, `GitCli.configEntries`).
- `run`, `runBytes`, `execute` and `Execution` are `internal` instead of `private`, so the read files can use them.
- Open: the result types `GitCli` returns still live in its wrappers' files (`GitResult` in `GitBranches.kt`,
  `GitInProgress` in `GitCommits.kt`, …); moving them next to the core would make the layering clean.
- Next candidates, by size and read volume: `GitCommits.kt`, `RSyntaxHighlighter.kt`, `MainWindow.kt`,
  `PreviewFileViewer.kt`, `FilterBar.kt`, `RefOperations.kt`, `TerminalView.kt`.
- Added to [../index.md](../index.md).

# 0082. Split the UI tests of long modules into two tasks

- Status: accepted
- Date: 2026-10-24

## Context
Since [ADR 0081](0081-parallel-ui-tests-on-a-display-pool.md) every module's `uiTest` runs on a display of its own,
and the full build took 3:48 minutes. Most of that was one task: git-log's 190 UI tests, about 210 s in one JVM, while
the other modules were long done. Parallel forks inside the task are not possible, because all forks of a task share
its display. Two tasks can each lease a display of their own.

## Decision
- **A module can be split in two by a class name pattern.** The root build's `uiTestSplits` maps a module to a
  pattern. Such a module gets `uiTestPart1` with the classes that match it (`includeTestsMatching`) and `uiTestPart2`
  with all others (`excludeTestsMatching`). `check` depends on the two parts instead of `uiTest`.
- **The rest goes to part 2.** A new test class that matches no pattern still runs, in part 2; a pattern that stops
  matching makes part 1 fail with "No tests found" instead of passing silently.
- **`uiTest` stays.** It still runs all of a module's UI tests, so that `--tests` works for every class as before.
  Naming it next to `build` (`./gradlew build uiTest`) therefore runs a split module's UI tests twice.
- **`-x uiTest` still means no UI tests.** The macOS jobs of CI and release build with `-x uiTest`, which removes only
  tasks of that name; when it (or `<module>:uiTest`) is excluded, `check` leaves out the parts as well. A later split
  needs no change to the workflows.
- **git-log splits at `GitLogView*`.** The topic classes already split there: the view tests (9 classes, about 107 s)
  and the writes, push, commit and compare tests (8 classes, about 96 s). The two parts run in about 113 s together.
- **full-build.sh starts 8 displays instead of 6.** With the extra task, 6 displays made previews' UI tests (168 s,
  now the longest) wait 70 s for a free one, and the build took 4:06. Full build with all tests rerun: 6 displays
  4:06, 7 displays 3:47, 8 displays 3:18, using at most 9.3 GB of 11 GB. Capping the test JVMs' heap at 1 GB did not
  lower that peak, so it is not Java heap.
- **previews splits at its `preview` package** (amended 2026-10-24, after a test audit had cut duplicated and slow
  UI tests): `dev.sluide.previews.preview.*` (the shared view, zoom, table of contents and export classes, about
  67 s) and the format packages (about 68 s). Full build with all tests rerun: 2:51 unsplit, 2:10 to 2:17
  split (two runs), at most 8.9 GB in use.

## Consequences
- The full build takes 3:18 instead of 3:48 minutes. The floor is now previews' UI tests (about 170 s), the next
  candidate for a split. With the previews split and the test audit, about 2:15; the floor is git-log's `uiTestPart1`
  (about 97 s) and the 8 displays.
- About 1.5 GB of memory stay free on the 11 GB sandbox; a smaller machine sets `SLUIDE_UI_DISPLAYS` lower.
- Part 2 grows with every new non-view class of git-log; when the parts drift far apart, the pattern moves.
- On CI's one display the parts run one after the other, as `uiTest` did, so CI time is unchanged.
- More load on the machine made a test-thread race visible: `EditorAreaFileChangesUiTest` read the watch count off
  the FX thread while a late watch event renewed a watch. Such reads belong in `robot.interact`.

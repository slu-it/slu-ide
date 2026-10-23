# UI tests on the headless JavaFX platform

Status: explored 2026-10-23, not adopted.

## Why
Since ADR 0081 the modules' `uiTest` tasks run in parallel, each on its own Xvfb display, and the full build takes
3:48 minutes. Now the longest module sets the floor: git-log's UI tests, about 3.5 minutes in one JVM. Running a
module's tests in parallel forks (`maxParallelForks`) would cut that, but all forks of a task share one `DISPLAY` and
would steal each other's focus again. JavaFX 25+ ships a headless glass platform (`-Dglass.platform=Headless`,
`com.sun.glass.ui.headless`) that needs no display at all, so forks would be independent.

## What was tried
A throwaway Gradle init script, no change to the build: in `uiTest` the system properties `glass.platform=Headless`
and `testfx.robot=glass` (TestFX's default AWT robot needs a display; `testfx.headless` would mean Monocle, which is
not wanted), run without `DISPLAY` and with `CI=1` so that `uiTest` is not skipped. JavaFX 27, TestFX 4.0.18.

## Results
- **911 of 988 UI tests pass unchanged.** background-tasks, docker-sandbox, editor-area, file-explorer, projects,
  scratches, settings and terminal pass completely, including drag and drop, context menus and popups.
- **Per test the platform is not faster.** Module times are within about 10 % of those on Xvfb (git-log 220 s, editor-
  area 73 s, file-explorer 98 s). The flat cost of about 0.7 s per test does not come from the X server.
- **Parallel forks work.** git-log with `maxParallelForks = 3`: 112 s instead of 238 s, with the same results as
  one fork.
- **77 failures, three causes:**
  - *The headless screen is a fixed 1000×1000* (`HeadlessApplication`, no property to change it). A window larger
    than that fails in `HeadlessWindow.blit`/`clearRect` with `IndexOutOfBoundsException`. TestFX reuses one stage
    per JVM, so after the first oversized window every later test of that JVM fails in its setup. That is 66
    failures, all in previews (YAML, XML, workflow, CSV, zoom, WebView parser).
  - *`getKeyCodeForChar` is not implemented* ("Not supported yet"). Shortcuts matched by character fail: toggle
    comment (`Shortcut+/`, `Shortcut+Shift+7`), zoom with `Shortcut++`. 10 failures in file-viewers, http-client,
    previews and ide; Thoreau's own key handling needs this, so the tests cannot simply avoid it.
  - *Focus differs.* `GitLogPushUiTest` "the dialog reads again when it gains the focus" fails: focus changes do not
    arrive as on a real window system.

## Recommendation
Not now. Making the suite pass headless means shrinking test windows below 1000×1000 in previews, excluding the
character-shortcut tests on that platform (they would then be verified only on Xvfb/CI), and handling focus tests.
Local runs would then differ from CI, which is exactly what "never claim UI was verified without running it" guards
against. The gain is about 1.5 minutes off a 3:48 build. Worth revisiting when a JavaFX release makes the screen size
configurable and implements `getKeyCodeForChar`. A cheaper alternative for the floor is splitting git-log's UI tests
across two Gradle tasks, so that two displays can serve them.

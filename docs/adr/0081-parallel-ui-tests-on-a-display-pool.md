# 0081. Parallel UI tests on a pool of displays

- Status: accepted
- Date: 2026-10-23

## Context
The local full build (`.claude/scripts/full-build.sh`) took 14 minutes, nearly all of it UI tests. The 988 TestFX
tests add up to about 810 s (git-log 214 s, previews 171 s, file-explorer 105 s, then editor-area, file-viewers,
ide, http-client and a few small ones), while all unit tests together take about 70 s and already run in parallel.
The modules' `uiTest` tasks ran strictly one at a time through a build service with one usage: tests of parallel
modules on the one Xvfb display took each other's window focus, which closed their popups (auto-hide follows the owner
window's focus) and failed tests at random. Focus is per display, so the conflict is the shared display, not
parallelism as such. CI time was acceptable and is not the goal.

## Decision
- **One display per running `uiTest` task.** The root build's `DisplayService` holds a pool of displays and allows as
  many `uiTest` tasks at once as it has displays. A task leases a free display in `doFirst` and sets `DISPLAY` for its
  test JVM; the display goes back to the pool when the task finishes, failed or not, through an
  `OperationCompletionListener` registered with `BuildEventsListenerRegistry` (a `doLast` is skipped when tests fail).
  The finish event can arrive after Gradle already started the next task, so a lease waits up to a minute for a free
  display instead of failing. An empty `-Psluide.uiDisplays=` counts as unset.
- **The pool comes from `-Psluide.uiDisplays=:99,:100,…`.** Without it the pool is the one display in `DISPLAY` (or none
  on macOS, where the environment stays untouched), so CI (`xvfb-run -a ./gradlew build`), plain `./gradlew build` and
  single test classes behave as before: one task at a time.
- **full-build.sh starts the pool.** On Linux it runs `setup-display.sh` for `:99` upwards, `SLUIDE_UI_DISPLAYS`
  displays (default 6), and passes them to Gradle. `DISPLAY=:99` stays exported for everything else.
- **Default of 6 by measurement.** Full build with all tests rerun (`--rerun-tasks`), 12 cores, 11 GB: one display
  14:02 min, 3 displays 5:18, 4 displays 4:42, 6 displays 3:48 with at most 7.7 GB in use. Six is close to the floor,
  the longest module (git-log, about 3.5 minutes); more displays would only cost memory. A machine with less memory
  sets `SLUIDE_UI_DISPLAYS` lower.

## Consequences
- The full build takes under 4 instead of 14 minutes; the Gradle configuration cache still applies.
- Modules no longer share a display, but each module's own tests still run in one JVM on one display, as before.
- The longest module now sets the build time. Going below it needs parallel forks inside a module, which a
  display per task cannot give: Gradle forks a task's test JVMs with the same environment, and `DISPLAY` is read once
  per JVM. The headless JavaFX platform would allow that, but is not ready for this suite yet
  ([../ideas/headless-ui-tests.md](../ideas/headless-ui-tests.md)).
- More displays cost memory: each one runs a JavaFX JVM with software rendering next to the unit test JVMs.

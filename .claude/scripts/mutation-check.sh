#!/usr/bin/env bash
# Mutation run with pitest (ADR 0185, replaces break-check.sh): mutates the production Kotlin that the commits since
# <base> add (or, with --whole, every line of the given files), runs the tests and lists every mutant no test killed.
# A driver around the pitest Gradle wiring of the root build.gradle.kts (`./gradlew :<module>:pitest
# -Psluide.pitest.*`) and the plugin tools/pitest-kotlin (KOTLIN_JUNK drops bytecode the Kotlin compiler adds, LINES
# keeps the mutants on an allow-list of lines). pitest never edits a source file: nothing is restored after a kill.
# Change mode (default): the lines `git diff -M <base>...HEAD` adds in */src/main/kotlin/**/*.kt (moved files count
# only their changed lines). An added line that starts with `?:` or `?.` also allows the line before it, where Kotlin
# puts that null check. --whole: every line of the given files (the backfill; <base> then only has to be an ancestor).
# Mutators, fixed in build.gradle.kts: DEFAULTS, REMOVE_CONDITIONALS_EQUAL_IF/ELSE, NON_VOID_METHOD_CALLS. Files in
# :tools have no pitest wiring and are skipped.
# Per module (one at a time, in order), always with LINES: targetClasses are the packages of the files (never class
# names: a file holds classes named otherwise, FooKt facades, lambdas and $$inlined$ classes), the allow-list holds
# repo-root entries `modules/git/src/main/kotlin/dev/sluide/git/GitPush.kt:53`.
#   1. Unit stage: one Gradle call, the module's unit tests (the build's default targetTests, no @Tag("ui")).
#   2. UI stage, unless --unit-only (and Linux only: it needs flock and a display per worker; elsewhere it is skipped
#      with a note and the summary says "unit tests only; preliminary"): every file that still has SURVIVED or
#      NO_COVERAGE mutants. A second call (-Psluide.pitest.ui=true, no tests property) runs all the module's tests,
#      unit and UI, with every given display leased per worker by pitest-java.sh (-Psluide.pitest.displays);
#      pitest's per-test coverage picks the tests of each mutant. targetClasses are the classes that hold the
#      remaining mutants (never packages: a relocated copy sits in a class of another package), LINES the remaining
#      lines. A mutant keeps its unit-stage status unless the UI stage KILLED it; the stages' mutants are matched by
#      class, method, descriptor, mutator and indexes, not by line. The UI stage's "n mutants" are the ones the unit
#      stage left. It costs about one run of the module's UI tests plus seconds per mutant (a kill by a test that
#      waits for its deadline costs that wait). --ui-tests A,B limits the call to those test classes (simple names,
#      found in the module's src/test/kotlin; a module that declares none of them runs all its tests): on ide's 21
#      mutants 765 s became 413 s on one display and 185 s on four (ADR 0189). A kill by a class that is not named
#      shows up as a survivor.
# Accepted mutants (ADR 0189): --accept <path>:<line>[:<Mutator>] <reason> appends the mutants the last finished run
# in this tree left on that line to build/mutation-check/accepted.tsv, one entry per mutant (path, class, method,
# mutator, description, the line's text, reason; the run's leftovers are in left.tsv, which a new run deletes first).
# A line whose leftovers have several mutators needs the mutator named: they rarely share a reason. A later run
# reports a mutant left that matches an entry, on a line that still has the entry's text, as
# `accepted  path:line  <Mutator>: <description>  (<reason>)`: it is not left (exit code, UI stage) and the summary
# counts it as ", A accepted". An entry accepts one mutant; a second one like it stays left. Only an equivalence or
# an endless-loop timeout, with its reason; the reviewer checks the file. Delete a line of the file to take an
# acceptance back. Refused (exit 2) while a run is active in the tree.
# Gradle runs on the shared daemon (--no-daemon in a worktree). Each call's output and a copy of its mutations.xml:
# build/mutation-check/logs/<module>-<unit|ui>.{log,xml}. Every run starts fresh: pitest's history plugin replayed old
# results (timeouts, kills, survivors) whenever the mutated class was unchanged, even after the tests changed.
# Output: one line per mutant left, sorted by file and line:
#   `SURVIVED|no coverage|timeout|memory error|run error  path:line  <Mutator>: <description>`
# (--verbose adds `killed  path:line  ...  [Test.method]`), then
#   `mutation-check: N mutants, K killed, S SURVIVED, U no coverage, T timeout; UI stage: n mutants on m files, k killed
#   in the UI stage` (kills by any test of the UI run, which runs all the module's tests, unit ones too).
#   --unit-only: `; unit tests only; preliminary` in place of the UI part; errors get ", E errors".
# A mutant's path is <module>/src/main/kotlin/<package path of its class>/<sourceFile>; a sourceFile with a "/" is the
# plugin's mapped location of an inlined copy (a path from a source root, maybe another module's) and is reported under
# the module that owns that file. A mutant on a file outside the requested ones is an internal error (exit 4).
# A timeout is not a kill (read its log, rerun, or break it by hand). pitest's XML does not list the mutants the junk
# filter dropped, so the summary has no count of them.
# Accepted gap: the copy of an inline function inlined into a class of another package or another module is not
# mutated, because targetClasses lists only the files' packages and pitest runs only in the file's module.
# Refuses (exit 2): a file whose package line does not match its folder (LINES would match nothing), a <base> that
# is not an ancestor of HEAD, uncommitted changes in the files to mutate, another run in
# this tree, a running Gradle build in this tree (gradle-running.sh).
# Usage: .claude/scripts/mutation-check.sh [--display N | --displays N,M] [--ui-tests A,B] [--detach] [--unit-only]
#                                          [--whole] [--verbose] <base> [file...]
#        .claude/scripts/mutation-check.sh --wait [seconds]
#        .claude/scripts/mutation-check.sh --accept <path>:<line>[:<Mutator>] <reason>
#   --display N     the display the UI stage uses (default 99); --displays N,M several, one worker each; each is
#                   started or repaired by setup-display.sh first (Linux)
#   --detach        start the run in the background and return; collect it with --wait (output.txt, status and run.pid
#                   in build/mutation-check/)
#   --wait          print what the detached run printed since the last --wait, waiting up to `seconds` (default 100,
#                   below the Bash tool's default 2-minute timeout); its exit code once finished, 5 while it runs
#   --unit-only     skip the UI stage: the run while implementing and in fix rounds (ADR 0189); its survivors include
#                   mutants only a UI test kills. The UI stage runs once per package, after the review
#   --ui-tests A,B  the test classes of the UI stage (see above); top-level classes, a name no mutated module
#                   declares is refused (exit 2); without effect with --unit-only
#   file...         only these production files (default in change mode: every one the commits since <base> change)
# Exit: 0 nothing left (accepted mutants are not left), 1 survivors, no-coverage mutants, timeouts or errors, 2 usage
# or refusal, 3 inconclusive (a red
# baseline -- Gradle's PitHelpError -- or another Gradle failure; a detached run that died), 4 internal error,
# 5 (--wait) still running, 128+signal when interrupted.
set -euo pipefail

exec python3 -I "$(cd "$(dirname "$0")" && pwd -P)/mutation_check.py" "$@"

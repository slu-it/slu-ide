#!/usr/bin/env bash
# Mutation run with pitest (ADR 0185, 0189): mutates the production Kotlin lines that the commits since <base> add
# (or, with --whole, every line of the given files), runs the tests and lists every mutant no test killed. How it
# works (stages, matching, the plugin, accepted gaps): the docstring of mutation_check.py.
# Usage: .claude/scripts/mutation-check.sh [--display N | --displays N,M] [--ui-tests A,B] [--detach] [--unit-only]
#                                          [--whole] [--verbose] <base> [file...]
#        .claude/scripts/mutation-check.sh --wait [seconds]
#        .claude/scripts/mutation-check.sh --accept <path>:<line>[:<Mutator>] <reason>
#   --unit-only     unit stage only: the run while implementing and in fix rounds; its survivors include mutants only
#                   a UI test kills. The UI stage (Linux only) runs once per package, after the review
#   --display N     the display the UI stage uses (default 99); --displays N,M several, one worker each; each is
#                   started or repaired by setup-display.sh first
#   --ui-tests A,B  the UI stage runs only these test classes (simple names of top-level classes; a name no mutated
#                   module declares is refused); a kill by a class that is not named shows up as a survivor; without
#                   effect with --unit-only
#   --detach        start the run in the background and return; collect it with --wait (output.txt, status and
#                   run.pid in build/mutation-check/)
#   --wait          print what the detached run printed since the last --wait, waiting up to `seconds` (default 100,
#                   below the Bash tool's 2-minute timeout); its exit code once finished, 5 while it runs
#   --whole         every line of the given files instead of the change's added lines
#   --verbose       also list the killed mutants with their test
#   file...         only these production files (default in change mode: every one the commits since <base> change);
#                   every rerun after the first complete run names its files (ADR 0189, amendment 2026-11-21)
#   --accept        record the mutants the last finished run left on that line as accepted, with the reason (an
#                   equivalence or an endless-loop timeout): later runs list them as `accepted` and skip them until
#                   the line's text changes. The mutator is needed when the line's leftovers have several. The
#                   entries are in build/mutation-check/accepted.tsv; delete a line there to take one back
# Output: one line per mutant left, sorted by file and line:
#   `SURVIVED|no coverage|timeout|memory error|run error|accepted  path:line  <Mutator>: <description>`, then
#   `mutation-check: N mutants, K killed, S SURVIVED, U no coverage, T timeout[, A accepted]; UI stage: n mutants on m
#   files, k killed in the UI stage` (--unit-only: `; unit tests only; preliminary` in place of the UI part).
#   Logs and reports: build/mutation-check/logs/<module>-<unit|ui>.{log,xml}; a detached run's output: output.txt.
# Refuses (exit 2): uncommitted changes in the files to mutate, a <base> that is not an ancestor of HEAD, a file whose
# package line does not match its folder, another run or a running Gradle build in this tree, a
# --ui-tests name that no mutated module declares.
# Exit: 0 nothing left (accepted mutants are not left), 1 survivors, no-coverage mutants, timeouts or errors, 2 usage
# or refusal, 3 inconclusive (a red baseline -- Gradle's PitHelpError -- or another Gradle failure; a detached run
# that died), 4 internal error, 5 (--wait) still running, 128+signal when interrupted.
set -euo pipefail

exec python3 -I "$(cd "$(dirname "$0")" && pwd -P)/mutation_check.py" "$@"

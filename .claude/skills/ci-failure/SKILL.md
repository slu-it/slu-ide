---
name: ci-failure
description: Use when the Thoreau CI build failed and the user put the test reports into tmp/ (e.g. "macOS build is broken, results in tmp/"). Finds the failing tests per platform, reproduces the platform condition locally, fixes the root cause and records new portability lessons.
---

# Fixing a CI failure from the reports in tmp/

The sandbox is Linux on ARM; CI runs Linux x64 and macOS. A test that passes here and fails there almost always
depends on something the platform does differently. Find that, don't just make the test pass.

## 1. Find the failures
- The user unpacks the CI artifacts into `tmp/` (git-ignored), e.g. `tmp/test-reports-macos-latest/<module>/...` with
  JUnit XML files. Several folders mean several platforms.
- `grep -rl '<failure\|<error ' tmp/ --include='*.xml'` lists the failing classes; read only the `<testcase>` blocks
  with a failure and their message and stack trace (plus `<system-err>` when the trace is not enough).
- Note per platform which tests failed and whether the same test passes on the other platform.

## 2. Classify each failure
- **Test assumption**: the test expects Linux behavior (paths, encodings, events, timing).
- **Product bug**: the app misbehaves on that platform too (e.g. a wait on the shared thread pool that stalls).
- **Timing**: a limit or wait too tight for a slower runner.
- **Flake**: fails sometimes everywhere; find the race, don't retry it away.
Check `docs/testing.md`, "Portability" first: most failures are one of its cases.

## 3. Reproduce before fixing
Recreate the platform condition here and see the test fail the same way, for example:
- few cores: `-XX:ActiveProcessorCount=3` (in the test task's `jvmArgs` temporarily, or a throwaway test);
- linked temp folder: run against a temp folder that is a symlink;
- stack: `-Xss512k`;
- slow runner: measure locally and compare with the CI time in the report; run under load;
- git encodings: configure `i18n.commitEncoding` and a message git converts differently;
- macOS file watching: the watcher's polling mode or the `os.name` path in the code.
If it cannot be reproduced, say so in the report and say why the fix still addresses the cause.

## 4. Fix the cause
- Fix product bugs in the product, with a test that fails without the fix under the reproduced condition.
- Fix test assumptions in the test (`toRealPath()`, `--encoding=UTF-8`, a growth assertion instead of a wall-clock
  limit, an `os.name` assumption with a reason). Never just raise a limit or add a retry.
- A production change gets a reviewer pass on its diff. Then run the affected classes, and the full gate
  (`.claude/scripts/full-build.sh`) when production code changed.
- One commit per cause, imperative subject. Don't push; leave `tmp/` as it is.

## 5. Record the lesson
A new kind of cause gets a line in `docs/testing.md`, "Portability" (what differs, how to write the test, how to
reproduce it), in the same change.

## 6. Report
Per platform: the failing tests, the cause, the fix commit, how it was reproduced (or why not), and what only the next
CI run can confirm.

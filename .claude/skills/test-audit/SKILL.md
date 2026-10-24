---
name: test-audit
description: Use when asked to review, clean up or speed up Thoreau's tests across modules (duplicates, UI tests that should be unit tests, gaps, slow or weak tests). Runs read-only audits per module group, triages the findings, applies them in worktree packages and measures the gain.
---

# Auditing and optimizing the tests

Load the `delegate` skill too: briefs, review loop, parallel packages and merge gate follow it. The first run
(2026-10-24, ADR 0084) found ~310 items in 19 modules and took the full build from 3:18 to about 2:15.

## 1. Measure
- `.claude/scripts/full-build.sh --rerun-tasks` (the build cache hides times), then
  `.claude/scripts/test-times.sh > <scratchpad>/test-times.txt` as the baseline for every agent.
- `.claude/scripts/check-tests.sh > <scratchpad>/check-tests.txt` for the static findings.

## 2. Audit (read-only, parallel)
- Split by module into groups of about 8,000–10,000 test lines (`wc -l` of `src/test`); split a large module by
  package, and give each half of a split the other's packages to grep for overlaps.
- One `reviewer` (Opus) per group with `audit-brief.md` (path it, don't paste it), the timings file, a scope, an ID
  prefix and what matters most there (a floor module's UI time, a slow unit task, a module with little coverage).
  These run no Gradle, so all groups can run at once.
- When a report arrives, ask the same agent to save it verbatim to `<scratchpad>/findings/<PREFIX>.md`.

## 3. Triage (main session)
- Per group: IDs to do and to skip, overlaps between groups (one owner each), the order (shared fakes and fixtures
  first, the fixture split last), and an explicit decision for every behavior change or bug. Before deciding one,
  check the declared `@Throws`, the contract and all callers (delegate skill, "Review loop").
- Build changes that need measuring (a `uiTestSplits` entry, forks) stay with the main session, after the merges.

## 4. Apply (worktree packages, at most three at a time)
- One `implementer` (Sonnet) per module group with `apply-brief.md`, the findings files, the decisions, a display
  (`:101` upwards) and a scratch folder. Packages are disjoint by module.
- Each finished package gets a `reviewer` pass on `git diff $(git merge-base master <branch>)..<branch>`, with the
  findings file and the production changes and behavior fixes named. Fixes go back to the same implementer.
- Merge as in the delegate skill; rebase and compile in the worktree when master moved.

## 5. Gate and measure
- `.claude/scripts/check-unused-imports.sh` over the changed files, then `full-build.sh --rerun-tasks` twice (time and
  flakes), `test-times.sh` for the new floor. A module whose UI tests are the floor and split well by package goes
  into `uiTestSplits` (ADR 0082); keep it only if the measured build is faster.
- Update `docs/testing.md` with new patterns (fixtures, signals, parameters) and record the round in memory.

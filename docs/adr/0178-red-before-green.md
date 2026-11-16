# 0178. Red before green: each test fails before its code, and a mutation run over every change

- Status: accepted
- Date: 2026-11-15

## Context
Implementers wrote production code and tests together and broke the code by hand afterwards ("break checks"), but
only for guards, wiring and races, and only those they chose. The reviewer reports from 2026-11-02 to 2026-11-15
(326 runs, about 255 first reviews and 71 re-reviews) show what that missed:
- At least 112 tests that could not fail, in 86 reports (about 18 Blockers). Reviewers found them by reading.
- 9 production bugs under a green test (7 Blockers), mostly behind fakes that behaved unlike the real tool
  (a `clear` that always exits 0, a JDBC fake that accepts `setSavepoint` in auto-commit).
- In one batch, 6 of 13 re-reviews found that the test added for a fix also passed on the unfixed code.

The causes, by share:
- About 40%: the input could not tell the guard from the default. A one-card fake ends a loop that the guard should
  end, two words of the same length give the same caret, a stock that is already empty refuses a draw anyway.
- About 25%: assertions true by construction. A clamp guarantees the bound, a model is null whenever no server
  runs, `RadioMenuItem.fire()` does not select.
- The rest: UI tests asserting the model instead of what is painted, names that claim more than the test checks,
  and break checks reported but not possible.

Running the brief's tests red against stubs would not have caught the largest group: against a stub every test fails
because nothing exists, not because the one guard is missing.

## Decision
- **Each test fails before its code exists.** Write the test, run it and see it fail on its assertion, then write
  the code. A compile error or a stub's exception is not a red.
- **A guard's red shows that only this guard decides.** Guards, filters, wiring lines and race fixes get their test
  once the code around them works and only that guard is missing.
- **A fix starts with a test that fails on the unfixed code**, including fixes asked for by a review.
- **Tests of behavior that already exists** (refactors, gap tests from audits) cannot be red first: break the code
  once instead.
- **Fakes of external tools, drivers and toolkit behavior** match the real one on the cases a test relies on, checked
  once against the real thing and named in a comment.
- **After the last commit, `.claude/scripts/break-check.sh` mutates the guards and calls the change adds, one at a
  time,** and runs the tests: a source-level mutation run limited to the change, on top of `verify.sh`, instead of a
  bytecode tool (pitest) with its Kotlin and JavaFX friction and a new Gradle plugin. It catches what a red step done in
  the wrong order misses, without relying on the implementer's account. Its line-based mutations miss some guards
  (multi-line conditions, `when (x)` branches, block-bodied Boolean functions; `?: return` exits and bare `when`
  branches are mutated since the evaluation below); it lists those, and they keep a break check by hand.
- **Reports quote the red**: per new test, the failing assertion line from `verify.sh`'s digest.

## Consequences
- More `verify.sh` runs per package (one per behavior and guard), plus the break-check run before reporting. Each mutant
  runs in fail-fast stages (the UI test method that killed the previous mutant, the unit tests, the UI classes), so most
  mutants never pay for the full UI test set; only survivors do, and the rerun after fixing tests (`--survivors`) runs
  only those, plus every mutant of a file whose production code changed. Measured on the Find in Files popup (72
  mutants, 9 survivors) in a worktree: 1109 s with the killing class first and no daemon; 697 s with a daemon of the run
  alone (break-check stops it at the end; about 1.2 GB while it runs, so up to 3.5 GB with three parallel implementers,
  and `full-build.sh` refuses to start while one is up); 541 s with the killing test method first instead of its class
  (592 s with the last 8 killing methods). Without a daemon the method came out only 5% faster, because every stage paid
  Gradle's start of 3-5 s. The `--survivors` rerun took 82 s without a daemon. Runs outlast one Bash call, so agents
  start them detached and collect them with `--wait`; skipping presentation lines (style classes, sizes, prompt texts)
  keeps the count down.
- Reviewers check the red lines and the break-check survivors instead of reconstructing every test's failure by
  reading.
- A red is never committed: tests and code still land in one commit, so git does not show the red; the report and
  the break-check run do.
- Amends [ADR 0084](0084-testability-logic-out-of-controls.md) (its "A test fails without the code it covers").
  Rules: `.claude/rules/testing.md`; how: `docs/testing.md` ("Red before green", "The break-check script").

## Evaluation (2026-11-16)
The first round under this decision, Java code intelligence ([ADR 0179](0179-java-via-jdtls.md), 64
commits, 6,900 production and 15,600 test lines), against Nov 2-15. Its 24 review turns were all read; the baseline
reports were filtered by wording, and a random sample of 25 of the reports the filter skipped corrected that estimate
(so the baseline numbers carry several points of uncertainty).

| Per review | Nov 2-15 (corrected) | Java round |
|---|---|---|
| Reviews with a vacuous test | ~38% | 33% |
| Vacuous tests | ~0.46 | 0.46 |
| Bugs under a green test | ~0.15 | 0.42 |
| Vacuous tests per 1,000 test lines | ~1.4 | 0.7 |
| Vacuous tests that were Blockers | ~16% | 0 |

- **The classic vacuous tests are gone**: an input that cannot tell the guard from the default, an assertion true by
  construction. What remained: 5 of 11 were break-check survivors the implementer called equivalent and the reviewer
  killed with a concrete input; guard-like lines break-check does not mutate and nobody checked by hand (a Blocker:
  `same()` reduced to `a == b` stayed green); names that claim more than the test checks.
- **Bugs under a green test rose**, nearly all from fakes that behaved unlike jdtls (token counts, status order, a
  response body) or inputs that could not reach the bug: 6 of the round's 13 Blockers. Neither a red step nor a
  mutant catches a fake that is wrong the same way as the code.
- **The cost**: implementers spent about 4.6 times as many active minutes per production line (7.4 instead of 34
  production lines a minute), and wrote 2.26 test lines per production line instead of 0.82; reviewers 7.7 instead of
  3.4 active minutes per run, and 10 of 12 first reviews said "merge: no". The round was one large, concurrency-heavy
  feature around an external server, so the ratios are rough.

Follow-ups decided from it: reviewers check every survivor called equivalent first; break-check mutates `?: return`
exits, conditions of a `when` without subject, boolean expressions outside `if` and Boolean expression bodies; fakes of
an external server are built from recorded real traffic.

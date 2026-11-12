# Applying test audit findings (implementer brief)

You apply the findings of a read-only test audit to one package of modules. Your prompt names the package, the
findings file(s), which IDs to do or skip, the decisions the main session took, your display and scratch folder.

## Goal
Faster, leaner tests with the same or better coverage: delete true duplicates, move logic tests out of `uiTest` into
unit tests (extracting small pure functions from controls where the finding says so), replace fixed sleeps with
waits on a real signal, fix weak assertions, and add the listed missing tests.

## Rules
- Your agent definition's worktree, verification and commit rules apply; this brief only adds to them.
- Read `docs/testing.md` ("Choosing the test level", "Writing UI tests"), ADR 0084 and `.claude/rules/testing.md`.
- Finding line numbers drift as you edit: Grep for the test name. Read ranges of big files, not whole files.
- Before deleting a test as DUPLICATE, open the test said to keep it covered and confirm it does; move a missing
  assertion over first, or skip the item and say why.
- A DOWNGRADE keeps the behavior tested: the unit test lands in the same commit as the UI test's removal, and one UI
  test of the wiring stays (it must fail if the view stops calling the extracted logic).
- Production changes only as small as the finding says (an `internal` pure function, a constructor parameter with
  the old value as default, a visibility change). Behavior must not change except for bugs your prompt explicitly
  decides to fix. Anything larger, or a finding that turns out wrong: skip it and report why.
- Never weaken a test to get it green. Suggested gap tests also get a break check: an audit's suggestion can be
  vacuous.
- Production files stay under 600 lines.

## Verification
- While working: `.claude/scripts/verify.sh --display <n> :modules:<m> <Class>...`.
- At the end, in your own worktree only, the full `test` and `uiTest` tasks of each module in your package (the one
  exception to the implementer's "no unfiltered `uiTest`"): `.claude/scripts/setup-display.sh <n> >/dev/null &&
  DISPLAY=:<n> ./gradlew --no-daemon testClasses :modules:<m>:test :modules:<m>:uiTest -q`. Read the output in
  full; a skipped `uiTest` is not a pass.
- Times before and after: `.claude/scripts/test-times.sh modules/<m>` (baseline: the timings file in your prompt).

## Report (final message)
- Commits (hash + subject).
- Per finding ID: done / skipped (why) / changed approach (how). One line each.
- Test counts and task times before → after per module and task.
- Exact verification commands and results.
- Docs that should change: name the doc and the change; edit only when your prompt says so.

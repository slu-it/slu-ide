# Applying test audit findings (implementer brief)

You apply the findings of a read-only test audit to one package of modules. Your prompt names the package, the
findings file(s), which IDs to do or skip, the decisions the main session took, your display and scratch folder.

## Goal
Faster, leaner tests with the same or better coverage: delete true duplicates, move logic tests out of `uiTest` into
unit tests (extracting small pure functions from controls where the finding says so), replace fixed sleeps with
waits on a real signal, fix weak assertions, and add the listed missing tests.

## Rules
- Base: your worktree starts at an old commit. First `git rebase master` (or `git reset --hard master` before your
  first commit) and confirm with `git merge-base --is-ancestor master HEAD`.
- Read `docs/testing.md` ("Choosing the test level", "Writing UI tests"), ADR 0084 and `.claude/rules/testing.md`.
- Finding line numbers drift as you edit: Grep for the test name. Read ranges of big files, not whole files.
- Before deleting a test as DUPLICATE, open the test said to keep it covered and confirm it does; move a missing
  assertion over first, or skip the item and say why.
- A DOWNGRADE keeps the behavior tested: the unit test lands in the same commit as the UI test's removal, and one UI
  test of the wiring stays (it must fail if the view stops calling the extracted logic).
- Production changes only as small as the finding says (an `internal` pure function, a constructor parameter with
  the old value as default, a visibility change). Behavior must not change except for bugs your prompt explicitly
  decides to fix. Anything larger, or a finding that turns out wrong: skip it and report why.
- Never weaken a test to get it green. A fixed sleep becomes a wait on a real condition, unless the finding says it
  is a negative check without a signal; then a short sleep with a `//` reason.
- Every new or changed test whose failure is not obvious (wiring, races, negative checks, suggested gap tests):
  break the code once, see it fail, restore it. Report the ones you checked this way.
- Files under 600 lines (production) and test topic classes under ~800 lines. Near-duplicates become
  `@ParameterizedTest`.
- Remove imports your deletions leave behind (`.claude/scripts/check-unused-imports.sh <files>`), and run
  `.claude/scripts/check-tests.sh` on the test files you touched.
- Commits: one logical step each, imperative subject, no Claude attribution; never push. `./gradlew spotlessApply`
  before each commit.

## Verification
- Display: `.claude/scripts/setup-display.sh <n>` and `export DISPLAY=:<n>` in the same command as Gradle; kill only
  processes you started.
- While working: `./gradlew :modules:<m>:test -q` and `:modules:<m>:uiTest --tests '<Class>' -q`.
- At the end, in your own worktree only: the full `test` and `uiTest` tasks of each module in your package and
  `./gradlew testClasses -q`. Read the output in full; a skipped `uiTest` is not a pass.
- Times before and after: `.claude/scripts/test-times.sh modules/<m>` (baseline: the timings file in your prompt).

## Report (final message)
- Commits (hash + subject).
- Per finding ID: done / skipped (why) / changed approach (how). One line each.
- Test counts and task times before → after per module and task.
- Exact verification commands and results.
- Docs that should change: name the doc and the change; edit only when your prompt says so.

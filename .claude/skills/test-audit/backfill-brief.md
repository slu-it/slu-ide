# Closing test gaps in existing code (implementer brief)

You write the missing tests for existing production code that never went through red-before-green or a mutation run
(code from before ADR 0178). A whole-file pitest run listed the mutants no unit test kills; that list is your work
list. Your prompt names the package, the files, the list, the master commit, your display and scratch folder.

For existing behavior ADR 0178 says "break the code once" instead of red-first: the mutant is that break. A test is
done when the mutation run shows it killing the mutant it targets.

## Read first
ADR 0178, ADR 0084, ADR 0185, `.claude/rules/testing.md`, and in `docs/testing.md` the sections "Choosing the test
level" and "The mutation-check script" (Grep for them, read ranges). Test classes over ~400 lines: Grep for the place
and read a neighbouring test as a pattern, never the whole class.

## Steps
1. `.claude/scripts/worktree-check.sh start <master commit>`. Record `date +%T` at the start, after the triage, after
   the last test commit, after the final mutation run.
2. Triage every line of the work list into exactly one class. Read the production code around it first; several
   mutants on one line or branch are usually one gap.
   - **GAP**: behavior someone relies on that no test checks. Write the test at the lowest level, extending the test
     class that covers the neighbouring behavior. Use an input on which only this guard or call decides the outcome.
     A no-coverage mutant in a file without any test is a GAP too: start that file's test class.
   - **EQUIVALENT**: no input can tell the mutant apart. Name the input or invariant (the caller already guarantees
     it, a later check catches the same case), with file:line. "No test reaches it" is a GAP, not an equivalence.
   - **UNOBSERVABLE**: the difference exists but a test on Linux cannot see it (a macOS-only path, a race window of
     microseconds). Give the reason.
   - **ENDLESS**: a timeout, memory error or run error where the mutation visibly makes a loop endless or an
     allocation unbounded (name the loop). Any other timeout or error: rerun it once; if it stays, triage the line as
     above.
   - **LOG**: the mutant only changes a log line, a call-log entry or a debug string nobody reads. List it, no test.
   - **DEAD**: code no input reaches. Remove it in its own commit ("Drop ... no input reaches"); behavior must not
     change.
   - **BUG**: the code is wrong. Do not fix it and do not write a test that pins the wrong behavior; note input,
     expected and actual, and go on with the other lines.
3. Write the tests. Commits: one per production file or closely related group, imperative subject
   ("Test GitBranches: ..."), no attribution lines. `./gradlew spotlessApply` before committing.
4. While in a test class, note any test that cannot fail or duplicates another (WEAK / DUPLICATE, file:line and why).
   Fix it only when that takes a line or two.
5. After the last commit: `.claude/scripts/mutation-check.sh --display <N> --detach --whole --unit-only --verbose
   <master commit> <the package's files>`, then `--wait` until it ends (`--wait 100` per call). Save its output as
   `<scratch>/mutation-after.txt`. Every mutant left must be one of your EQUIVALENT, UNOBSERVABLE, ENDLESS, LOG or BUG
   items; a GAP still alive gets a sharper test and another run. "Not understood" is not a class: read the bytecode
   (`javap -c -p` on the class in `build/classes`) until you can name one, or report it as an open GAP and why. The `killed ... [Test.method]` lines are the red
   evidence for your GAP tests: each GAP must show its targeted line killed by the test you wrote.
6. Verification in your worktree: `.claude/scripts/verify.sh --display <N> :modules:<m>` for each module of the
   package (all its unit tests), plus the UI test classes your prompt names.

## Pitfalls the first reviews found
- Most "equivalent" claims that failed review had a killing input nobody tried: an input where the old test's value
  hid the mutant (a readable entry where only an unreadable one tells), a second call of a selector
  (`maxByOrNull` calls it twice), a store that "never throws" but has a throwing getter, a folder `chmod 000` that
  makes a walk fail, `/dev/null` or `/dev/zero` for size and read limits. Name the input you tried; check JDK,
  library and tool behavior in jshell or with the real tool.
- A blocked read (a FIFO) is not ENDLESS; find an input that differs without blocking.
- Tests go into the class of the neighbouring behavior or a class named by topic, never a `*GapsTest` class;
  search for an existing test first and extend it instead of restating it.
- Assert the behavior (the message equals git's message), not the absence of the mutant's text.
- A test that could hang on a regression (a FIFO, a blocking read) runs under `assertTimeoutPreemptively`.

## Limits
- Production changes only: an `internal` extraction a test needs (ADR 0084), a constructor parameter with the real
  default, DEAD removals. Anything else in production code, or files outside the package's modules: stop and report.
- Tests of code that runs external tools (git, docker, JDBC drivers, language servers) use the real tool where the
  module's tests already do; a fake follows `.claude/rules/testing.md` (checked once against the real tool, named in
  a `//` comment).
- No fixed sleeps; tests touching files, processes or timing follow `docs/testing.md`, "Portability".
- Test classes stay under about 800 lines; split by topic. Production files stay under 600 lines.
- If the package turns out much bigger than its list suggests (more than about 150 GAPs), finish the files you have
  started, stop, and report which files are left.

## Report (final message)
- Worktree path, branch, commits (hash + subject), timestamps.
- Totals: work-list lines; per class GAP / EQUIVALENT / UNOBSERVABLE / ENDLESS / LOG / DEAD / BUG; mutants left after.
- One line per non-GAP item: file:line, mutator, class, reason. GAPs grouped per file: test names and the lines they
  kill (from the `--verbose` run).
- BUG items with reproduction. WEAK / DUPLICATE notes.
- `git diff --stat <master commit>..HEAD`, the verification commands and results, the final mutation-check summary.

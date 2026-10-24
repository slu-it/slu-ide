# Test audit brief (read-only)

Read `docs/testing.md` first, especially "Choosing the test level", and ADR 0084: unit tests and UI tests share
`src/test/kotlin`; UI tests are classes tagged `@Tag("ui")` and run in the slow `uiTest` task (or `uiTestPart1/2`)
on a virtual display. Large UI subjects use an abstract `*UiFixture` plus `*<Topic>UiTest` classes.

Per-class timings of the last full build: the file named in your prompt (from `.claude/scripts/test-times.sh`).
`.claude/scripts/check-tests.sh <files>` lists fixed sleeps, synthetic input and oversized classes in your scope.

## Goal
Audit the tests of your scope and produce a ranked, actionable findings list. You change nothing and run no Gradle.
Read the tests; read production code where needed to judge coverage (Grep and ranged Reads for files over ~400 lines).

## What to look for
1. DUPLICATE: a test that checks the same behavior as another test (same module, or a unit test vs. a UI test, or
   `ide` vs. a feature module) with no extra value. Verify by reading both test bodies, not by name. Name the one to
   delete and the one that keeps it covered, and any unique assertion that must move first. Also near-duplicates
   that should be one `@ParameterizedTest`, and shared code tested again by every user.
2. DOWNGRADE: a UI test (or part of one) that tests logic, not the control, and could be a plain unit test, directly
   or after a small extraction (an `internal` pure function, a plain class, an FX-free host interface with a fake).
   Say which production function/class would be tested, how much production code changes, the uiTest seconds
   saved, and which single UI test keeps the view→logic wiring covered.
3. GAP: important behavior without tests: error paths, edge cases, parsing, concurrency, branches never exercised.
   Name the production file:line and the test to add (unit or UI). Logic bugs hide there, not in getters or wiring.
4. SLOW: avoidable slowness: fixed sleeps, long timeouts on the happy path, delays that are not injectable, heavy
   setup per test where per-class would do, real processes where a fake would do, polling loops. Quote the line.
5. WEAK: tests that cannot fail or do not check what their name claims (missing assertions, counts instead of
   messages, negative checks without a sentinel, assertions that pass for the wrong reason). When you suggest a
   test, say how it fails if the code is broken.
6. OTHER: misplaced tests, dead helpers, copied helpers that belong in a fixture, tests of another module's code.

## Output (your final message, nothing else; it is all the main session sees)
- One line: scope, test files read, total findings.
- At most 40 items, ranked by value. Each item:
  `ID | CATEGORY | file:line (test) | what | proposed action | prod change? (none/small/medium) | effort S/M/L | confidence high/med/low`
  IDs are `<scope-prefix>-<n>` (prefix given in your prompt).
- For DOWNGRADE items, estimated uiTest seconds saved.
- A final line "Not worth changing:" with what you considered and rejected, in one or two sentences.
Be concrete enough that a Sonnet implementer can act on each item without re-auditing. When asked, save the report
verbatim to the findings file named in your prompt (Bash heredoc with a quoted delimiter).

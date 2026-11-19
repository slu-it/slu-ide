---
paths:
  - "modules/*/src/**/*.kt"
  - "ide/src/**/*.kt"
---

# Testing and testability

Details, examples and the stories behind these rules: `docs/testing.md`, ADR 0084, ADR 0178.

- Red before green (ADR 0178): each test fails on its assertion before its code exists; a compile error or a stub's
  exception is not a red. Guards, filters, wiring lines and race fixes come last, one at a time, each red with only
  it missing; a fix's test fails on the unfixed code first. A test that passes at once gets a sharper input, never a
  weaker assertion. Existing behavior (moved code, gap tests) gets a mutation run before and after instead
  (`mutation-check.sh --display <N> --whole <base> <file>`: the new test kills the mutants on the lines it targets).
  A unit test: both runs `--unit-only`. A UI test: the before run `--unit-only`, the after run with the UI stage
  (`--displays`, `--ui-tests <the test's class>`).
- `.claude/scripts/mutation-check.sh --unit-only --detach <base>` after the last commit and after review fixes; the full
  run with the UI stage (`--displays`, `--ui-tests <the package's UI test classes>`) once per package, after the review
  (ADR 0185, 0189). Every SURVIVED or no-coverage mutant is killed with a sharper input or test, removed as dead code,
  or reported as equivalent by naming the input that cannot tell it apart (the caller already guarantees it; the
  platform does it anyway on every OS, with the source that shows it); "no test reaches it" is a gap, not an
  equivalence. A timeout is not a kill unless the mutation visibly makes a loop endless or an allocation unbounded; name
  that per timeout. An equivalence or such a timeout is recorded with `mutation-check.sh --accept
  <path>:<line>[:<Mutator>] <reason>`, so later runs skip it; before the full run, code only a UI test reaches is
  reported as left for the UI stage, never accepted. A hand break check stays only for what pitest cannot mutate:
  removing an inlined `filter { }` (its predicate always true), a `close()` call, an inline function of ours used only
  from another package or module (it gets no mutants). Its test runs under `timeout 600`; a run the limit ends is a hang
  (often a dialog on the FX thread), not a red.
- Unit tests over UI tests. A `@Tag("ui")` test only for what needs a scene: rendering, focus, event routing,
  bindings, WebKit.
- Logic in a control is extracted (an `internal` pure function, a plain class, an FX-free host with a fake),
  not tested through the UI. Keep one UI test per view→logic connection that fails if the call is removed.
- Search before adding: grep the asserted message and the function; extend the existing test. Test shared code once;
  feature behavior in its module, `ide` only the wiring.
- Same test with other input: one `@ParameterizedTest`, not copies.
- Wait on a signal (idle barrier, latch, log line, sentinel, or a poll loop with a deadline), never a fixed sleep; a
  negative check without a signal keeps a short sleep with a `//` reason. Delays and debounces are constructor
  parameters with the real default.
- A UI test whose flow can show an error or question dialog replaces that seam: a real `Alert` blocks the FX thread,
  so a broken path hangs instead of failing (`docs/testing.md`, "Writing UI tests").
- No synthetic OS input (`clickOn`, `moveTo`, `type`, `scroll`) unless only real input can test it (`//` reason);
  `interact` already waits for FX events.
- A test's name claims only what it asserts: "without saving", "zeroes the array", "dismissible" each need their
  own assertion.
- What the user sees is tested by what is painted, not only by the data behind it: a WebView page's or a stylesheet's
  look gets a pixel or computed-style check (`docs/testing.md`, "WebView pages").
- Fakes of another component's output keep its invariants (empty parts, gaps, order): build them with that
  component's real builder where one exists. A fake of an external tool, driver or toolkit call (a CLI's exit
  codes, JDBC in auto-commit, `RadioMenuItem.fire()`) behaves like the real one on the cases the test relies on:
  check them once against the real thing (the sandbox's tool, jshell) and name that check in a `//` comment.
- A fake of an external server (a language server, anything speaking a protocol over time) replays recorded real
  traffic (`.claude/scripts/record-lsp.sh`, `docs/testing.md` "Fakes of external servers"): its messages, order and
  counts come from a recording under `src/test/resources/`; a case no recording shows gets a recording, not a guess.
- Code that reads untrusted input gets hostile tests (`.claude/rules/safety.md`, "Untrusted input").
- Deleting a test: open the test that keeps it covered first. Remove the imports a deletion leaves behind.
- Test classes stay under about 800 lines; split by topic (`docs/testing.md`, "Writing UI tests").
- CI also runs on macOS and slower x64 runners: tests touching files, processes, timing, recursion or git
  encodings follow `docs/testing.md`, "Portability".

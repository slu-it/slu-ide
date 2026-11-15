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
  weaker assertion. Existing behavior (refactors, gap tests) gets a break check instead.
- `.claude/scripts/break-check.sh` after the last commit: fix each survivor (sharper input, or delete the dead code)
  or explain it as equivalent; the guard-like lines it lists as not mutated get a break check by hand. A timeout is
  not a kill: read its log, rerun the file, or break it by hand.
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
- Code that reads untrusted input gets hostile tests (`.claude/rules/safety.md`, "Untrusted input").
- Deleting a test: open the test that keeps it covered first. Remove the imports a deletion leaves behind.
- Test classes stay under about 800 lines; split by topic (`docs/testing.md`, "Writing UI tests").
- CI also runs on macOS and slower x64 runners: tests touching files, processes, timing, recursion or git
  encodings follow `docs/testing.md`, "Portability".

---
paths:
  - "modules/*/src/**/*.kt"
  - "ide/src/**/*.kt"
---

# Testing and testability

Details, examples and the stories behind these rules: `docs/testing.md`, ADR 0084.

- Unit test first. A `@Tag("ui")` test only for what needs a scene: rendering, focus, event routing, bindings, WebKit.
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
- A test must fail without its code: for wiring, races, guards and negative checks, break the code once, see it
  fail, and report what you broke and which test failed; a new guard without a reported break check counts as
  untested. A test of a guard or filter needs an input where only that guard decides the outcome.
- What the user sees is tested by what is painted, not only by the data behind it: a WebView page's or a stylesheet's
  look gets a pixel or computed-style check (`docs/testing.md`, "WebView pages").
- Fakes of another component's output keep its invariants (empty parts, gaps, order): build them with that
  component's real builder where one exists.
- Code that reads untrusted input gets hostile tests (`.claude/rules/safety.md`, "Untrusted input").
- Deleting a test: open the test that keeps it covered first. Remove the imports a deletion leaves behind.
- Test classes stay under about 800 lines; split by topic (`docs/testing.md`, "Writing UI tests").
- CI also runs on macOS and slower x64 runners: tests touching files, processes, timing, recursion or git
  encodings follow `docs/testing.md`, "Portability".

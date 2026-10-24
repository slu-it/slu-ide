---
paths:
  - "modules/*/src/**/*.kt"
  - "ide/src/**/*.kt"
---

# Testing and testability

Details: `docs/testing.md` ("Choosing the test level"), ADR 0084.

- Unit test first. A `@Tag("ui")` test only for what needs a scene: rendering, focus, event routing, bindings, WebKit.
- Logic in a control is extracted (an `internal` pure function, a plain class, an FX-free host with a fake),
  not tested through the UI. Keep one UI test per view→logic connection that fails if the call is removed.
- Search before adding: grep the asserted message and the function; extend the existing test. Test shared code once;
  feature behavior in its module, `ide` only the wiring.
- Same test with other input: one `@ParameterizedTest`, not copies.
- Wait on a signal (idle barrier, latch, log line, sentinel), never a fixed sleep; a negative check without a signal
  keeps a short sleep with a `//` reason. Delays and debounces are constructor parameters with the real default.
- No synthetic OS input (`clickOn`, `moveTo`, `type`, `scroll`); `interact` already waits for FX events.
- A test must fail without its code: for wiring, races and negative checks, break the code once and see it fail.
- Deleting a test: open the test that keeps it covered first. Remove the imports a deletion leaves behind.

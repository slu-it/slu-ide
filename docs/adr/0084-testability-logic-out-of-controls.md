# 0084. Testability: logic out of controls, UI tests for what needs a scene

- Status: accepted
- Date: 2026-10-24

## Context
A test audit of all modules (2026-10-24) found the same causes everywhere:
- **Logic only a UI test could reach.** Decisions sat in private code of controls: messages and counters
  (`missingMessage`, the find bar counter), sizes (`checkedSize`, `gitMessageSize`), key and scroll mapping
  (`zoomKeyAction`, `ScrollRouting`), dialog answers, file writes (`EnvFileWriter`), lookup bookkeeping
  (`IgnoreDotsTracker`). Every one needed a real view, a Stage and often a WebView to be tested.
- **Duplicates.** UI tests asserted texts, enable matrices and validation that unit tests already covered;
  `MainWindow*` tests re-tested feature modules; shared code (the fold tree of JSON, XML and YAML, the zoom controls)
  was tested once per user; 14 near-identical tests differed only in their input.
- **Slow and weak tests.** Fixed sleeps, delays and debounces the tests could not shorten (`STOP_GRACE`,
  `EXPAND_DELAY`), synthetic OS input, and tests that still passed with the code they named removed.

Small extractions fixed it every time, and an FX-free host with a fake (`ExplorerEditHost`, `FakeEditHost`)
moved 27 file-explorer UI tests to unit tests. UI test time fell by about a third, and the full build went from 3:18 to
about 2:15 together with [ADR 0082](0082-split-ui-tests-of-long-modules.md)'s previews split.

## Decision
- **Controls are thin adapters.** A control holds layout, bindings and event wiring. Decisions (texts, enablement,
  validation, mapping of input to actions, sizes, what to write or read) go into `internal` pure functions or plain
  classes beside it, in the same package.
- **What logic needs from a view is a host without JavaFX controls**, an interface or a class of function properties
  (`ExplorerEditHost`), so a unit test passes a fake. `TreeItem` and other plain model classes work without the
  toolkit and may appear in it.
- **Timings are parameters.** Delays, debounces, graces and timeouts are constructor parameters (or an internal
  supplier) with the real value as default (`CodeView(highlightPause)`, `GitCli(stopGrace)`, `dragExpandDelay`);
  background work offers an idle barrier for tests to wait on (`GitLogView.readersIdle()`).
- **A behavior is tested once, at the lowest level that can see it.** A UI test covers what needs a scene:
  rendering, focus, event routing, bindings, and one wiring test per connection between a view and its extracted
  logic (the call must happen; the logic's cases are the unit test's job). Feature behavior is tested in its module;
  `ide` tests the wiring between modules. Shared code is tested in one place, with one smoke test per user.
- **A test fails without the code it covers.** For a test whose failure is not obvious, break the code once and see
  it fail.

## Consequences
- Most new behavior gets unit tests; a new `@Tag("ui")` test needs a reason a unit test cannot cover it.
- Views get a little more surface (`internal` functions, host interfaces, timing parameters) for testability.
- Rules: `docs/testing.md` ("Choosing the test level"), `.claude/rules/testing.md`; reviewers check for duplicates,
  avoidable UI tests and tests that cannot fail. `.claude/scripts/check-tests.sh` reports fixed sleeps, synthetic
  input, redundant waits and oversized test classes, and a hook reports them in newly written test code.
- Periodic audits follow the `test-audit` skill.

# 0185. Mutation testing with pitest

- Status: accepted
- Date: 2026-11-18

## Context
[ADR 0178](0178-red-before-green.md) added `break-check.sh`: a source-level mutation run over every change, one mutant
at a time, each recompiled through Gradle. It works for a change, but it does not scale:
- A mutant costs 8-15 s because of the recompile. A backfill over the existing code (about 17,000 mutants) would have
  needed about 70 compute hours.
- It mutates by lines, so it lists guard-like lines it cannot mutate (multi-line conditions, `when` branches,
  block-bodied Boolean functions) for a hand break check, and it needs a private Gradle daemon and a restore
  machinery for the sources it edits.

Spikes on 2026-11-17/18:
- **Cost per mutant:** pitest with unit tests costs 0.06-0.18 s per mutant, about 80 times faster.
- **Coverage:** on the git pilot's ground truth (93 real gaps over 12 files), pitest with four mutator groups flags 87
  with no custom mutator.
- **Junk:** 27-35% of its survivors are Kotlin bytecode junk (null checks, stdlib inline code, getters).
- **Why not `avoidCallsTo`:** pitest's own filter works per line and lost 9 real gaps. The plugin judges each mutant
  by its instruction instead.
- **UI tests:** about 100 times slower per mutant than unit tests. In file-explorer, 3-29% of a class's mutants are
  killed only by UI tests.

## Decision
Mutation runs use pitest 1.30.2. This amends ADR 0178 (its mechanics of the mutation run; its rules stay).

- **The plugin `tools/pitest-kotlin`** has two features:
  - `KOTLIN_JUNK` (on by default) drops compiler junk, one mutant at a time, by the mutated instruction: Intrinsics
    null checks, `closeFinally`, stdlib inline code, the declarations of our own inline functions (their copies are
    kept and relocated to the source line), plain getters, spread/varargs plumbing, `Result` boxing.
  - `LINES(file[...])` keeps only the mutants on an allow-list of `path:line`.
- **Gradle wiring** (`configurePitest()` in the root `build.gradle.kts`) on every Kotlin module outside `:tools`,
  driven by `-Psluide.pitest.*`: mutators DEFAULTS, REMOVE_CONDITIONALS_EQUAL_IF/ELSE and NON_VOID_METHOD_CALLS; a
  timeout constant of 35 s (the longest test wait is 30 s); `--mutationUnitSize=10` so one file spreads over the
  workers; `pitest-java.sh` leases one display per worker JVM for UI runs.
- **`mutation-check.sh`** drives it, per module, in two stages:
  1. The unit stage runs the module's unit tests over the mutants on the change's added lines (or, with `--whole`,
     every line of the given files).
  2. The UI stage runs all the module's tests over the files that still have SURVIVED or no-coverage mutants. It is
     Linux only (it needs `flock` and a display per worker); elsewhere it is skipped and the summary says
     "unit tests only; preliminary". `--unit-only` skips it on purpose.
- **Rules that stay from 0178:**
  - A timeout is not a kill, unless the mutation visibly makes a loop endless or an allocation unbounded; the report
    names that reason per timeout.
  - An equivalent survivor needs its reason: why no input can tell it apart.
  - The reviewer checks the equivalence claims first.
- **Existing behavior** (moved code, whose `-M` diff counts only changed lines, and gap tests) uses `--whole <base>
  <file>` before and after the new test, instead of 0178's "break the code once". The targeted mutants go from
  SURVIVED or no coverage to killed. A unit test: both runs are `--unit-only`. A UI test: the before run is
  `--unit-only`, the after run has the UI stage, with the displays the brief hands out. A hand break check stays
  only for what pitest cannot mutate (see the accepted gaps).
- **Dropped, with the reason:**
  - break-check's private Gradle daemon: pitest runs in its own JVMs.
  - The "not mutated, hand break check" list: pitest mutates every jump, multi-line conditions and `when` branches
    included.
  - `--list` and `--survivors`: a plain rerun is cheap enough.
  - pitest's history (`pitest-history-plugin`), added first and removed on 2026-11-18: it replayed a mutant's old
    result whenever its class was unchanged, without checking whether the tests had changed. In the gap backfill,
    where only tests change, final runs showed timeouts and survivors that the new tests no longer allowed, and kills
    that no longer held. Every run now runs every mutant.
  - The restore machinery: pitest never edits sources.
- **Accepted gaps:**
  - Removing an inlined `filter { }` (its predicate always true): no mutator does that. The plan accepted
    "filter/takeIf" up front; the plugin turned out to cover `takeIf`, which is mutated like our code.
  - `close()` has no void-call mutant. This was seen on one line (GitEnvironment:57); the cause is unknown.
  - Mutants that other tests of the module kill: pitest runs all of the module's tests, break-check only the file's
    own.
  - Copies of an inline function inlined into another package or module are not mutated, in both stages. An inline
    function of ours used only from another package or module therefore gets no mutants at all: its declaration is
    dropped as junk and its copies sit in classes the run does not target. A change to it reports 0 mutants on its
    lines.
  - `@InlineOnly` stdlib (`require`, `check`, `takeIf`, `repeat`, `error`) maps to the caller's line, so it counts as
    our code.
  - The 35 s timeout constant makes every endless-loop mutant cost at least 35 s.
  - A UI-stage kill by a test that let the mutant survive in the unit stage still counts as killed (the summary says
    "killed in the UI stage", not "by UI tests"). In one run 5 mutants survived the unit stage and died in the UI
    stage under the same unit test, which points to a timing-dependent test.

## Consequences
Acceptance runs on 2026-11-18:
- **Pilot ground truth** (12 git files, `--whole --unit-only`): 1781 mutants in 378 s; 1383 killed, 317 survived,
  77 without coverage, 4 timeouts. 90 of 93 gaps flagged; the 3 misses are 2 inlined `.filter { }` lines and
  `process.outputStream.close()`, all accepted gaps. Survivors plus no-coverage mutants: 394, against 638 without the
  junk filter (-38%).
- **UI-only kills** (`ExplorerClipboard.kt`, `ClipboardImages.kt`, 3 displays): the spike saw 22 mutants killed
  only by UI tests; 20 are killed in the UI stage. The other 2 (`Collection::add` inside an inlined stdlib `map`) are
  now dropped as junk and no longer mutated. Unit stage 8 s, UI stage 587 s for 62 mutants.
- **Change mode** on a refspec fix in `GitPush.kt`: 10 mutants, only on the added lines, all killed by the fix's
  test, 41 s.
- **`TreeWatches.kt`** (178 mutants, a class most of file-explorer's UI tests cover): unit stage 526 s on one worker
  before the unit size, 158 s after. UI stage (166 mutants, about 17 tests per mutant): 3339 s with one worker, 1709 s
  with 3 displays.

What this means:
- A mutation run over a change is minutes, and a whole-file backfill of unit-tested code is feasible.
- The UI stage costs seconds to tens of seconds per surviving mutant, times the UI tests that cover it. A UI stage
  over whole files belongs to a few high-risk files; in change mode it costs minutes. One display is one UI worker,
  which suffices in change mode; a brief that needs a whole-file UI stage hands out several displays.
- No hand break check list remains, except for the accepted gaps (removing an inlined `filter { }`, a `close()`, an
  inline function of ours used only from another package or module).
- gradle-pitest-plugin 1.19.0 calls `Configuration.setVisible`, which Gradle 11 removes. The plugin is applied to
  every Kotlin module, so the upgrade to Gradle 11 breaks every build until the plugin is updated or applied only
  when `-Psluide.pitest.classes` is set.
- `break-check.sh` and its daemon branches in `verify.sh` and `full-build.sh` are removed.
- Guidance: `.claude/rules/testing.md`, `docs/testing.md`, the agent files and the delegate skill describe the new
  flow.

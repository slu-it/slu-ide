# 0189. Mutation runs: the UI stage once per package, after the review

- Status: accepted; amended 2026-11-21 (bounded runs after the review, named files in reruns, Opus for new views)
- Date: 2026-11-19

## Context
[ADR 0185](0185-mutation-testing-with-pitest.md) moved the mutation run to pitest and asked for a full run (unit
stage and UI stage) after the last commit and again after every review fix. The first feature built that way (Paste
Text, 2026-11-19, three packages) took 7 h 10 min, of which about 3 h 20 min were mutation runs:
- The unit stage stayed cheap: 30-100 s for 170-400 mutants in `ide`.
- The UI stage was the cost: 14-20 min per run in `ide` for 21-49 mutants, on one display. The `ide` package ran it
  eight times (before the review, after a requirement change, after each of three review rounds); five of the runs
  ended with the same UI-stage result.
- The same two endless-loop timeouts and three to five uncovered lambdas were reported and argued again in every
  round.
- A hand break check that removed a clipboard read ended in an error dialog and hung for 34 minutes until the user
  asked.

A spike on the final state of that package (`ide`, 21 mutants left for the UI stage, base `70806c059`) measured the
UI stage alone:

| Tests of the UI stage | Displays | Coverage pass | Mutants | Total |
|---|---|---|---|---|
| all of the module's (338 classes) | 1 | 138 s | 627 s | 765 s |
| the package's 9 classes | 1 | 51 s | 362 s | 413 s |
| all of the module's | 4 | 145 s | 236 s | 381 s |
| the package's 9 classes | 4 | 51 s | 133 s | 185 s |

All four runs killed the same mutants and left the same three. Three of the four also ended one mutant with a memory
error (a worker at `-Xmx768m`); the run with the listed classes on four displays did not.

## Decision
This amends ADR 0185 (when the stages run and what the UI stage runs; its rules about survivors, timeouts and
equivalence stay).

- **Unit stage while implementing and in fix rounds.** The implementer's run after the last commit, and after each
  round of review fixes, is `mutation-check.sh --unit-only`. Its report names the mutants left in three groups: killed
  by a sharper test (gone), accepted with a reason (below), and left for the UI stage (code that only a UI test
  reaches). The reviewer reads that output.
- **The UI stage once per package, after the reviewer's "Merge: yes".** The main session then asks the same
  implementer for the one full run. What it still leaves is killed by a test (the full command again shows the kill)
  or accepted; the main session reads that fix diff, and the reviewer gets a short follow-up only when production
  code changed or a new mutant is accepted.
- **Exception: a UI test of existing behavior** (a gap test, moved code; ADR 0185, "Existing behavior"). Its `--whole`
  after-run with the UI stage is the test's red, so it runs while implementing, on the one file, with `--ui-tests
  <the test's class>`.
- **`--ui-tests A,B`** limits the UI stage to the named test classes (simple names; the package's UI test classes
  from its verify list). A module that declares none of them runs all its tests. A kill by a class that was not
  named shows up as a survivor, so the error is on the safe side: add the class or rerun without the option.
- **Four displays per implementer** (`--displays`), and the main session starts the packages' UI-stage runs one at a
  time, so two never share the cores.
- **Accepted mutants.** `mutation-check.sh --accept <path>:<line>[:<Mutator>] <reason>` records the mutants the last
  finished run left on that line in `build/mutation-check/accepted.tsv`, one entry per mutant (path, class, method,
  mutator, description, the line's text, reason). A line whose leftovers have several mutators needs the mutator
  named, so an endless loop's reason cannot cover a real gap next to it. Later runs in that tree report a matching
  mutant as `accepted` with its reason, do not count it as left and do not send it to the UI stage. An entry accepts
  one mutant and stops matching when its line's text changes. Only an equivalence or an endless-loop timeout is
  accepted, with the reason the rules ask for; "a UI test covers it" is no reason. The reviewer checks the file's
  entries first.
- **A hand break check runs under `timeout 600`.** A run the timeout ends is a hang, not a red. The limit is a
  guess with room: one test class through `verify.sh` took under two minutes in that round.

## Consequences
- A package pays for the UI stage once, in about a quarter of the time. The Paste Text round would have taken about
  4.5 hours.
- The first review sees no evidence for lines that only UI tests reach; it comes after the review. A gap found
  there costs one more fix, without a new full review round unless production code changes.
- An accepted mutant is not run again in the UI stage, so a wrong acceptance hides a gap until its line changes. The
  reviewer's check of `accepted.tsv` is the guard.
- `accepted.tsv` and `left.tsv` live in the tree's `build/mutation-check/`; `./gradlew clean` and removing the
  worktree drop them.
- Open: the memory errors of UI workers at 768 MB, and a default JUnit timeout that would turn a hung dialog into a
  failure everywhere.
- Guidance: `.claude/rules/testing.md`, `docs/testing.md`, the agent files and the delegate skill describe the flow.

## Amendment 2026-11-21: bounded runs after the review, named files in reruns, Opus for new views

### Context
The JShell round (ADR 0191, packages P0-P5) took 20 h 20 min from the request to the report. About 11.7 h of its
critical path were mutation runs:
- P1, the engine (about 1,450 mutants, tests that start JShell processes): seven `--unit-only` runs of 37-40 min over
  every file, and two of 20 min that named only the changed files. Its full run after the review took 38 min, and its
  UI stage had 0 mutants.
- P2a (transcript pane) and P2b (`.jsh` view): after the reviewer's "Merge: yes" the full run went 6 and 7 times, 20-29
  min each, always over every file and every listed UI class, each pass killing part of the no-coverage list (P2a 70 →
  20 → 2 → 2 → 3 → 1; P2b 162 → 93 → 62 → 62 → 54, then Opus 5 → 1). That was 2 h 45 min and 4 h 13 min after the
  approval.
- Sonnet failed review twice on both view packages, P2a and P2b, before Opus took over: about 2.5 h of rework.

### Decision
- **Reruns name the files.** The complete `--unit-only` run after the last commit stays. Every later rerun (killing
  its survivors, a fix round) appends the production files it concerns: those the round changed and those whose
  mutants it set out to kill. The implementer works through the whole list before it reruns, not one mutant per run.
- **No full run without UI test classes.** A package whose verify list has no UI test class has no UI stage; its fix
  rounds' `--unit-only` runs are final.
- **The full run once, then one bounded pass.** The implementer handles everything the full run leaves in one pass (a
  test per gap, an acceptance per equivalence or endless loop) and reruns once, naming only the files that had
  leftovers and, in `--ui-tests`, only the classes meant to kill them. What that rerun leaves is not chased in further
  runs: the report lists each mutant with its line and why no test reaches it, the main session sends the list to the
  reviewer's follow-up, and the reviewer either names a test that reaches the line (one more fix, one more named
  rerun) or the mutant goes into the round's final report as a known gap. `accepted.tsv` stays for equivalences and
  endless-loop timeouts only.
- **Opus for new views.** A package whose main work is a new JavaFX view, pane or control (layout, scrolling, focus,
  event timing that its UI tests pin down) starts on Opus. Dialogs, menu entries and wiring into an existing view stay
  on Sonnet.

### Consequences
- Estimated for the JShell round: about 6 h less (P1 about 1 h 20 min, P2a about 2 h, P2b about 3 h).
- A gap left after the bounded pass stays open until someone writes its test. The final report names it, so it is
  visible rather than accepted.
- A rerun that names files does not see a test change that would let a mutant in another file survive. The complete
  run before the review and the full run after it bound that; a package without UI tests relies on the reviewer's
  reading of its fix diffs.
- Guidance: `.claude/rules/testing.md`, `docs/testing.md`, the implementer and reviewer agent files, the delegate skill
  and the header of `mutation-check.sh`.

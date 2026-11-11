---
name: delegate
description: Use before handing implementation work in Thoreau to subagents, especially several packages in parallel worktrees. Covers package size, model choice and escalation, briefs, the triaged review loop, real-app checks and the merge gate.
---

# Delegating work to subagents

## Who does what
- One file, a few lines: do it yourself, no subagent.
- Otherwise the main session plans and writes the brief, `implementer` (Sonnet) builds and writes its tests,
  `reviewer` (Opus) checks every change before it is merged or reported done.
- Research across the code goes to `explorer` (Sonnet): its brief states numbered questions, what the answer feeds
  into (the plan, a brief, a bug hunt) and how thorough to be. Keep file dumps out of the main context. The built-in
  `Explore` and `Plan` agents run on the main model and are refused by a hook (`.claude/scripts/agent-type-hook.sh`):
  plan in the main session.
- Subagents do not spawn further agents.

## Packages and model
Split first, escalate second. A package is about one module and a handful of files, with the signatures other
packages rely on frozen in the plan. "A whole feature", "UI work" and "many files" are reasons to split, not to
escalate.

Pass `model: "opus"` to an `implementer` only when one of these applies, and put it in the Agent description as
`[opus: <reason>]` (e.g. `[opus: concurrency] P2 pull backend`):
- the foundation package of a round: a cross-module API or the core of a new ADR that later packages build on;
- threading or concurrency, terminal, file-watcher or git process internals;
- a Sonnet implementer stopped on a design gap that a better brief cannot close;
- a Sonnet attempt failed review twice.

Prefer fixing the brief over escalating: a stop-and-report usually means the plan was incomplete. The round's final
report to the user gives the number of Sonnet and Opus implementer runs.

## Brief
Sonnet does well with a precise brief and guesses without one. Every implementer brief states:
1. Goal and acceptance criteria (observable behavior). Designed content (levels, holes, puzzles) gets measurable
   targets, not "make it hard": Mini Golf's first holes came back trivially solvable until the brief named a limit on
   shots from the tee that hole in one, measured over the search grid, plus a required look at a render.
2. Files and classes to touch; anything outside them needs a stop-and-report.
3. Signatures and behavior of new or changed API, including threading (FX thread or not).
4. Tests to add or change, with the level per behavior (`docs/testing.md`, "Choosing the test level"): unit, naming
   the function or class to extract when the logic sits in a control, or `@Tag("ui")` with the reason it needs a
   scene, plus the one wiring test an extraction keeps. Name the existing test classes that already cover
   neighbouring behavior, to extend rather than duplicate. Then the test classes to run, by class name:
   `verify.sh --changed` already runs the touched modules' unit tests and every class
   `.claude/scripts/related-tests.sh` finds for the changed files, so name only what that word search misses (a
   behavior tested through another class, `:ide`'s `SpringWiringTest` when an API changes). Run
   `related-tests.sh <files the package will change>` while planning: more than 12 UI classes means the
   implementer must name classes instead of using `--changed`; say which. Tests that depend on the OS
   (files, processes, timing, encodings): point at `docs/testing.md`, "Portability".
   Name in the brief, because agents skip what it doesn't name (`.claude/rules/testing.md`, `safety.md`): the break
   check of every new guard, wiring and race; the paint check of what the user sees (`docs/testing.md`, "WebView
   pages"); the hostile inputs for untrusted content; fixtures built with the real builder of the output they fake.
   `related-tests.sh` finds tests by Kotlin names: it misses tests of a changed resource (`.js`, `.css`) that do not
   name it and consumers in other modules that use the API without naming the changed file; name those classes.
5. Docs to update: feature page, ADR, `docs/index.md`; `docs/features/keyboard-shortcuts.md` when key bindings change.
6. Docs to read (so the agent skips the index), with sections where useful; for UI work the matching sections of
   `docs/ui-conventions.md`.
7. Base: the master commit (for `worktree-check.sh start`) and the agent's display (`:111` upwards; the final gate's
   pool is `:99` to `:106`), or the worktree and the commit to rebase onto.
8. For large files, the classes or line ranges that matter, so the agent reads ranges instead of whole files.
9. The interactions the plan decided (`feature` skill, "Interactions"): offset spaces of the hooks, what counts as
   the user's action, key and focus ownership, dispose and reload. A package that consumes another's hooks repeats
   them in its brief, with the parts that hold only in one mode (e.g. page-local offsets in chapter mode).

Take the base commit from `git rev-parse master` right before sending the brief: the user squashes and the main
session amends, and an agent sent to a commit no longer on master stops at once. An agent whose worktree stayed
unchanged may find it removed when it resumes; tell it to recreate it from the current master, never to work in the
main tree.

The sandbox rules every implementer needs (`worktree-check.sh` start and report, `verify.sh`, `--no-daemon`, no
`pgrep -f` loops, MainWindow bean lists) are in `.claude/agents/implementer.md`; briefs need not repeat them, only
name the master commit and the display (item 7).

## Review loop
The reviewer labels each finding Blocker, Should or Nit (definitions in `.claude/agents/reviewer.md`).
- Blockers and Shoulds go back to the same implementer with SendMessage (it keeps its context).
- A re-review only when a Blocker was fixed; for fixed Shoulds the main session reads the fix diff itself.
- Nits: the main session fixes the ones worth a line itself, or drops them; the final report names what was dropped.
  Nits never start another implementer round.
- Squash review fixes non-interactively: `GIT_SEQUENCE_EDITOR=: git rebase -i --autosquash <base>`.

Before the main session decides a behavior change (a "bug" an agent or audit found), it checks the declared
`@Throws`, the interface contract and every caller: what looks like a missing catch is often handled one level up.
The same goes for a fix the main session itself suggests in a review message: read the call order first.
Ask for exact fixes, not heuristics (time windows, text searches for known prefixes, regexes over raw markup each
came back from review once); when a review names a heuristic fix, say which exact one to build. A fix that replaces
a mechanism updates the ADR that described it.

After the last wave, before the final gate, one reviewer reads the round's whole diff for what no package review could
see: problems across packages (a search jump that Read Aloud undid, an empty chapter between two packages' fixtures).
Give it the code and docs, not the round's conclusions.
A test an audit or brief suggests can be vacuous; ask for it to be shown failing with the code broken.

## Real-app check (done criteria for UI work)
A round that changes what the user sees is done only after the main session ran the real app:
`.claude/scripts/run-app.sh <folder>` (dark theme, the default; `--theme light` as well when theme CSS or colours
changed), screenshots with `import -window root`, looked at with Read. The report says what was checked and what was
not (macOS-only behavior, Podman, ...). Keep long outputs (logs, xdotool searches) in scratchpad files and read only
what matters, to save the main context. `--stop <pid>` afterwards.
Run it after each merged package that changes what the user sees, before the next wave builds on it, not only at
the end (faults left for the last look needed fix packages after the gate). `run-app.sh` builds the jar in the main
tree and defaults to display `:99`, the gate's: never run it while the gate or another Gradle build runs there, and
pass `--display 107` or higher. Drive the feature end to end with real data where the sandbox allows it (a real model
linked into the throwaway home, the null ALSA device, `docs/testing.md`), plus one real-world input beyond the
generated fixtures when the feature reads files from outside (a store or Gutenberg book, a real-life document).

When the main session fixes something itself:
- Read a break check's result as a pass or fail of its own. Spotless reformats code after an edit, so a marker or a
  `sed` restore can stop matching: check the restored line with `grep` before committing.
- Never pipe `verify.sh` into `tail` in a command chain that commits: the pipe hides the failing exit code.
- Remove debug output in the same edit that added it, and check with `git diff` that none is left.

## Final gate
Once the reviewer reports no Blockers and no agent runs Gradle in the main tree any more, the main session runs
`.claude/scripts/full-build.sh` in the background: it repairs stale or hung displays (`:99` upwards, one per parallel
`uiTest`), refuses while another Gradle build runs in the tree, then runs `./gradlew build -q`,
`.claude/scripts/check-docs-index.sh` and `check-unused-imports.sh`. Never start the gate while an implementer still
edits or builds in the main tree: both share `build/`, and classes vanish under running tests (fake
`NoClassDefFoundError`). It fixes failures itself instead of starting another implementer round: rerun the failing class
with `.claude/scripts/verify.sh <Class>` (digest instead of full stack traces), fix the cause (never by weakening the
test), fold the fix into the matching commit (`fixup!` + autosquash). A fix that touches production code gets a short
reviewer pass on that diff; then run the gate again. Hand back to the implementer only when the failure turns out to be
a design problem, not a local fix.
- "Unable to open DISPLAY": a display broke during the run; rerun the script once, which repairs it. If it fails the
  same way again, investigate (`pgrep -a Xvfb`, `xdpyinfo -display :99`).
- A uiTest that dies within a second with a `Build_gradle$DisplayService` cast error: `./gradlew --stop`, then rerun
  (a plain rerun can fail again; daemons are shared with worktrees).
- To measure build or test times, pass `--rerun-tasks`: the build cache reuses results of worktree runs, and
  `.claude/scripts/test-times.sh` reads the times afterwards.

## Parallel packages
- At most three worktree agents at a time (12 cores, about 6 GB free). Count every implementer still running,
  including one applying review fixes, before starting the next package.
- Packages are pairwise disjoint on files, except that each package adds its own rows to `docs/index.md` (its ADR,
  its feature page) and its own module and dependency rows to `docs/architecture.md`: deferring them leaves master's
  docs check red between merges. README and cross-cutting rewrites go into a final package. Freeze shared constructor
  signatures up front, and name one owner for shared test fixtures (fakes, harnesses): two packages that each added a
  caret to the same fake merged without a textual conflict into duplicate members.
- When several packages add entries to the same registry or list (file types, menus, a doc table), the brief names
  each package's anchor ("right after the Bicep rows"), so the inserts don't touch. Rebase conflicts that remain in
  imports or such lists are resolved by keeping both sides, dropping exact duplicates.
- Worktrees (`isolation: "worktree"`) start at the session's first commit, not current master. The implementer
  definition makes the agent reset or rebase onto master and check its diff; name the master commit in the brief.
- Each agent gets its own Xvfb display (`:111` upwards, above the final gate's pool) and its own scratchpad subfolder,
  and kills only processes it started.

## Merge gate (per package, on master)
1. `.claude/scripts/merge-package.sh <worktree, branch or agent id> --expect <the package's paths>`, one call per
   package (several in one Bash call are fine). It refuses while the worktree is dirty or Gradle runs in it, prints the
   commits and the three-dot stat, stops on files outside `--expect` (a stale tree once silently reverted master's
   newest commits), rebases when master moved and then compiles the touched modules, and fast-forwards master. Exit 1 is
   a check that failed: unexpected files, or a compile error after the rebase (the branch is reset to its pre-rebase
   head; the fix goes back to the implementer, who runs `worktree-check.sh start` again first). Exit 3 is a rebase
   conflict, already aborted: run `git -C <worktree> rebase master` yourself, resolve (keep both sides of lists and
   imports), `--continue`, compile there (`./gradlew --no-daemon <:module>:testClasses -q`; the script won't, since it
   did not rebase), then run the script again. After resolving conflicts in shared code or fixtures, compile every
   commit of the branch, not only the tip: `for c in $(git rev-list --reverse master..HEAD); do git checkout -q $c;
   ./gradlew --no-daemon <:module>:testClasses -q >/dev/null 2>&1; echo "$c $?"; done` (read `$?` right after
   Gradle, not after a command substitution), then check out the branch again.
2. The final gate on master (above). With `DISPLAY` set, `build` already includes `uiTest`.
3. Only after the review is through (no Blockers or Shoulds left) and the gate is green:
   `merge-package.sh --remove <worktree>` (refuses unmerged branches); merged worktrees are not removed
   automatically. An agent whose worktree is gone cannot be resumed with SendMessage, so removing it early turns a
   small fix into a new agent with a new brief.

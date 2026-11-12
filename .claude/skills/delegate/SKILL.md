---
name: delegate
description: Use before handing implementation work in Thoreau to subagents, especially several packages in parallel worktrees. Covers package size, model choice and escalation, briefs, the triaged review loop, real-app checks and the merge gate.
---

# Delegating work to subagents

Who does what is in the project `CLAUDE.md` ("Delegation"). This skill is the process around it; what an agent does
inside its role is in `.claude/agents/`, the rules for code and tests in `.claude/rules/`. Briefs point there instead
of repeating them.

One file, a few lines: do it yourself, no subagent.

## Order of a round
1. Plan the packages (below); for a feature, the `feature` skill writes the plan.
2. Per package: brief → `implementer` in a worktree → `reviewer` → fixes by the same implementer → merge.
3. After each merged package that changes what the user sees: real-app check, before the next wave builds on it.
4. After the last wave: one cross-package review, the final gate, a last real-app check, then remove the worktrees.

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

## Displays
One place for the numbers; briefs hand them out.

| Display | Used by |
|---|---|
| `:99` to `:106` | the final gate's pool (`full-build.sh`); `:99` is also `verify.sh`'s default in the main session |
| `:107` | `run-app.sh` (real-app check) |
| `:111` upwards | one per running agent, implementer or reviewer, named in its brief |

## Explorer brief
Numbered questions, what the answer feeds into (the plan, a brief, a bug hunt) and how thorough to be.

## Implementer brief
Sonnet does well with a precise brief and guesses without one. Every implementer brief states:
1. Goal and acceptance criteria (observable behavior). Designed content (levels, puzzles) gets measurable targets
   and a required look at a render, not "make it hard".
2. Files and classes to touch; anything outside them needs a stop-and-report.
3. Signatures and behavior of new or changed API, including threading (FX thread or not).
4. Tests, with the level per behavior (`docs/testing.md`, "Choosing the test level"): unit, naming the function or
   class to extract when the logic sits in a control, or `@Tag("ui")` with the reason it needs a scene, plus the one
   wiring test an extraction keeps. Name the existing test classes that cover neighbouring behavior, to extend
   rather than duplicate. Agents skip what a brief does not name, so name: the break check of every new guard,
   wiring and race; the paint check of what the user sees; the hostile inputs for untrusted content; fixtures built
   with the real builder of the output they fake; `docs/testing.md`, "Portability" for tests that depend on the OS.
5. Test classes to run. `verify.sh --changed` already runs the touched modules' unit tests and every class
   `.claude/scripts/related-tests.sh` finds for the changed files, so name only what that search cannot see: a
   behavior tested through another class, consumers in other modules, `:ide`'s `SpringWiringTest` when an API
   changes, and the names it reports as skipped. Run `related-tests.sh <files the package will change>` while
   planning; when `verify.sh --changed` would refuse (too many UI classes), give the class list instead.
6. Docs to update: feature page, ADR, `docs/index.md`; `docs/features/keyboard-shortcuts.md` when key bindings change.
7. Docs to read (so the agent skips the index), with sections where useful; for UI work the matching sections of
   `docs/ui-conventions.md`. For large files, the classes or line ranges that matter.
8. Base and display: the master commit for `worktree-check.sh start` and the agent's display.
9. The interactions the plan decided (`feature` skill, "Plan file"), repeated in the brief of every package that
   provides or consumes a hook, with the parts that hold only in one mode.

Take the base commit from `git rev-parse master` right before sending the brief: the user squashes and the main
session amends. An agent whose worktree stayed unchanged may find it removed when it resumes; send a new agent with
a new brief instead.

## Reviewer brief
- What to review: `git diff master...<branch>` in the package's worktree (path and branch from the implementer's
  report), or a commit range on master.
- The implementer's brief or the plan section, and the implementer's report (test commands, break checks).
- A display of its own, if it may confirm a suspected failure with a test.
- Not the main session's conclusions: give it the code and docs to judge.

## Review loop
The reviewer labels each finding Blocker, Should or Nit (definitions in `.claude/agents/reviewer.md`).
- Blockers and Shoulds go back to the same implementer with SendMessage (it keeps its context).
- A re-review only when a Blocker was fixed; for fixed Shoulds the main session reads the fix diff itself.
- Nits: the main session fixes the ones worth a line itself, or drops them; the final report names what was dropped.
  Nits never start another implementer round.
- Squash review fixes non-interactively: `GIT_SEQUENCE_EDITOR=: git rebase -i --autosquash <base>`.
- The package is merged once no Blocker or Should is left.

Before the main session decides a behavior change (a "bug" an agent or audit found) or suggests a fix in a review
message, it checks the declared `@Throws`, the interface contract, every caller and the call order: what looks like
a missing catch is often handled one level up. When a review names a heuristic fix, say which exact rule to build.
A test an audit or brief suggests can be vacuous; ask for it to be shown failing with the code broken.

After the last wave, before the final gate, one reviewer reads the round's whole diff for what no package review
could see: problems across packages (one package undoing another's effect, fixtures of two packages that disagree).

## Merge (per package, after its review)
`.claude/scripts/merge-package.sh <worktree, branch or agent id> --expect <the package's paths>`, one call per
package. Its header describes the checks and exit codes. In short: it refuses while the worktree has uncommitted or
untracked files or a Gradle build runs in the worktree or the main tree, stops on files outside `--expect`, rebases
when master moved and then compiles the touched modules, and fast-forwards master. The worktree stays.
- Exit 1, a check failed: unexpected files, or a compile error after the rebase (the branch is back at its
  pre-rebase head). The fix goes to the implementer, who runs `worktree-check.sh start` again first.
- Exit 3, a rebase conflict, already aborted: run `git -C <worktree> rebase master` yourself, resolve (keep both
  sides of lists and imports, drop exact duplicates), `--continue`, compile there (`./gradlew --no-daemon
  <:module>:testClasses -q`), then run the script again. After resolving conflicts in shared code or fixtures,
  compile every commit of the branch, not only the tip, since each commit must compile on its own.

## Real-app check (done criteria for UI work)
A round that changes what the user sees is done only after the main session ran the real app, after each merged
package that changes what the user sees and once more at the end:
- `.claude/scripts/run-app.sh <folder>` in the dark theme (the default), and `--theme light` as well when theme CSS
  or colours changed; screenshots with `import -window root`, looked at with Read; `--stop <pid>` afterwards.
- It builds the jar in the main tree: never run it while the gate or another Gradle build runs there.
- Drive the feature end to end with real data where the sandbox allows it (`docs/testing.md`, "Running and
  screenshotting the real app"), plus one real-world input beyond the generated fixtures when the feature reads
  files from outside.
- Keep long outputs (logs, xdotool searches) in scratchpad files and read only what matters.
- The report says what was checked and what was not (macOS-only behavior, Podman, ...).

## Final gate
Once the cross-package review has no Blockers and no agent runs Gradle in the main tree, the main session runs
`.claude/scripts/full-build.sh` in the background (what it runs and checks: its header). Never start it while an
implementer still edits or builds in the main tree: both share `build/`.

The main session fixes gate failures itself instead of starting another implementer round: rerun the failing class
with `.claude/scripts/verify.sh <Class>`, fix the cause (never by weakening the test), fold the fix into the matching
commit (`fixup!` + autosquash). A fix that touches production code gets a short reviewer pass on that diff; then run
the gate again. Hand back to the implementer only when the failure turns out to be a design problem.
- "Unable to open DISPLAY": a display broke during the run; rerun the script once, which repairs it. If it fails the
  same way again, investigate (`pgrep -a Xvfb`, `xdpyinfo -display :99`).
- A uiTest that dies within a second with a `Build_gradle$DisplayService` cast error: `./gradlew --stop` once no
  other Gradle build runs, then rerun.
- To measure build or test times, pass `--rerun-tasks` (the build cache reuses results of worktree runs);
  `.claude/scripts/test-times.sh` reads the times afterwards.

When the main session fixes something itself:
- Read a break check's result as a pass or fail of its own. Spotless reformats code after an edit, so a marker or a
  `sed` restore can stop matching: check the restored line with `grep` before committing.
- Never pipe `verify.sh` into `tail` in a command chain that commits: the pipe hides the failing exit code.
- Remove debug output in the same edit that added it, and check with `git diff` that none is left.

Only after the gate is green: `merge-package.sh --remove <worktree>` for each package (it refuses unmerged
branches). An agent whose worktree is gone cannot be resumed with SendMessage, so removing it early turns a small
fix into a new agent with a new brief.

## Parallel packages
- At most three worktree agents at a time. Count every implementer still running, including one applying review
  fixes, before starting the next package.
- Packages are pairwise disjoint on files, except that each package adds its own rows to `docs/index.md` (its ADR,
  its feature page) and its own module and dependency rows to `docs/architecture.md`: deferring them leaves master's
  docs check red between merges. README and cross-cutting rewrites go into a final package.
- Freeze shared constructor signatures up front, and name one owner for shared test fixtures (fakes, harnesses):
  two packages that each extend the same fake can merge without a textual conflict into duplicate members.
- When several packages add entries to the same registry or list (file types, menus, a doc table), the brief names
  each package's anchor ("right after the Bicep rows"), so the inserts don't touch.
- Worktrees (`isolation: "worktree"`) start at the session's first commit, not current master; `worktree-check.sh
  start` brings them onto master.
- Each agent gets its own display and its own scratchpad subfolder, and kills only processes it started.

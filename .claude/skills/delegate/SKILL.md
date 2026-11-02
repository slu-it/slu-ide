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
1. Goal and acceptance criteria (observable behavior).
2. Files and classes to touch; anything outside them needs a stop-and-report.
3. Signatures and behavior of new or changed API, including threading (FX thread or not).
4. Tests to add or change, with the level per behavior (`docs/testing.md`, "Choosing the test level"): unit, naming
   the function or class to extract when the logic sits in a control, or `@Tag("ui")` with the reason it needs a
   scene, plus the one wiring test an extraction keeps. Name the existing test classes that already cover
   neighbouring behavior, to extend rather than duplicate. Then the test classes to run, by class name: the related
   UI test classes and, when an API changes, unit tests of dependent modules (e.g. `:ide`'s `SpringWiringTest`).
   The implementer runs only those, its own and the touched modules' unit tests. Tests that depend on the OS
   (files, processes, timing, encodings): point at `docs/testing.md`, "Portability".
5. Docs to update: feature page, ADR, `docs/index.md`; `docs/features/keyboard-shortcuts.md` when key bindings change.
6. Docs to read (so the agent skips the index), with sections where useful.
7. Base: master, or the worktree and the commit to rebase onto.
8. For large files, the classes or line ranges that matter, so the agent reads ranges instead of whole files.

The sandbox rules every implementer needs (worktree base and diff check, `--no-daemon`, no `pgrep -f` loops,
MainWindow bean lists) are in `.claude/agents/implementer.md`; briefs need not repeat them.

## Review loop
The reviewer labels each finding Blocker, Should or Nit (definitions in `.claude/agents/reviewer.md`).
- Blockers and Shoulds go back to the same implementer with SendMessage (it keeps its context).
- A re-review only when a Blocker was fixed; for fixed Shoulds the main session reads the fix diff itself.
- Nits: the main session fixes the ones worth a line itself, or drops them; the final report names what was dropped.
  Nits never start another implementer round.
- Squash review fixes non-interactively: `GIT_SEQUENCE_EDITOR=: git rebase -i --autosquash <base>`.

Before the main session decides a behavior change (a "bug" an agent or audit found), it checks the declared
`@Throws`, the interface contract and every caller: what looks like a missing catch is often handled one level up.
A test an audit or brief suggests can be vacuous; ask for it to be shown failing with the code broken.

## Real-app check (done criteria for UI work)
A round that changes what the user sees is done only after the main session ran the real app:
`.claude/scripts/run-app.sh <folder>` (dark theme, the default; `--theme light` as well when theme CSS or colours
changed), screenshots with `import -window root`, looked at with Read. The report says what was checked and what was
not (macOS-only behavior, Podman, ...). Keep long outputs (logs, xdotool searches) in scratchpad files and read only
what matters, to save the main context. `--stop <pid>` afterwards.

## Final gate
Once the reviewer reports no Blockers and no agent runs Gradle in the main tree any more, the main session runs
`.claude/scripts/full-build.sh` in the background: it repairs stale or hung displays (`:99` upwards, one per parallel
`uiTest`), refuses while another Gradle build runs in the tree, then runs `./gradlew build -q`,
`.claude/scripts/check-docs-index.sh` and `check-unused-imports.sh`. Never start the gate while an implementer still
edits or builds in the main tree: both share `build/`, and classes vanish under running tests (fake
`NoClassDefFoundError`). It fixes failures itself instead of starting another implementer round: rerun the failing
class with `--tests`, fix the cause (never by weakening the test), fold the fix into the matching commit (`fixup!` +
autosquash). A fix that touches production code gets a short reviewer pass on that diff; then run the gate again.
Hand back to the implementer only when the failure turns out to be a design problem, not a local fix.
- "Unable to open DISPLAY": a display broke during the run; rerun the script once, which repairs it. If it fails the
  same way again, investigate (`pgrep -a Xvfb`, `xdpyinfo -display :99`).
- A uiTest that dies within a second with a `Build_gradle$DisplayService` cast error: `./gradlew --stop`, then rerun
  (a plain rerun can fail again; daemons are shared with worktrees).
- To measure build or test times, pass `--rerun-tasks`: the build cache reuses results of worktree runs, and
  `.claude/scripts/test-times.sh` reads the times afterwards.

## Parallel packages
- At most three worktree agents at a time (12 cores, about 6 GB free). Count every implementer still running,
  including one applying review fixes, before starting the next package.
- Packages are pairwise disjoint on files; `docs/index.md`, README and `docs/architecture.md` go into a final
  package that collects each package's "index drift" notes. Freeze shared constructor signatures up front.
- When several packages add entries to the same registry or list (file types, menus, a doc table), the brief names
  each package's anchor ("right after the Bicep rows"), so the inserts don't touch. Rebase conflicts that remain in
  imports or such lists are resolved by keeping both sides, dropping exact duplicates.
- Worktrees (`isolation: "worktree"`) start at the session's first commit, not current master. The implementer
  definition makes the agent reset or rebase onto master and check its diff; name the master commit in the brief.
- Each agent gets its own Xvfb display (`.claude/scripts/setup-display.sh 10N`) and its own scratchpad subfolder,
  and kills only processes it started.

## Merge gate (per package, on master)
1. Check `git diff master...<branch> --stat` (three dots: from the merge base): only the package's files (a stale
   tree once silently reverted master's newest commits).
2. In the worktree: `git rebase master`; when master moved since the agent's last test run, compile there
   (`./gradlew --no-daemon :<module>:testClasses -q`); on master: `git merge --ff-only <branch>`.
3. The final gate on master (above). With `DISPLAY` set, `build` already includes `uiTest`.
4. Only after the review is through (no Blockers or Shoulds left) and the gate is green: `git worktree remove --force
   <path>` and delete the branch; merged worktrees are not removed automatically. An agent whose worktree is gone
   cannot be resumed with SendMessage, so removing it early turns a small fix into a new agent with a new brief.

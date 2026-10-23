---
name: delegate
description: Use before handing implementation or test work in Thoreau to subagents, especially several packages in parallel worktrees. Covers briefs, model choice and escalation, the review loop and the merge gate.
---

# Delegating work to subagents

## Who does what
- One file, a few lines: do it yourself, no subagent.
- Otherwise the main session plans and writes the brief, `implementer` (Sonnet) builds, `tester` (Sonnet) adds tests
  when that is a separate step, `reviewer` (Opus) checks every change before it is merged or reported done.
- Wide searches go to `explorer` (Haiku); keep file dumps out of the main context.
- Subagents do not spawn further agents.

## Brief
Sonnet does well with a precise brief and guesses without one. Every implementer brief states:
1. Goal and acceptance criteria (observable behavior).
2. Files and classes to touch; anything outside them needs a stop-and-report.
3. Signatures and behavior of new or changed API, including threading (FX thread or not).
4. Tests to add or change, unit or `@Tag("ui")`, and the existing test classes to run, by class name: the related
   UI test classes and, when an API changes, unit tests of dependent modules (e.g. `:ide`'s `SpringWiringTest`).
   The implementer runs only those, its own and the touched modules' unit tests.
5. Docs to update: feature page, ADR, `docs/index.md`; `docs/features/keyboard-shortcuts.md` when key bindings change.
6. Docs to read (so the agent skips the 17 KB index), with sections where useful.
7. Base: master, or the worktree and the commit to rebase onto.
8. For large files, the classes or line ranges that matter, so the agent reads ranges instead of whole files.

## Escalate to Opus
Pass `model: "opus"` to the `implementer` call when the task is:
- a cross-module API or needs a new ADR;
- threading or concurrency, terminal or file-watcher internals;
- one where a Sonnet implementer stopped because the brief was ambiguous and the gap is a design question;
- one where a Sonnet attempt failed review twice.

Prefer fixing the brief over escalating: a stop-and-report usually means the plan was incomplete.

## Review loop
Send reviewer findings back to the same implementer with SendMessage (it keeps its context), then review again.
Squash review fixes non-interactively: `GIT_SEQUENCE_EDITOR=: git rebase -i --autosquash <base>`.
Subagents run only targeted tests; the full build is the final gate.

## Final gate
Once the reviewer reports no issues and no agent runs Gradle in the main tree any more, the main session runs
`.claude/scripts/full-build.sh` in the background: it repairs stale or hung displays (`:99` upwards, one per parallel `uiTest`), refuses while another
Gradle build runs in the tree, then runs `./gradlew build -q` and `.claude/scripts/check-docs-index.sh`. It fixes failures
itself instead of starting another implementer round: rerun the failing class with `--tests`, fix the cause (never by weakening the test), fold the fix
into the matching commit (`fixup!` + autosquash). A fix that touches production code gets a short reviewer pass on
that diff; then run the gate again. Hand back to the implementer only when the failure turns out to be a design
problem, not a local fix. "Unable to open DISPLAY" means a display broke during the run (the script checked it
before): rerun the script once, which repairs the display; if it fails the same way again, stop and investigate
(`pgrep -a Xvfb`, `xdpyinfo -display :99`).

## Parallel packages
- At most three worktree agents at a time (12 cores, about 6 GB free).
- Packages are pairwise disjoint on files; `docs/index.md`, README and `docs/architecture.md` go into a final
  package that collects each package's "index drift" notes. Freeze shared constructor signatures up front.
- Worktrees (`isolation: "worktree"`) start at the session's first commit, not current master: the brief tells the
  agent to `git rebase master` (or `git reset --hard master` before its first commit) and confirm with
  `git merge-base --is-ancestor master HEAD`.
- Each agent gets its own Xvfb display (`.claude/scripts/setup-display.sh 10N`) and its own scratchpad subfolder,
  and kills only processes it started.

## Merge gate (per package, on master)
1. In the worktree: `git rebase master`; on master: `git merge --ff-only <branch>`.
2. The final gate on master (above). With `DISPLAY` set, `build` already includes `uiTest`.
3. `git worktree remove --force <path>` and delete the branch; merged worktrees are not removed automatically.

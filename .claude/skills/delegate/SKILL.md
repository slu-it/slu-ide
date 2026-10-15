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
4. Tests to add or change, unit or `@Tag("ui")`.
5. Docs to update: feature page, ADR, `docs/index.md`.
6. Docs to read (so the agent skips the 17 KB index), with sections where useful.
7. Base: master, or the worktree and the commit to rebase onto.

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
2. `./gradlew build`, `DISPLAY=:99 ./gradlew uiTest --rerun`, `.claude/scripts/check-docs-index.sh`.
   Known flake: about one full `uiTest` run in ten fails one module with "Unable to open DISPLAY"; rerun once.
3. `git worktree remove --force <path>` and delete the branch; merged worktrees are not removed automatically.

---
name: feature
description: Use when the user describes a new Thoreau feature or a bigger change to plan (often vague, "grill me", "plan the next integration"). Plans in plan mode by asking about the details until the spec is closed, suggests extras, writes a plan of Sonnet-sized packages, then builds it with the delegate skill.
---

# Planning and building a feature

The user usually has a rough idea and wants to be asked about the details instead of having them guessed. Ask; don't
decide what is theirs to decide. Decide yourself what follows from the code, the ADRs or an obvious default, and say so.

## 1. Plan mode
Enter plan mode (`EnterPlanMode`) unless the session already is in it. Nothing but the plan file changes until the
user approves.

## 2. Orient
- Read `docs/ui-conventions.md` (and `.claude/rules/safety.md` when the feature touches files, runs commands or
  handles secrets), then the `docs/index.md` rows that match and only the ADRs and feature pages they point to.
- Wide research goes to `explorer`, with numbered questions, what the answer feeds into and how thorough to be;
  read the few files that matter yourself.
- Look for what can be reused: a similar view, tool, dialog or runner the feature should behave like (the user often
  says "like the shell scripts" or "like the Codecs tool"; read that one).

## 3. Suggest
When the user asks for ideas or the request is open ("any other formats?", "what else would help?"), offer a short
list of concrete extras with one line each, marked as optional. Extras the user wants to keep for later go to
`docs/ideas/`.

## 4. Ask until nothing is open
Use AskUserQuestion in rounds of up to four questions, each with 2-4 concrete options, the recommended one first.
Questions stand on their own (name the thing asked about). Go through what applies:
- behavior and corner cases: empty, huge, invalid, unsaved, concurrent, missing tools, errors shown how;
- layout and defaults: where it lives, what is selected or expanded first, labels, dark theme look;
- entry points: menus, context menus, explorer, editor, toolbar buttons;
- key bindings: check `docs/features/keyboard-shortcuts.md` for free keys first;
- persistence: nothing, per window, per project (`~/.sluide/projects`), shared (`~/.sluide/shared.json`), settings;
- platforms: macOS behavior (the user's platform), Linux;
- naming in the UI, and what is explicitly out of scope. A game named after a specific title (2048, Space Invaders)
  gets its own name and look; briefs give no dimensions or shapes taken from the original (Orbit Guard's first
  sprites used the classic sprite widths and were rejected in review as look-alikes).
Stop when the answers leave no design question. A follow-up round is fine; asking what the code already answers is not.

## 5. Plan file
Write the plan to the plan file plan mode names:
- **Context**: the user's request in their words, the problem it solves.
- **Decisions**: every answer from the rounds, as the spec; out-of-scope items and where they go.
- **Packages**: split per the `delegate` skill (about one module and a few files each, frozen signatures between
  them); per package the model (`[opus: <reason>]` only by the delegate checklist), files, test levels, docs (feature
  page, ADR, index, keyboard shortcuts).
- **Interactions**: frozen signatures are not enough; decide how the packages behave together, or reviews find it
  one round at a time (the ebook round lost about six review rounds this way). For every hook between packages state:
  the coordinate or offset space of each value (whole document or the shown part, source or rendered, local or
  scene); which actions count as the user's (scroll, navigation, click) and which are the app's own, and who is told
  about each; who owns which keys and the focus when two packages add controls to one view (a text field must keep
  Space and Escape); the lifecycle (who stops background work on dispose, what survives a reload). Add the hooks
  these need (a user-scroll list, a navigation list, `onDispose`) to the frozen signatures up front.
- **Order**: what runs in parallel worktrees (at most three), what waits.
- **Verification**: targeted tests per package, the final gate, a real-app check with `run-app.sh` in the dark
  theme after EACH package that changes what the user sees (not only at the end: highlight ghosts and a squeezed
  toolbar were found two waves late), what cannot be checked in the sandbox.
- **Network**: a feature that downloads (models, packages) names every host the sandbox must allow, redirect targets
  included (Hugging Face files redirect to `us.aws.cdn.hf.co`), and asks the user once before the round starts. The
  real-model or real-service check runs right after the engine package, before packages build on its assumptions.
Then `ExitPlanMode`.

## 6. Round note
After approval, write one project memory note for the round: the plan file path, the decisions, and a progress line
updated after each package, so a later session can continue. A session that resumes a round first checks in
`git log` which packages have landed (the note can lag behind), and does not ask again what the note or the plan
file already decided. When the round is done:
- lessons for agents go into `.claude/agents/`, `.claude/skills/` or `docs/testing.md`, not into memory;
- open or unverified items go into the `open-follow-ups` memory note;
- delete the round note and its `MEMORY.md` line. ADRs, feature pages, the plan file and git keep the rest.

## 7. Build
Load the `delegate` skill and run the packages. Finish with the final gate and the real-app check, and report: what
was built, the commits, Sonnet/Opus implementer counts, review Nits dropped, and what was not verified.

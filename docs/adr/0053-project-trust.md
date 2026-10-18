# 0053. Project trust: tools check what a folder would make them run, the user decides once

- Status: accepted
- Date: 2026-10-18

## Context
Thoreau runs tools on its own in an opened folder. Git is one: `git status` runs on open and on every change, and the
editor's diff runs `cat-file --filters`. Through its own configuration, a repository can make git run commands:
- a `core.fsmonitor` hook, which [ADR 0052](0052-hardening-how-git-is-started.md) keeps from running on reads;
- clean and smudge filters on status and diff;
- hooks and `core.sshCommand` on writes.

`git clone` copies neither `.git/config` nor hooks, so a cloned repository carries none of this. A folder unpacked from
an archive or copied from elsewhere does. Git's `safe.directory` refuses only repositories owned by another user. More
tools that read a folder's contents may come, and they will have the same problem.

VS Code asks about every folder it opens for the first time ("workspace trust"). Its tasks, extensions and debuggers
take commands from tracked files, so a clone can carry them there. Asking about every clone would teach users to trust
without reading.

## Decision
- **Check first, ask only about what was found.** A new foundation module, `project-trust`, holds the extension point.
  - Every tool that runs commands on its own implements `ProjectTrustCheck` as a Spring bean. It has an `id` (`git`), a
    `name` (`Git`), and `check(folder)`, which returns `TrustFinding`s or `null` if it could not check. A finding has:
    - a text: what it is;
    - a note: when it runs;
    - a fingerprint: what identifies it, changing when what would run changes.
  - The window-scoped `ProjectTrust` gets every such bean as a `List` (Spring collection injection). `open(folder)` runs
    all checks on a thread of its own (`sluide-trust`) and combines them into one state and one fingerprint. Opening
    another folder cancels the checks still running. The fingerprint is SHA-256 over the sorted SHA-256s of the
    findings, each with its check's id, hashed one by one so that no finding's text can pass for two:
    - a check that could not check counts as a finding of its own;
    - a check that found nothing adds nothing, so adding a tool that finds nothing keeps earlier decisions.
  - A new tool adds its check as a bean and asks `allows(id)` before it runs anything; nothing else changes.
- **States:**
  - Nothing found: `Trusted`, without asking or storing anything.
  - A decision about exactly this fingerprint, stored or made while the folder is open: that decision, `Trusted` or
    `Untrusted`.
  - Otherwise: `Ask`, until the user decides. `Ask` is marked as a change when a check run again (`recheck`) found
    something new once the folder was settled (trusted, also because nothing was found, or not). An `Ask` from opening
    stays one.
  - The user decides about the state that was shown (`decide(shown, trusted)`). An answer about findings that a recheck
    has replaced since is ignored, so nothing the user did not see is trusted.
  - `Checking` while the checks run, `Closed` without a folder.
- **Off per tool:** once the checks have run, a tool may run if the folder is trusted or its own check found nothing.
  - `allows(id)` answers at once and says no while the checks run.
  - `awaitAllows(id)` first waits for them, for tools asking from their own thread, at most 30 s (no then). A check must
    end in a few seconds, or when its thread is interrupted.
  - A tool's findings never turn off another tool, and a tool without a check runs once the checks have run.
- **Decisions are stored per folder with their fingerprint** in `~/.sluide/project-trust.properties` (`TrustedProjects`,
  in the same pattern as `RecentProjects`, [ADR 0034](0034-project-picker.md)): `trusted:<fingerprint>` or
  `untrusted:<fingerprint>`.
  - Other findings than those decided about ask again.
  - A decision not to remember (the dialog closed with Esc) lasts while the folder is open, and so does one about a
    check that could not check, which is never stored.

## Consequences
- Opening a normal clone asks nothing. A folder whose contents would make a tool run commands is asked about once, with
  what was found, and asked about again when that changes.
- A tool that will run commands the folder defines adds a `ProjectTrustCheck`. Its findings join the same question and
  the same stored decision.

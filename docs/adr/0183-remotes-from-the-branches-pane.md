# 0183. Remotes are added, renamed, changed and removed from the branches pane; Remote fetches all

- Status: accepted; amends [0040](0040-git-log-branches-pane.md), [0043](0043-git-log-fetch.md) and
  [0171](0171-context-sensitive-branch-menus.md); see [0041](0041-git-write-operations.md),
  [0076](0076-cache-of-remote-action-sources.md)
- Date: 2026-11-17

## Context
The git log listed remotes and fetched one of them (ADR 0043, 0171), but adding, renaming or removing a remote, or
changing its URL, needed a terminal, and the header's Fetch (all remotes) had no counterpart on the Remote node. The
request: add, rename and remove remotes in the branches pane, and fetch all remotes when fetching on "Remote".

The tree could not carry this. It built its remote nodes from the remote branches, taking the first segment after
`refs/remotes/` (ADR 0040): a remote without branches did not show, with no remote branches there was no Remote group
to add a remote from, and a remote named `team/a` was split into `team` and `a`.

## Decision
**Tree.** Remote lists the configured remotes (`git remote`, read with every first page as `pages.remotes`), also those
without branches (a node without children). A remote branch goes to the longest configured remote that matches its name
(`remoteOfBranch`), so `team/a` is one node. Remote branches of a remote that is not configured (leftovers) are grouped
by their first segment as before. Once the remotes are read, the Remote group is always shown, also empty, so "Add
Remote…" is always reachable; before they are read the tree behaves as before. "No branches yet." shows only when the
remotes cannot be read. A selected remote stays selected across refreshes. The tooltip of a remote is its location
label, read with the first page (`GitRemotes.locations`): `https://github.com`, `ssh github.com` (for `ssh://` and
`git@github.com:x/y`), "Local repository" (paths and `file://`), "Unknown location"; never the path, user or full URL.

**Menus** (ADR 0171's rules: left out where never applicable, greyed out only while another write runs, not by a
pending merge, rebase or pick):
- Remote group: `Add Remote…` | `Fetch All Remotes` (the header's Fetch: all but `remote.<name>.skipFetchAll`; left
  out while there are no remotes).
- A configured remote: `Rename…`, `Change URL…`, `Remove` | `Fetch`. A folder of a remote keeps `Fetch`; a leftover
  node gets nothing new. The remote actions need exactly one selected remote.
- F2 renames and Delete or Shortcut+Backspace removes the selected remote (it asks) in the branch tree.

**Operations** all run through `RefWriter` on `sluide-git-write` (no file change), then the log refreshes. Failures
open the error dialog with git's message: "Add Remote Failed", "Rename Remote Failed", "Remove Remote Failed",
"Change URL Failed". `GitRemotes` (module git, CLI in `cli/GitCliRemotes.kt`) has `add`, `rename`, `remove`,
`setUrls`, `urls`, `removal` and `locations`; the operations and dialogs are in `RemoteOperations.kt` and
`RemoteDialogs.kt`, the ask-then-start flows in `RemoteActions.kt` (module git-log).
- **Names.** A name is valid iff `refs/remotes/<name>/test` is a valid refname (`GitRefNames.isValidRemoteName`; git's
  own rule, measured on 21 names: `HEAD`, `@`, `a.` and `é` are valid). Problems, in this order: "Enter a name.", "A
  name must not start with '-'.", "'x' is not a valid remote name.", the name cannot be passed in this locale
  (`refusal`), "A remote named 'x' already exists.", and "'a/b' conflicts with the remote 'a'." for a name that is a
  path prefix of an existing remote or has one as its prefix. `git remote add` refuses such conflicts itself, but
  `git remote rename` does not, so the check is ours for both. Names and URLs are passed after `--end-of-options`
  (measured: add, rename, set-url and remove accept it), so a URL starting with `-` is harmless; a name starting with
  `-` is refused all the same, as everywhere in the log (`refusal`).
- **Add Remote** (title "Add Remote", button "Add"): Name (prefilled and selected as `origin` when the repository has
  no remote, else empty), URL (problem "Enter a URL." when blank) and the check box "Fetch after adding" (checked;
  nothing is remembered). `git remote add`; if checked, the same as Fetch of that remote (cancellable, with its
  summary). A failed fetch leaves the remote added: the header says "Added remote upstream.", the dialog is the
  fetch's "Fetch Failed"; a cancel says "Added remote upstream. Fetch cancelled.". Header: "Added remote upstream."
  and, after a fetch, " Fetched upstream: …".
- **Rename Remote** (button "Rename", prefilled and selected, the same name rules; the unchanged name disables the
  button). `git remote rename` moves `remote.<old>.*`, `refs/remotes/<old>/*` with `HEAD`, `branch.*.remote`,
  `branch.*.pushRemote` and `remote.pushDefault`. Fetch refspecs that are not git's default (negative, or not
  destined under `refs/remotes/<old>/`) are left as they are with only a warning from git; this is detected from the
  refspecs before running, not from git's message, and the header adds "Its fetch refspecs were not updated, as they
  differ from git's default: edit remote.<new>.fetch.". If the rename fails after git renamed the configuration (a held
  `packed-refs.lock` does that: exit 128, refs left under the old name), the remotes are read again; new present and
  old gone is the partial case and the error says "Remote old was renamed to new in the configuration, but its remote
  branches could not be moved." with git's message. Header: "Renamed remote origin to upstream."
- **Remove** (alert "Remove Remote", header "Remove remote 'upstream'?", buttons Remove and Cancel, Cancel default and
  focused): one line per consequence, left out when zero: "Its 12 remote branches are deleted locally." ("Its 1
  remote branch is …"), "2 local branches (main and fix) lose their upstream." (the list capped as other branch lists:
  "a, b, c and 2 more"), "upstream is no longer the default push remote.", and always "Its URL is removed. This
  cannot be undone." The numbers are read fresh off the FX thread before the alert and follow git's own rule: the refs
  under `refs/remotes/` that a positive fetch refspec of the remote maps to, minus the negative ones, minus refs
  another remote's refspec also maps to, not counting `<remote>/HEAD` (git deletes it too, but nobody counts it as a
  branch). A mirror remote or one fetching heads into heads keeps local branches and tags. Tracking branches are those
  whose `branch.X.remote` or `branch.X.pushremote` is the remote; `remote.pushDefault` is the third consequence.
  Header: "Removed remote upstream."
- **Change URL** (title "Change URL of 'origin'", button "Change"): the fields URL and Push URL (prompt "Same as
  URL"; empty means no `pushurl`, clearing an existing one runs `git config --unset-all remote.<name>.pushurl`). A
  field whose key has several values is disabled with the note "Several URLs are configured; change them in a
  terminal.", because `git remote set-url` refuses several values without an old-URL pattern. The URLs are read off the
  FX thread before the dialog opens. Credentials are masked as `****` (see Safety), with the note "Credentials are
  hidden as ****; leave them to keep them." If a field still holds the mask at the userinfo position, the stored
  userinfo is kept, but only while scheme, host and port stay the same (`unmaskedUrl`); otherwise the problem is "Enter
  the credentials for the new host, or remove them.". A field that still shows what it started with writes nothing; a
  blank URL or no change disables Change. Header: "Changed the URL of origin." ("Changed the URLs of origin." when
  both fields were written).

`PushReader.NO_REMOTE` now says: "The repository has no remote. Add one in the git log's branches pane: Remote → Add
Remote…".

**Measured git behavior** (git 2.56.1, as ADR 0043 lists the fetch's): `add` refuses prefix conflicts in both
directions (exit 128), `rename` accepts them; `rename` prints nothing without a terminal and warns "Not updating
non-default fetch refspec" with exit 0; with `packed-refs.lock` held, `rename` fails after changing the configuration;
`remove` deletes what the refspecs map to and unsets `branch.*.remote`, `merge`, `pushRemote` and `remote.pushDefault`
pointing at the remote; `set-url` with several `url` values fails without an old-URL pattern; an empty URL makes
`get-url` print the name; a config lock fails with git's message. With 3000 refs rename takes 0.25 s and remove
0.1 s, so the normal write timeout suffices.

**Safety.** Git repeats the full URL in failure messages, so `add` and `setUrls` mask it in their result and
`GitCommandLog` masks it in logged messages. The command log masks the userinfo of every argument that is a scheme URL
(`scheme://***@host/…`, also after a `<transport>::` prefix and in the bracketed scp-like form
`[user:secret@host:22]:path`) and of URLs inside git's messages; this covers `GitPush`'s existing `ls-remote <url>`
too. The Change URL dialog is the one deliberate exception to "never show remote URLs" (ADR 0076, `docs/safety.md`):
it shows them with the userinfo of `http(s)` and `ftp(s)` URLs, and any userinfo with a `:`, as `****`
(`https://****@github.com/x.git`); `ssh://git@host/x` and `git@host:x` stay as they are. Everywhere else, tooltips
show the scheme and host only, and the operations' `toString` masks URLs.

## Consequences
Remotes can be managed without a terminal, and Fetch on Remote is the header's Fetch. A remote can have no branches
and still show, and names with a `/` are no longer split wrongly. The Remote group and its menus exist from the first
read on, so the empty-repository case needs no extra message.

Limits: no Git menu entry, one remote at a time, no editing of refspecs, `tagOpt` or `skipFetchAll`, and the leftover
remote branches of a remote that is not configured cannot be removed here. A renamed remote is not selected afterwards.
Git 2.53 or newer is assumed; the behavior was measured with 2.56.1.

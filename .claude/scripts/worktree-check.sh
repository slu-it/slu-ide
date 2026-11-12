#!/usr/bin/env bash
# Base check and hand-over report for an implementer working in a linked git worktree.
# start:  syncs the worktree onto master and prints the base. After every successful sync it records the master commit
#         it synced onto in the ref refs/worktree/sluide-base (per worktree). The sync, in this order:
#         1. HEAD is an ancestor of master: fast-forward.
#         2. A recorded base exists and is an ancestor of HEAD: the own commits are <base>..HEAD. None: `git reset
#            --keep` to master, saying the base is no longer on master (the base was amended or squashed away there).
#            Some: when the base is an ancestor of master, `git rebase master` (then master..HEAD is exactly the own
#            commits, even if something other than this script rebased the branch since the base was recorded); when
#            the base left master, `git rebase --onto master <base>`, so the old base's commits are not replayed.
#         3. No recorded base, and nothing ever happened in the worktree: the oldest entry of the branch's reflog is its
#            creation ("branch: Created from ...", so the log is complete) and every entry of that reflog and of the
#            worktree's HEAD reflog names the current HEAD commit, so HEAD never was anywhere else. A fresh worktree
#            has no own commits; reset as in 2.
#         4. Anything else: `git rebase master`.
#         A rebase conflict lists the files, aborts and exits 3. The reset keeps local files: when git refuses (an
#         untracked file in the way) the script exits 2 and nothing is discarded.
#         Refuses with uncommitted tracked changes; with <master-commit> also refuses when master lacks that commit.
# report: prints branch, worktree, own commits, the three-dot diff stat and uncommitted changes; --expect lists changed
#         files outside the given pathspecs as UNEXPECTED; untracked files that are not ignored are listed last as
#         UNTRACKED, an untracked folder as one line (they would be left out of a merge). Exits 1 when there is an
#         UNEXPECTED or UNTRACKED line.
# Usage: .claude/scripts/worktree-check.sh start [<master-commit>]
#        .claude/scripts/worktree-check.sh report [--expect <pathspec>...]
# Exit codes: 0 ok, 1 unexpected or untracked files, 2 usage or refusal, 3 rebase conflict.
set -euo pipefail

die() { echo "$*" >&2; exit 2; }

[ $# -ge 1 ] || die "Usage: worktree-check.sh start [<master-commit>] | report [--expect <pathspec>...]"
mode="$1"; shift

gitdir="$(git rev-parse --path-format=absolute --git-dir 2>/dev/null)" || die "Not inside a git working tree."
common="$(git rev-parse --path-format=absolute --git-common-dir)"
[ "$gitdir" != "$common" ] || die "Not in a linked worktree (this is the main tree); run this inside your .claude/worktrees/agent-* directory."
top="$(git rev-parse --show-toplevel)"
branch="$(git rev-parse --abbrev-ref HEAD)"

dirty() { git -C "$top" status --porcelain --untracked-files=no; }
own_commits() { git -C "$top" rev-list --count master..HEAD; }

case "$mode" in
  start)
    [ $# -le 1 ] || die "start takes at most one argument: <master-commit>"
    for marker in rebase-merge rebase-apply MERGE_HEAD; do
      [ ! -e "$gitdir/$marker" ] || die "A rebase or merge is in progress ($marker); finish or abort it first."
    done
    if [ $# -eq 1 ]; then
      git -C "$top" merge-base --is-ancestor "$1" master 2>/dev/null \
        || die "master does not contain $1; the base is not what the brief expects."
    fi
    changes="$(dirty)"
    if [ -n "$changes" ]; then
      echo "Uncommitted tracked changes; commit them first (nothing was discarded):" >&2
      echo "$changes" >&2
      exit 2
    fi
    rebase_conflict() {
      echo "Rebase onto master conflicts in:" >&2
      git -C "$top" diff --name-only --diff-filter=U >&2
      git -C "$top" rebase --abort
      exit 3
    }
    # The master commit the last successful sync ended on, kept in a ref of this worktree only.
    base_ref=refs/worktree/sluide-base
    base="$(git -C "$top" rev-parse -q --verify "$base_ref" 2>/dev/null || true)"
    reset_to_master() { # $1: the base that left master; --keep refuses instead of overwriting local files
      git -C "$top" reset --keep -q master \
        || die "Could not move to master (untracked or local files in the way?); nothing was discarded."
      echo "reset to master: the base $(git -C "$top" rev-parse --short "$1") is no longer on master"
    }
    head="$(git -C "$top" rev-parse HEAD)"
    reflog_commits() { git -C "$top" reflog show --format=%H "$1" 2>/dev/null | sort -u; } # distinct commits named
    if git -C "$top" merge-base --is-ancestor HEAD master; then
      git -C "$top" merge --ff-only -q master || die "Could not fast-forward to master (untracked files in the way?)."
    elif [ -n "$base" ] && git -C "$top" merge-base --is-ancestor "$base" HEAD; then
      if [ "$(git -C "$top" rev-list --count "$base..HEAD")" -eq 0 ]; then
        reset_to_master "$base"
      elif git -C "$top" merge-base --is-ancestor "$base" master; then
        # The base may be stale (something else rebased the branch), then <base>..HEAD holds master's commits.
        git -C "$top" rebase -q master >/dev/null 2>&1 || rebase_conflict
      else
        # The base left master (amended or squashed away): <base>..HEAD is exactly the own commits.
        git -C "$top" rebase -q --onto master "$base" >/dev/null 2>&1 || rebase_conflict
      fi
    elif [ -z "$base" ] && [ "$(reflog_commits "$branch")" = "$head" ] && [ "$(reflog_commits HEAD)" = "$head" ] \
      && [[ "$(git -C "$top" reflog show --format=%gs "$branch" | tail -n 1)" == "branch: Created from"* ]]; then
      # Never synced and nothing ever happened here: a fresh worktree, so no own commits (its base left master).
      reset_to_master "$head"
    else
      git -C "$top" rebase -q master >/dev/null 2>&1 || rebase_conflict
    fi
    git -C "$top" merge-base --is-ancestor master HEAD || die "master is not an ancestor of HEAD after syncing."
    git -C "$top" update-ref "$base_ref" master
    echo "base: $(git -C "$top" log -1 --format='%h %s' master)"
    echo "branch: $branch"
    echo "worktree: $top"
    echo "own commits: $(own_commits)"
    ;;
  report)
    expect=()
    if [ $# -gt 0 ]; then
      [ "$1" = "--expect" ] && [ $# -ge 2 ] || die "Usage: report [--expect <pathspec>...]"
      shift
      expect=("$@")
    fi
    echo "branch: $branch"
    echo "worktree: $top"
    echo "own commits:"
    git -C "$top" log --oneline master..HEAD
    git -C "$top" diff master...HEAD --stat
    changes="$(dirty)"
    if [ -n "$changes" ]; then
      echo "WARNING: uncommitted tracked changes (not part of the branch):"
      echo "$changes"
    fi
    if ! git -C "$top" merge-base --is-ancestor master HEAD; then
      echo "master moved $(git -C "$top" rev-list --count HEAD..master) commits since your base; the merge gate rebases"
    fi
    status=0
    if [ ${#expect[@]} -gt 0 ]; then
      allowed="$(git -C "$top" diff --name-only --no-renames master...HEAD -- "${expect[@]}")"
      unexpected="$(git -C "$top" diff --name-only --no-renames master...HEAD | grep -vxF -f <(printf '%s\n' "$allowed" "") || true)"
      if [ -n "$unexpected" ]; then
        sed 's/^/UNEXPECTED: /' <<<"$unexpected"
        status=1
      fi
    fi
    untracked="$(git -C "$top" ls-files --others --exclude-standard --directory --no-empty-directory)"
    if [ -n "$untracked" ]; then
      sed 's/^/UNTRACKED: /' <<<"$untracked"
      status=1
    fi
    exit "$status"
    ;;
  *) die "Unknown mode '$mode'; use start or report." ;;
esac

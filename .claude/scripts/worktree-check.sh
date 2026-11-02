#!/usr/bin/env bash
# Base check and hand-over report for an implementer working in a linked git worktree.
# start:  syncs the worktree onto master (reset when there are no own commits, otherwise rebase) and prints the base.
#         Refuses with uncommitted tracked changes; with <master-commit> also refuses when master lacks that commit.
# report: prints branch, worktree, own commits, the three-dot diff stat and uncommitted changes; --expect lists changed
#         files outside the given pathspecs as UNEXPECTED and exits 1.
# Usage: .claude/scripts/worktree-check.sh start [<master-commit>]
#        .claude/scripts/worktree-check.sh report [--expect <pathspec>...]
# Exit codes: 0 ok, 1 unexpected files, 2 usage or refusal, 3 rebase conflict.
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
    if [ "$(own_commits)" -eq 0 ]; then
      git -C "$top" merge --ff-only -q master || die "Could not fast-forward to master (untracked files in the way?)."
    elif ! git -C "$top" rebase -q master >/dev/null 2>&1; then
      echo "Rebase onto master conflicts in:" >&2
      git -C "$top" diff --name-only --diff-filter=U >&2
      git -C "$top" rebase --abort
      exit 3
    fi
    git -C "$top" merge-base --is-ancestor master HEAD || die "master is not an ancestor of HEAD after syncing."
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
    if [ ${#expect[@]} -gt 0 ]; then
      allowed="$(git -C "$top" diff --name-only --no-renames master...HEAD -- "${expect[@]}")"
      unexpected="$(git -C "$top" diff --name-only --no-renames master...HEAD | grep -vxF -f <(printf '%s\n' "$allowed" "") || true)"
      if [ -n "$unexpected" ]; then
        sed 's/^/UNEXPECTED: /' <<<"$unexpected"
        exit 1
      fi
    fi
    ;;
  *) die "Unknown mode '$mode'; use start or report." ;;
esac

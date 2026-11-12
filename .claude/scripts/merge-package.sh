#!/usr/bin/env bash
# Merges a package worktree (an implementer's branch) onto master in the main tree in one step: checks, rebase onto
# master when it moved, compile of the touched modules, fast-forward merge. Refuses (exit 2), before anything is
# changed, while a Gradle build runs in the worktree or in the main tree (builds in other worktrees do not count), and
# when the worktree has uncommitted tracked changes or untracked files that are not ignored (they would be left out).
# A fast-forward that git refuses (e.g. local changes in the main tree in the way) also exits 2; the branch may already
# be rebased by then. The worktree is kept on merge so the agent can still be resumed; --remove deletes it after the
# review and the final gate.
# <worktree> is a worktree path, its branch name, or a unique substring of either (e.g. the agent id).
# Usage: .claude/scripts/merge-package.sh <worktree> [--expect <pathspec>...] [--no-compile]
#        .claude/scripts/merge-package.sh --remove <worktree>
# Exit codes: 0 ok, 1 check failed (unexpected files, compile), 2 usage or refusal, 3 rebase conflict.
set -euo pipefail

die() { echo "$*" >&2; exit 2; }

usage="Usage: merge-package.sh <worktree> [--expect <pathspec>...] [--no-compile] | --remove <worktree>"
[ $# -ge 1 ] || die "$usage"

remove=0
if [ "$1" = "--remove" ]; then
  remove=1
  shift
  [ $# -eq 1 ] || die "$usage"
fi
query="$1"
[ -n "$query" ] || die "Empty worktree name."
shift
expect=()
compile=1
while [ $# -gt 0 ]; do
  case "$1" in
    --no-compile) compile=0; shift ;;
    --expect)
      shift
      while [ $# -gt 0 ] && [[ "$1" != --* ]]; do expect+=("$1"); shift; done
      [ ${#expect[@]} -gt 0 ] || die "--expect needs at least one pathspec"
      ;;
    *) die "$usage" ;;
  esac
done

# Worktree list: parallel arrays of path and branch; the first entry is the main tree.
paths=()
branches=()
p=""
while IFS= read -r line; do
  case "$line" in
    "worktree "*) p="${line#worktree }"; paths+=("$p"); branches+=("") ;;
    "branch "*) branches[$((${#paths[@]} - 1))]="${line#branch refs/heads/}" ;;
  esac
done < <(git -C "$(cd "$(dirname "$0")/../.." && pwd -P)" worktree list --porcelain)
main="${paths[0]}"

matches=()
for i in "${!paths[@]}"; do
  [ "$i" -eq 0 ] && continue
  if [ "${paths[$i]}" = "$query" ] || [ "${branches[$i]}" = "$query" ]; then
    matches=("$i")
    break
  fi
  if [[ "${paths[$i]}" == *"$query"* ]] || [[ "${branches[$i]}" == *"$query"* ]]; then
    matches+=("$i")
  fi
done
if [ ${#matches[@]} -eq 0 ]; then
  echo "No worktree matches '$query'. Candidates:" >&2
  for i in "${!paths[@]}"; do [ "$i" -gt 0 ] && echo "  ${branches[$i]}  ${paths[$i]}" >&2; done
  exit 2
elif [ ${#matches[@]} -gt 1 ]; then
  echo "'$query' is ambiguous. Candidates:" >&2
  for i in "${matches[@]}"; do echo "  ${branches[$i]}  ${paths[$i]}" >&2; done
  exit 2
fi
wt="${paths[${matches[0]}]}"
branch="${branches[${matches[0]}]}"
[ -n "$branch" ] || die "Worktree $wt has a detached HEAD, no branch to merge."

if [ "$remove" -eq 1 ]; then
  if ! git -C "$main" merge-base --is-ancestor "$branch" master \
    && [ "$(git -C "$main" rev-list --count "master..$branch")" -gt 0 ]; then
    die "$branch is not merged into master; refusing to remove $wt."
  fi
  git -C "$main" worktree remove "$wt" || die "Could not remove $wt (modified or untracked files?); the branch was kept."
  git -C "$main" branch -D "$branch" >/dev/null
  echo "removed worktree $wt and branch $branch"
  exit 0
fi

[ "$(git -C "$main" rev-parse --abbrev-ref HEAD)" = "master" ] || die "The main tree $main is not on master."

# Gradle clients (not daemons) in the worktree (the agent may still work) or in the main tree (the fast-forward changes
# its files; builds in other worktrees below it do not count), found by gradle-running.sh (reads /proc, so Linux only).
# Before anything is changed.
if found="$("$(dirname "$0")/gradle-running.sh" "$wt")"; then
  die "A Gradle build runs in $wt (pid ${found%% *}); the agent may still be working. Wait for it to finish."
fi
if found="$("$(dirname "$0")/gradle-running.sh" "$main")"; then
  die "A Gradle build runs in the main tree $main (pid ${found%% *}); wait for it to finish first."
fi

changes="$(git -C "$wt" status --porcelain --untracked-files=no)"
if [ -n "$changes" ]; then
  echo "Uncommitted tracked changes in $wt:" >&2
  echo "$changes" >&2
  exit 2
fi
untracked="$(git -C "$wt" ls-files --others --exclude-standard --directory --no-empty-directory)"
if [ -n "$untracked" ]; then
  echo "Untracked files in $wt (they would be left out of the merge); commit or delete them:" >&2
  sed 's/^/  /' <<<"$untracked" >&2
  exit 2
fi
[ "$(git -C "$main" rev-list --count "master..$branch")" -gt 0 ] || die "nothing to merge: $branch has no commits beyond master."

git -C "$main" log --oneline "master..$branch"
git -C "$main" diff "master...$branch" --stat

if [ ${#expect[@]} -gt 0 ]; then
  allowed="$(git -C "$main" diff --name-only --no-renames "master...$branch" -- "${expect[@]}")"
  unexpected="$(git -C "$main" diff --name-only --no-renames "master...$branch" | grep -vxF -f <(printf '%s\n' "$allowed" "") || true)"
  if [ -n "$unexpected" ]; then
    sed 's/^/UNEXPECTED: /' <<<"$unexpected"
    exit 1
  fi
fi

if ! git -C "$main" merge-base --is-ancestor master "$branch"; then
  before="$(git -C "$wt" rev-parse HEAD)"
  if ! git -C "$wt" rebase -q master >/dev/null 2>&1; then
    echo "Rebase of $branch onto master conflicts in:" >&2
    git -C "$wt" diff --name-only --diff-filter=U >&2
    git -C "$wt" rebase --abort
    exit 3
  fi
  echo "rebased $branch onto master"
  if [ "$compile" -eq 1 ] && [ "$before" != "$(git -C "$wt" rev-parse HEAD)" ]; then
    modules=()
    while IFS= read -r file; do
      case "$file" in
        modules/*/*) m=":modules:$(cut -d/ -f2 <<<"$file")" ;;
        ide/*) m=":ide" ;;
        *) continue ;;
      esac
      [[ " ${modules[*]:-} " == *" $m "* ]] || modules+=("$m")
    done < <(git -C "$wt" diff --name-only --no-renames "master...$branch")
    if [ ${#modules[@]} -gt 0 ]; then
      tasks=()
      for m in "${modules[@]}"; do tasks+=("$m:testClasses"); done
      echo "compiling: ${tasks[*]}"
      log="$(mktemp)"
      if ! (cd "$wt" && ./gradlew --no-daemon "${tasks[@]}" -q >"$log" 2>&1); then
        grep '^e: ' "$log" | head -30 || true
        awk '/^\* What went wrong:/ { on = 1 } /^\* Try:/ { on = 0 } on' "$log" | head -20 || true
        rm -f "$log"
        git -C "$wt" reset --hard -q "$before"
        echo "compile failed; $branch was reset to its pre-rebase head, a retry rebases and compiles again" >&2
        exit 1
      fi
      rm -f "$log"
    fi
  fi
fi

count="$(git -C "$main" rev-list --count "master..$branch")"
git -C "$main" merge --ff-only -q "$branch" \
  || die "Fast-forward of master to $branch failed (see git's message above; local changes in the main tree in the way?). $branch may already be rebased onto master; fix the cause and run this again."
echo "merged $count commits onto master: $(git -C "$main" rev-parse --short HEAD)"
echo "worktree kept: remove after the review and the final gate with merge-package.sh --remove $branch"

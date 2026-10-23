# 0080. Which `.gitignore` lines have an effect

- Status: accepted
- Date: 2026-10-23

## Context
`.gitignore` files grow over time. Lines for tools that are long gone stay behind, as do lines that a later line or an
ignored parent folder overrides, and lines for files git already tracks (which ignoring does not untrack). The editor
showed such a file only highlighted, so it was not possible to tell which lines did anything. Whether a line has an
effect depends on the files present, the other ignore files and the index. Git decides that, and since ADR 0013 Thoreau
has no `.gitignore` parser of its own.

## Decision
- **Effect means "deciding rule".** A line has an effect when `git check-ignore -v` names it as the deciding pattern
  for at least one untracked path: the last matching line of the ignore files in effect, either ignoring the path or
  un-ignoring it (a `!` line). A line that only tracked files match is a case of its own: git keeps tracking those
  files, so the line does not do what it seems to. Every other pattern line has no effect: it matches nothing, or
  another line or an ignored parent folder decides instead.
- **Candidates from git, limited to the file's folder.** A `.gitignore` applies only below its own folder D, so only
  the paths below D are checked:
  - untracked and ignored paths come from `git status --porcelain=v2 -z --no-renames --ignored=matching
    --untracked-files=all -- D/` (`./` for the root). In `matching` mode a folder is listed instead of its contents
    only when a pattern matches the folder itself. A folder whose files a `*.log` line ignores therefore shows as its
    files, so `*.log` gets the credit. Under an ignored folder nothing is listed, which is why `build/*.tmp` next to
    `build/` has no effect.
  - tracked files that match ignore rules come from `git ls-files -z --cached --ignored --exclude-standard -- D/`.
- **Deciding lines from `git check-ignore -v -z --stdin`.** The untracked paths go to it as they are, the tracked ones
  with `--no-index`. Their records are `source, line, pattern, path`, and only those whose source is the open file
  count. Sources come relative to the root (`sub/.gitignore`, `.git/info/exclude`) or absolute (`core.excludesFile`).
  Exit code 1 means that no path matched and is an empty answer, not a failure. check-ignore rejects
  `--literal-pathspecs`, so paths starting with `:` are sent as `./:…`.
- **Limits.** Stdin is sent in chunks below `GitCli.MAX_INPUT` (15 KiB). Each git call has a 10 s timeout, and the
  whole query a 15 s deadline, since it shares the window's single diff thread. At most 50,000 candidates (tracked
  files once, not once per conflict stage) and 16 MiB of listing output are checked. Hitting one of these caps, or the
  deadline between check-ignore runs, gives a partial answer marked incomplete, and the tooltips say that not all
  paths could be checked. If the deadline runs out during the two listings, or a call reaches its own 10 s timeout,
  there is no answer: the file shows no dots. Each line keeps its counts and up to 10 example paths, relative to D.
- **Off the JavaFX thread, like diffs.** `GitStatusService.ignoreRulesAsync` runs on the window's `sluide-git-diff`
  thread. As with `diffAsync` (ADR 0062), the newest request per file wins, and older git runs are stopped. Git runs
  only while the folder's trust allows it (ADR 0053).
- **Reaching the view through a window service.** `file-viewers` cannot depend on `git`, so it declares
  `IgnoreRuleEffects` (a `lookup` set by the window, plus `changed()`/`onChanged`). `MainWindow` registers it as a
  window service (ADR 0070), sets `lookup` to `ignoreRulesAsync`, and calls `changed()` when the git status changes.
  This is the pattern the HTTP client session uses for `wouldCommit`.
- **Gutter dots in a wrapper view.** `IgnoreFileViewer` opens files named exactly `.gitignore` in a
  `GitignoreFileView`. It wraps a `CodeView` the way `EnvFileView` does, because `CodeView` is at its size limit
  (ADR 0054). Other ignore files (`.dockerignore`, `Python.gitignore`) are not git's, and they keep the plain
  `CodeView`. One gutter column shows, per pattern line:
  - a green dot when the line has an effect;
  - an orange dot when only tracked files match it;
  - a grey ring when it has no effect.

  Comment and blank lines get none. Tooltips give counts and examples. The text itself is not styled: it is still
  being edited, and dimming would compete with the syntax colours.
- **Saved text, moved with edits.** The dots describe the file as saved. They are looked up again on save, reload and
  status changes. Between saves they follow the unsaved edits through a line diff against the saved text, so only
  inserted or changed lines lose their dot. An answer is dropped when the file on disk no longer matches the saved
  text the view shows (a pending conflict banner). Renamed to another name, the view keeps following the file
  (unsaved edits included) without dots.

## Consequences
- Dead lines, overridden lines and lines that only match tracked files can be seen at a glance, with git's own answer.
- Opening or saving a `.gitignore` costs one status listing, one `ls-files` and a few check-ignore runs below its
  folder. That is cheap for normal projects. Huge untracked trees hit the caps and say so; a repository whose
  `git status` alone takes too long gets no dots.
- The dots are for `.gitignore` only, not for `.git/info/exclude` or global excludes.
- An unsaved line has no dot until it is saved: what it would do is not computed without git.
- Added to [../index.md](../index.md).

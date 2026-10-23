# Which `.gitignore` lines have an effect

**Modules:** `file-viewers` (`GitignoreFileView`, `IgnoreRuleEffects`), `git` (`GitStatusService.ignoreRulesAsync`),
wired in `ide`'s `MainWindow`; decision in [ADR 0080](../adr/0080-gitignore-line-effects.md)

A file named `.gitignore` shows, in a gutter column next to the line numbers, which of its lines currently do
something.

- **Green dot:** the line has an effect. It is the deciding rule for at least one untracked path below the file's
  folder: it ignores it, or a `!` line un-ignores it.
- **Orange dot:** only files git already tracks match the line. Git keeps tracking them, so the line has no effect on
  them (remove them from the index with `git rm --cached` if they should be ignored).
- **Grey ring:** no effect. The line matches nothing, or another line or an ignored parent folder decides instead
  (`build/*.tmp` below `build/`).
- **No dot:** comment lines (`#` as the first character) and blank lines.
- **Tooltip:** what the line ignores or un-ignores, with the count and up to 10 example paths (relative to the file's
  folder), and the tracked files it also matches. When git could check only part of the paths (more than 50,000, or
  it took too long), the tooltip says so.
- **Git decides:** the dots come from `git status` and `git check-ignore -v`, not from a parser of Thoreau's own, so
  global excludes, `.git/info/exclude` and other `.gitignore` files are taken into account as git sees them.
- **When it updates:** when the file is opened, saved or reloaded, and when the git status of the window changes (e.g.
  a new build output folder appears). Git runs in the background; the dots appear when it is done.
- **Unsaved edits:** the dots describe the saved file. While editing, they move with their lines; new or changed lines
  get no dot until the file is saved.
- **No dots** outside a git repository, without git, while the folder's trust keeps git off
  ([project-trust.md](project-trust.md)), or when git fails or takes too long.
- **Only `.gitignore`:** `.dockerignore`, `.npmignore` and other ignore files, and templates like `Python.gitignore`,
  are only highlighted ([file-viewers.md](file-viewers.md)). Renaming a `.gitignore` to another name removes the
  dots.

# 0019. Changed words within modified lines

- Status: accepted
- Date: 2026-10-14

## Context
The diff view ([ADR 0015](0015-git-diff.md)) colours whole lines: a modified line is light blue over its full width,
however little of it changed. We want the changed words within modified lines to stand out, on both sides. Git gives
us only the changed lines (`git diff -U0`). Within a modified block, the lines are paired by position, so line i on
the left need not be the counterpart of line i on the right when lines were added or removed in the block.

## Decision
- SluIde finds the changed words itself (`wordChanges` in `file-viewers`), only within git's modified blocks, so the
  changed lines are still exactly git's.
  - It does not use `git --word-diff=porcelain`: that would mean a second git process per refresh, a second parser,
    and a word model in `git` and `TextDiff`. Its default words are whitespace-separated, too, which is coarser than
    we want.
  - It does not use a library: comparing a block takes a few dozen lines.
- Words are runs of letters, digits and `_`; runs of whitespace, line breaks and every other character are tokens of
  their own.
- Each modified block is compared as a whole (its lines joined by line breaks), so that a line added within the block
  is marked whole and the lines after it are compared with their counterparts. A token not in the longest common
  subsequence of the two sides is changed.
  - Of equally long subsequences, the earliest matches win: only a common prefix is skipped, and there is no suffix
    trimming. Otherwise the end of a changed line would be matched with the end of a line added after it.
- The longest common subsequence comes from a plain dynamic program. A block that needs more than a million steps is
  left out and stays light blue, so a large rewrite cannot stall the FX thread.
- The changed words get the style class `diff-changed-words`, laid over the syntax highlighting. It sets RichTextFX's
  text background (`-rtfx-background-color`) in a stronger blue, light and dark.

## Consequences
- The view shows the changed words without a new dependency or git call, and works for any `TextDiff`.
- Our word comparison can disagree with git's `diff.wordRegex` or `--word-diff`; only the lines follow git's
  configuration.
- Unchanged lines in a modified block, and lines whose only change is the line ending, have no changed words.
- Very large modified blocks show no changed words.

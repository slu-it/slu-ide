# Review window: ideas left for later

[ADR 0168](../adr/0168-review-window.md) brought the review window ([feature](../features/code-review.md)). Left out:
- **Line comments**: a comment on a line selection (`ReviewAnchor`: path, side, 0-based line; the selection is the
  anchor), shown as rows below the line, stored per project and re-anchored when the file changes; exported as a
  Markdown list (to hand to an agent) or turned into TODOs with `file:line` links.
- **Persisted Viewed flags**: per project, keyed by path and the reviewed version's blob, so they survive closing the
  window and clear when the file changes.
- **Image diffs**: old and new image side by side, or swiped, for PNG, JPEG and SVG.
- **Free text selection** across rows, beyond whole lines.
- **Generated files by attribute**: `linguist-generated` in `.gitattributes` collapses a file, as on GitHub.
- **Staged and unstaged** as separate scopes of the uncommitted changes.
- **Remembered window bounds.**

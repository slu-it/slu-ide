# 0030. Masking .env values with a style overlay

- Status: accepted
- Date: 2026-10-15

## Context
`.env` files hold secrets. Opening one on a shared screen, in a call or a screenshot should not show them, yet the
file must stay editable like any code file: copy, find and replace, save, undo, dirty state, change markers and line
endings all work on its text ([ADR 0020](0020-editing-and-saving-files.md),
[ADR 0021](0021-change-markers-and-diffs-on-demand.md)). Editing must never be blind.

## Decision
- **An overlay, not a text replacement:** `EnvFileView` wraps a normal `CodeView` and sets `CodeView.maskedRanges`;
  the code view adds the style class `masked` over the token styles of those ranges (`EnvValues.ranges`). It is
  applied together with the find bar's overlay, masks first, then the matches, on every change of the text
  synchronously (so pasted or undone text is never shown, not only once highlighting catches up) and again in the
  background highlighting pass.
- **Rendering** (`syntax.css`, light and dark): `-fx-fill: transparent` with an `-rtfx-background-color` bar as long
  as the value; `.masked.search-match` rules keep match colours from showing through. RichTextFX applies no text
  colour of its own to selections, so selecting reveals nothing either.
- **What counts as a value:** the text after the first `=`, without surrounding blanks and a trailing ` # comment`,
  quotes included; quoted values spanning lines up to their closing quote; commented-out assignments too.
- **Revealed:** the caret's line while the text has the focus and once the caret was placed, and the lines of the
  find bar's selected match; recomputed on caret, selection, focus and text changes. A **Show values** toggle per
  tab, off on every open and never stored. Placeholder files (`.example`, `.sample`, `.template`) are not masked.

Rejected:
- **Replacing the values in the text** (`****`): copy, find, replace, save, undo, dirty state, change markers and
  line endings would all have to translate between shown and real text.
- **Masking whole lines:** the keys are what one looks for and is safe to show.
- **Revealing on hover:** a mouse moved across the editor would leak values on a shared screen.
- **Masking in the side-by-side diff:** it has no caret or toggle to reveal a value; it shows them unmasked.

## Consequences
- The real values are always in the text model: copy, find, replace and save need no special cases, but a find
  query typed into the bar can still confirm a guessed value by its counter.
- The bars are square: RichTextFX has no background radius for text.
- Any `CodeView` can mask ranges this way; only `.env` files use it.
- The diff of a `.env` file (View → Show Changes) shows its values.

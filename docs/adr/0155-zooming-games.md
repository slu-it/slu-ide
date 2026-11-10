# 0155. Zooming games

- Status: accepted
- Date: 2026-11-10

## Context
Every game picks one size for its tab: Minesweeper's cells fill the tab between 16 and 40 px, the other games scale
their field or cards to the tab within limits of their own. Users want the board smaller (a game beside a build log in
a narrow tab, more of a large board at once) or larger (bigger cards, easier clicks) than that. The previews have a
zoom of their own (ADR 0033, ADR 0083), but in absolute percentages of a page; a game has no natural 100%.

## Decision
- **Fit is the anchor:** the zoom is a factor on the size the game shows today, with its caps and floors kept; at 1.0
  ("Fit") nothing changes. Minesweeper's cell is `max(1, floor(fit × zoom))`, so 25% of the 16 px minimum is 4 px; no
  extra floor below the game's minimum.
- **Two ranges** (`ZoomRange`): grid and card games (Minesweeper, Word Guess, Doubles, Sudoku, Solitaire, Pipe Puzzle)
  zoom 25%, 50%, 75%, Fit, 125%, 150%, 200% and scroll above Fit in their scroll pane. Real-time games (Orbit Guard,
  Block Breaker, Mini Golf) zoom 25%, 50%, 75%, Fit only: they already fill the tab at Fit, and scrolling while playing
  is unusable.
- **Controls** (`GameZoomControls`): a group at the far right of each game toolbar, after the game's own trailing
  controls: zoom out, a non-editable level menu (the presets as radio items, the current one checked, none between
  presets; its text "Fit" or the rounded percentage, at one fixed width), zoom in and a Fit button. The buttons step
  through the presets and are disabled at the bounds. None of them takes the focus from the board.
- **Inputs anywhere in the game tab**, through event filters on the view's root that consume what they handle:
  Shortcut+Plus (or `=`, numpad +) and Shortcut+Minus (numpad −) step, Shortcut+0 (numpad 0) fits, Shortcut+wheel
  zooms by `1.1^(deltaY / 40)` instead of scrolling, a pinch by its factor; both clamped to the range. Shortcut+9, the
  previews' Fit key, is left free: it is the Git Log key on macOS (Cmd+9). A plain wheel still scrolls.
- **Stored per game, user-wide**, in the top-level field `gameZoom` of `shared.json` (ADR 0101): game id → factor
  (`minesweeper`, `wordGuess`, `doubles`, `sudoku`, `solitaire`, `pipePuzzle`, `orbitGuard`, `blockBreaker`,
  `miniGolf`). Read when a game view is created, written on every change through `StoredField` (`GameZoomPreferences`,
  one bean for all games). Missing or invalid values read as Fit, out-of-range ones as the nearest bound. Open tabs in
  other windows keep their zoom until they are opened again.
- **Re-layout, not a Scale transform:** the zoom feeds each game's own size computation, so text, icons and drawn
  cards are rendered sharp at the new size, and clicks map to cells through the existing mappings (`cellAt` and the
  like) without inverting a transform. A board larger than the tab grows its minimum size inside a scroll pane that
  fits its content, so the scroll pane scrolls; the Fit size is computed from the inside of that scroll pane, not from
  the board, which would feed its own growth back. `FitArea` in `games/common/ui` gives that inside and describes the
  pattern for every game in such a scroll pane.
- **The previews keep their own control** (ADR 0033, ADR 0083): absolute percentages, a 100% button and Fit on
  Shortcut+9 fit pages, not games. Images and previews could get the level menu later.

## Consequences
- Every game gets the same group and keys; the shared pieces (`ZoomRange`, `GameZoomPreferences`, `GameZoomControls`
  in `games/common`) are tested once, each game only through one wiring test of its view.
- Each game has to take the zoom into its own sizing; Minesweeper is the reference wiring.
- Below its old minimum a board can get very small (4 px cells); that is the user's choice and Fit restores it.
- Added to [../index.md](../index.md); see [ADR 0033](0033-zooming-images-and-diagrams.md),
  [ADR 0083](0083-zooming-document-previews.md), [ADR 0101](0101-shared-data-file.md),
  [ADR 0131](0131-games-menu-minesweeper.md).

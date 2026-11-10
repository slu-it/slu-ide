# Game zoom

**Modules:** `modules/games` (`ZoomRange` and `GameZoomPreferences` in `dev.sluide.games.common`, the toolbar group
and inputs in `GameZoomControls` in `dev.sluide.games.common.ui`); decisions in
[ADR 0155](../adr/0155-zooming-games.md)

Every game of the Games menu can be shown smaller or larger than the size it picks for its tab. That size is **Fit**;
the zoom is a percentage of it.

## Controls
At the far right of the game's toolbar, after the game's own controls:

| Control | Action |
|---|---|
| Zoom out (magnifier with minus) | To the next smaller level; disabled at the smallest |
| Level menu ("Fit", "50%", "63%") | Shows the zoom; choosing a level zooms to it. The current level is checked, none while the zoom lies between levels (after the wheel or a pinch) |
| Zoom in (magnifier with plus) | To the next larger level; disabled at the largest |
| Fit | Back to the size the game picks for the tab |

None of them takes the keyboard focus from the board.

## Keys, wheel and pinch
Anywhere in the game tab:

| Input | Action |
|---|---|
| Shortcut+Plus (or `=`, numpad +) | Zoom in |
| Shortcut+Minus (or numpad −) | Zoom out |
| Shortcut+0 (or numpad 0) | Fit |
| Shortcut+mouse wheel | Zoom continuously, about 10% per notch, instead of scrolling |
| Pinch | Zoom continuously |

Shortcut+9 does nothing here (it is the Git Log key on macOS). A plain wheel still scrolls a board larger than the
tab.

## Ranges
| Games | Levels |
|---|---|
| Minesweeper, Word Guess, Doubles, Sudoku, Solitaire, Pipe Puzzle | 25%, 50%, 75%, Fit, 125%, 150%, 200% |
| Orbit Guard, Block Breaker, Mini Golf | 25%, 50%, 75%, Fit |

Above Fit the board can grow larger than the tab and then scrolls. The real-time games stop at Fit: they already fill
the tab, and scrolling while playing does not work. Fit keeps each game's limits (Minesweeper's cells are 16 to 40 px at Fit);
the zoom applies on top of them, so a zoomed-out board can be smaller than the game's usual minimum.

Each game's page says how its board follows the zoom.

## Stored
The zoom of each game, user-wide in `~/.sluide/shared.json`, field `gameZoom`
([ADR 0101](../adr/0101-shared-data-file.md)):
```json
"gameZoom": { "minesweeper": 0.75, "solitaire": 1.25 }
```
Keys: `minesweeper`, `wordGuess`, `doubles`, `sudoku`, `solitaire`, `pipePuzzle`, `orbitGuard`, `blockBreaker`,
`miniGolf`. Written a moment after a change, off the FX thread. A missing or invalid value reads as Fit, one outside
the game's range as the nearest level. A game tab reads it when it opens: a tab of the same game open in another
window keeps its zoom until it is opened again.

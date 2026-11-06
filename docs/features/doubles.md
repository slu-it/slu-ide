# Doubles

**Modules:** `modules/games` (window bean `Doubles` in `dev.sluide.games`; package `dev.sluide.games.doubles`: the pure game in
`DoublesGame`, the FX-free `DoublesModel`, `DoublesPreferences`, UI in `ui`); menu in `ide` (`ToolsMenu`, `WindowTools`);
decisions in [ADR 0136](../adr/0136-doubles.md) and [ADR 0131](../adr/0131-games-menu-minesweeper.md)

A sliding-tile merge game: slide all tiles, merge equal ones into their sum and reach the goal tile. A tab of the editor
area with a toolbar, the score and best score, the board and a status line.

## Opening
- **Games → Doubles** opens the tab. There is one per window: choosing the item again selects the open tab.
- Shortcut+W closes the tab. The game belongs to the window: after closing, a new tab shows the same game. After a
  restart a new game starts; only the last size and the best scores are kept.

## Playing
- The board is square: 3×3, 4×4, 5×5 or 6×6 (default 4×4). A game starts with two tiles; after every move that changed
  something one new tile appears in a random empty cell, a 2 (90 %) or a 4 (10 %).
- A move slides every tile as far as it goes. Two equal tiles that meet merge into their sum; a tile merges at most
  once per move, and merging starts at the leading edge (a row `2 2 2 _` moved left gives `4 2 _ _`, `2 2 2 2` gives
  `4 4 _ _`). A move that changes nothing does nothing: no new tile and no undo step.
- The score grows by the value of every merged tile. "Best" is the best score of the current size.
- The goal tile depends on the size. Reaching it shows a status text; play goes on, with no dialog.

| Size | Goal |
|---|---|
| 3×3 | 512 |
| 4×4 | 2048 |
| 5×5 | 4096 |
| 6×6 | 8192 |

- The game is lost when the board is full and no neighbouring tiles are equal.
- Tiles slide in about 100 ms, and merged and new tiles pop briefly. A move made during an animation finishes it first.

## Undo
One step, with U, Backspace or the Undo button: it takes back the last move and its score. It is possible only right
after a move (not twice in a row) and also after a lost game. The best score keeps the highest score reached, even one
that was undone.

## Sizes and New Game
The toolbar menu shows the current size and offers 3×3 to 6×6; choosing another size starts a new game, choosing the
current one does nothing. New Game (F2 or the button) starts a new game of the same size.

While the game is running (not lost) and the score is above 0, both ask first: title "New Game" or "Change Size",
header "Abandon this game?", text "Its score of 1,204 is lost; the best score is kept.", the buttons "New Game" or
"Change Size", and Cancel, which is the default. Closing the dialog counts as Cancel.

## Keys
| Input | Action |
|---|---|
| Arrow keys, W A S D | Move |
| U, Backspace | Undo |
| F2 | New game |
| Mouse drag (press, drag, release) | One move in the dominant direction of the drag |
| Trackpad swipe (macOS) | One move in the swipe direction |

The keys work only while the board has the focus, and not with Shortcut, Ctrl, Meta or Alt. A drag counts from a
distance of 16 pixels or a third of a tile, whichever is larger. The swipe is a JavaFX `SwipeEvent`, which only macOS
sends; it is not checked in the sandbox.

## Status texts
- "Reach 2048" while playing (the goal of the size).
- "2048 reached! Keep going, or F2 for a new game" once the goal tile exists.
- "No moves left: U to undo, F2 for a new game" after losing, or "No moves left: F2 for a new game" when undo is not
  possible.

## Look
Own colours, not the original's beige and orange: a ramp from cool to warm. Small tiles are slate and teal, then blues
and violets, then magenta to gold; from 4096 up: dark tiles with gold text. Light and dark variants
follow the theme (`prefers-color-scheme`). The font shrinks with the number of digits. Toolbar buttons: New Game
(tooltip "New Game (F2)") and Undo (tooltip "Undo (U)"). The tab's tooltip is "Slide and merge tiles to reach the
goal".

## Stored
User-wide in `~/.sluide/shared.json`, field `doubles` ([ADR 0101](../adr/0101-shared-data-file.md)): the last size and
the best score per size.
```json
"doubles": { "size": 4, "best": { "3": 812, "4": 20512 } }
```
`size` is the side (3 to 6) and the keys of `best` are sides too. An invalid size reads as 4×4, an invalid best score as none. Written
a moment after a change, off the FX thread.

## Not included
Resuming after a restart, statistics, more than one undo step and a running game kept per size:
[ideas/games.md](../ideas/games.md).

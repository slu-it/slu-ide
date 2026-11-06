# 0136. Doubles: a third game of the Games menu, a sliding-tile merge game

- Status: accepted; follows [0131](0131-games-menu-minesweeper.md) and [0132](0132-word-guess.md)
- Date: 2026-11-06

## Context
The user asked for "2048" in the Games menu. That is the title of one specific game; the rules of the genre (slide
all tiles, equal tiles merge into their sum, a new tile appears after each move) are not protected. As with Word
Guess, the game gets its own name and its own look.

## Decision
- **Doubles**, the third item of the Games menu, a `WindowTool` like the others (one tab per window). Own name and own
  look: cool-to-warm tile colours, not the original's beige and orange.
- **Rules**: every tile slides as far as it can; equal tiles meeting merge into their sum, a tile merges at most once
  per move and merging starts at the leading edge (`[2,2,2,0]` left gives `[4,2,0,0]`, `[4,0,4,8]` gives `[8,8,0,0]`).
  The score grows by each merged tile's value. After a move that changed something one tile spawns: a 2 (90 %) or a
  4 in a random empty cell. A move that changes nothing does nothing. The game is over when the board is full and no
  two orthogonal neighbours are equal.
- **Sizes** 3×3 to 6×6 with the goals 512, 2048, 4096 and 8192 (a 2048 tile on 3×3 is practically unreachable).
  Reaching the goal shows a status text; play goes on.
- **One-step undo** (U, Backspace, toolbar button) after a move, also when the game is over. The best score keeps the
  highest score ever reached, even if the move is undone.
- **New Game and a size change** ask "Abandon this game?" while the game runs with a score above 0.
- **One game per window**, outliving the tab like the other games, not restored after a restart.
- **Animation**: slide and pop, about 100 ms.
- **Input**: arrow keys, W/A/S/D, mouse drag and trackpad swipe (macOS); F2 for a new game.
- **Storage**: `shared.json` ([0101](0101-shared-data-file.md)), field `doubles`:
  `{"size": 4, "best": {"3": 812, "4": 20512}}` (last size, best score per size).
- **Code**: the `games` module, package `dev.sluide.games.doubles` (FX-free rules, model, key actions, gestures and
  tile sizes with unit tests; [0084](0084-testability-logic-out-of-controls.md)); the window bean `Doubles` is in the
  root package `dev.sluide.games`.

Considered:
- The name "2048": it is the title of one specific game (see Context).
- A fixed goal of 2048 on all sizes: unreachable on 3×3, trivial on 6×6.
- Unlimited undo: it makes the best score meaningless.
- A game kept per size: more state for little gain; a size change starts a new game.

## Consequences
- Feature page [../features/doubles.md](../features/doubles.md) (added with the view).
- Keys work only while the board has the focus; see [../features/keyboard-shortcuts.md](../features/keyboard-shortcuts.md).

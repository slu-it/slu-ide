# Sudoku

**Modules:** `modules/games` (window bean `Sudoku` in `dev.sluide.games`; package `dev.sluide.games.sudoku`: the engine
(`Solver`, `Grader`, `Generator`), the pure game in `SudokuGame`, the FX-free `SudokuModel`, `SudokuPreferences`, UI in
`ui`; shared with Minesweeper: `GameClock` in `dev.sluide.games.common`, the pause's `TabVisibility` and `PauseOverlay`
in `dev.sluide.games.common.ui`);
menu in `ide` (`ToolsMenu`, `WindowTools`); decisions in [ADR 0137](../adr/0137-sudoku.md) and
[ADR 0131](../adr/0131-games-menu-minesweeper.md)

The number puzzle: fill every row, column and box with each digit once. A tab of the editor area with a toolbar, the
board with a number pad, and a status line. Puzzles are made on the spot and can always be solved by reasoning.

## Opening
- **Games → Sudoku** opens the tab. There is one per window: choosing the item again selects the open tab.
- Shortcut+W closes the tab. The game belongs to the window: after closing, a new tab shows the same game, with the
  clock paused meanwhile. After a restart a new puzzle starts; only the last size and level and the best times are kept.
- The first puzzle is made when the tab first shows; the board reads "Generating…" and takes no input until it is there.

## Sizes and levels
Two toolbar menus, **Size** and **Level**, each showing the current choice:

| Size | Boxes |
|---|---|
| 4×4 | 2×2 |
| 6×6 | 2 rows × 3 columns |
| 9×9 | 3×3 |

Levels: Easy, Medium, Hard and Expert. That makes 12 combinations, each with its own best time. The default is 9×9
Medium. Choosing the current size or level does nothing; another one starts a new puzzle (after the guard question, see
below) and is remembered.

## How puzzles are made
- A random full grid comes from randomised backtracking. Then givens are removed in random order, on 9×9 in pairs that
  are symmetric by a 180° turn. A removal is kept only while the puzzle still has exactly one solution and can still be
  solved by the grader without guessing.
- The grader solves like a person and reports the hardest technique it needed:

| Tier | Techniques |
|---|---|
| T1 | Naked and hidden singles |
| T2 | Naked and hidden pairs, pointing and claiming |
| T3 | Naked and hidden triples, X-Wing |
| T4 | Swordfish, XY-Wing |

- 9×9: Easy needs T1 only (removal stops at 38 givens), Medium aims at T2, Hard at T3 and Expert at T4. The attempts
  have a time budget of about 3 seconds; when no puzzle of the exact tier turns up, the best candidate not above the
  tier is used (the hardest one, with the fewest givens).
- 4×4 and 6×6 aim at a number of givens only (they are still unique and solvable without guessing):

| Level | 4×4 | 6×6 |
|---|---|---|
| Easy | 8 | 18 |
| Medium | 6 | 15 |
| Hard | 5 | 12 |
| Expert | 4 | 10 |

- Generation runs off the FX thread on its own thread. When a puzzle starts, the next one of the same size and level is
  made in advance, so New Game is usually instant; a size or level change drops a prefetch that no longer fits. If no
  puzzle can be made, the status reads "Could not generate a puzzle — F2 to try again".

## Playing
- Click a cell to select it, or move with the arrow keys or W A S D. A given cannot be changed.
- A digit key or a pad button places the digit in the selected cell. Placing the same digit again does nothing.
- **Mistakes: conflicts only.** An entry that clashes with another digit in its row, column or box is shown red, and so
  is the entry it clashes with; a given that takes part in a clash gets a red tint. A wrong entry that clashes with
  nothing is never revealed. The puzzle is solved when the grid is full and has no conflicts: the clock stops, the board
  takes no more input, a sweep plays across the board and the time is recorded as a best time if it is one.
- **Notes** are small candidate digits in a cell. N, the Notes toggle of the toolbar or the pad switches Notes mode: then
  digits toggle notes. Shift+digit toggles a note in any mode (read from the key code, because Shift+1 types "!"). A
  cell with a digit takes no notes. Placing a digit clears that cell's notes and removes the digit from the notes of its
  row, column and box; both are one undo step.
- **Fill Notes** (toolbar) puts every digit that is still possible, judged from the givens and entries, into every
  empty cell, replacing the notes there. It is one undo step and costs nothing.
- **Hint** (H or the lightbulb): adds **30 seconds** to the time, still counts for the best time, and is shown in the
  result. If an entry is wrong (differs from the solution), it removes one (the selected one if that is wrong, else the
  first), flashes the cell red and says "This 7 was wrong". Otherwise it fills the selected cell if that is empty, or
  else the cell of the easiest step of the grader, and selects it. A hint is an undo step, but undoing it keeps the
  penalty and the hint count.
- **Undo and Redo**: unlimited. Steps are a placed digit, an erased digit or note set, a note toggle, Fill Notes and a
  hint. A new step clears the redo steps. Both are off once the puzzle is solved.
- **Restart** (toolbar) clears every entry and note back to the givens and forgets the undo history; the clock keeps
  running and the hints and their penalty stay. It asks first when anything was entered: title "Restart", header "Clear
  all entries?", text "The puzzle starts over from its givens; the clock keeps running.", buttons "Restart" and Cancel.
- **New Game** (F2 or the button) starts a new puzzle of the same size and level.
- **Guard:** New Game, a size change and a level change ask first when the puzzle is not solved and has at least one
  entry or note: title "New Game", "Change Size" or "Change Level", header "Abandon this puzzle?", text "Its time of
  4:12 is lost.", the verb as button and Cancel, which is the default. Closing the dialog counts as Cancel.

## Timer and pause
The timer in the toolbar shows m:ss (h:mm:ss from an hour), hint penalties included. It starts when the puzzle
appears, not while it is made. It pauses by itself while the tab is hidden or the window is iconified. P or the Pause
button pauses by hand (the button then shows Resume) and covers the board with the "Paused" overlay, which hides the
digits; clicking the overlay resumes. While paused or solved, input and the buttons that change the puzzle are off.

## Layout and look
- The toolbar (a wrapping toolbar): Size, Level, New Game ("New Game (F2)"), Restart ("Restart Puzzle"), Undo
  ("Undo (U)"), Redo ("Redo (Shift+U)"), Notes ("Notes (N)"), Fill Notes, Hint ("Hint (H)"), Pause ("Pause (P)") and
  the timer at the end. The tab's tooltip is "Fill every row, column and box with each digit once".
- The board is square and fills the tab; a cell is between 28 and 64 pixels (below that the tab scrolls). Lines between
  boxes are thick.
- The **number pad** sits beside the board, or below it when that gives larger cells (a tab that is taller than wide).
  It has the digits in three columns, each showing how many are still missing (greyed out at 0), Erase ("Erase (0,
  Backspace)") and a Notes toggle; in Notes mode the pad looks different.
- **Highlighting:** the row, column and box of the selected cell get a soft shade; cells holding the selected digit a
  stronger one, and that digit is emphasised in notes.
- Colours (`sudoku.css`, light and dark): givens bold in the text colour, entries blue, notes small and muted,
  conflicts red.
- **Flashes** (about 400 ms): a row, column or box that becomes full and conflict-free fades a tint; solving plays a
  diagonal sweep; a hint that removes a wrong entry flashes the cell red.

## Keys
| Input | Action |
|---|---|
| Click | Select a cell |
| Arrow keys, W A S D | Move the selection (the first press selects the top-left cell) |
| 1 to N (also on the numpad) | Place the digit, or toggle the note in Notes mode; digits above the size are ignored |
| Shift+digit | Toggle a note |
| 0, Backspace, Delete | Erase the digit; of a cell without a digit, all its notes |
| N | Notes mode |
| H | Hint |
| P | Pause or resume |
| F2 | New game |
| U, Shortcut+Z | Undo |
| Shift+U, Shortcut+Shift+Z | Redo |

The keys work only while the board has the focus, and not with Ctrl, Meta or Alt; with Shortcut only Z and Shift+Z
count (Cmd on macOS). Shortcut+Z has no menu entry; the board takes it.

## Status texts
- "Generating a puzzle…" while one is made; "Could not generate a puzzle — F2 to try again" if that failed.
- While playing: "Best: 12:34" when the size and level have a best time, else "Fill every row, column and box with
  1–9" (the last digit is the size).
- "This 7 was wrong" after a hint removed a wrong entry, until the next step or selection.
- After solving: "Solved in 12:34", then " with 1 hint (+0:30)" or " with 2 hints (+1:00)" when hints were used, then " —
  new best!" for a record.

## Stored
User-wide in `~/.sluide/shared.json`, field `sudoku` ([ADR 0101](../adr/0101-shared-data-file.md)): the last size and
level and the best time per combination. Plain values, no secrets.
```json
"sudoku": { "size": 9, "level": "medium", "best": { "9-hard": 754000, "4-easy": 21000 } }
```
`size` is the side (4, 6 or 9), `level` is `easy`, `medium`, `hard` or `expert`, and the keys of `best` are
`<side>-<level>` with the time in milliseconds, hint penalties included. An invalid or missing value reads as 9×9
Medium and no best time; other fields of `sudoku` stay as they are. Written a moment after a change, off the FX thread.

## Scaling
The cell size follows the tab between 28 and 64 pixels and the pad and the fonts with it. The Paused overlay and the
Generating text never change the board's size.

## Not included
Resuming after a restart, statistics per level, a hint that explains the step, digit-first input on the pad, a mode
with three mistakes and variants such as Killer or diagonal Sudoku: [ideas/games.md](../ideas/games.md).

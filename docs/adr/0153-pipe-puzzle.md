# 0153. Pipe Puzzle: a ninth game of the Games menu, turning tiles to connect a flow, in a Network and a Path mode

- Status: accepted; see [0131](0131-games-menu-minesweeper.md) and [0137](0137-sudoku.md)
- Date: 2026-11-09

## Context
The Games menu gets a pipe puzzle: fluid, power or light has to get from a source to a drain, and the player turns
tiles until the way is clear. Puzzles are generated at random, so they must be provably solvable. The genre has two
styles, turning tiles that are already laid and laying pipes against a timer; the request asked for turning. The game
follows no specific title; it is called by its genre, **Pipe Puzzle**, and has its own look. It is a turn-based puzzle
like Sudoku and reuses its parts (`GameClock`, `PauseOverlay`, board focus, generating off the FX thread).

## Decision
**Game**
- **Pipe Puzzle**, the ninth item of the Games menu, after Mini Golf: a `WindowTool` without key binding, one tab per
  window; the game outlives the tab (window bean `PipePuzzle`) and is lost on a restart. Package
  `dev.sluide.games.pipepuzzle`, `shared.json` field `pipePuzzle`.
- A **square grid with a tile in every cell**: an end (1 opening), a straight, a corner, a T and, rarely, a cross. The
  **source** and the **drain** are fixed tiles with one opening, at least a side apart (Manhattan distance). Turning a
  cross does nothing. A left click turns a tile clockwise, a right click counter-clockwise; keys move a cursor.
- **Sizes** 5×5, 7×7, 9×9 and 12×12 with **closed edges**, no wrap-around: an opening toward the edge never matches.
- After every turn, the tiles connected to the source through matching openings are drawn filled, and an unmatched
  opening of a filled tile shows a leak.

**Two modes**
- **Network**: every tile belongs to one spanning tree that includes the source and the drain. Solved when every tile
  is filled and nothing leaks; the tile set has 2 × (cells − 1) openings, so no loop is possible then. Each puzzle has
  **exactly one solution**, and a **deduction-only solver** (`NetworkSolver`, no guessing or backtracking) reaches it:
  edge agreement with closed borders, no loop, and no closed-off part smaller than the board. The generator draws a
  random spanning tree (Wilson's loop-erased walks, at most 5 % crosses and 35 % ends), makes two far-apart leaves the
  source and the drain, and keeps the tree only when the solver solves its tile set; while it is stuck, an edge near
  an ambiguous cell is swapped for another one of the loop it closes.
- **Path**: a random self-avoiding route from the source to the drain (at least 1.5 × side cells), a few short spurs
  off it that end in end-caps (at least side / 3, at least 1), and random decoys in every other cell. Solved when the
  flow reaches the drain and the filled network is **sealed**, every opening of a filled tile meeting a matching one.
  Unconnected decoys don't matter, and **any sealed route wins**; uniqueness is not required.
- **Start**: every turnable tile gets a random turn, and at least 60 % of the turnable tiles other than crosses start
  wrong; a start that is already solved is rolled again.
- **Hint** turns one wrong tile, chosen at random, into its solution turn, compared by openings, so both turns of a
  straight that look right are right. In Path mode it compares only the generated route and its spurs, so it
  **follows the generated route** and never spends a hint on a decoy.

**Clock and records**
- A relaxed clock as in Sudoku: it starts with the first turn and pauses while the tab is hidden or the game is paused.
  A **best time per mode and size**; each hint adds **30 s**.
- **Restart** turns every tile back to the start and **resets the clock and the hints**. Someone can replay a known
  puzzle for a better best time; the user accepted that.

**Looks**: Water, Power and Light are a **drawing choice only** (pipes and water, cables and current, fibres and
light), each with its own palette on a dark board in both themes. They change nothing in the rules.

**Code** ([0084](0084-testability-logic-out-of-controls.md)): the engine is pure Kotlin without JavaFX: `Geometry`
(`Dir`, `Openings`, `Pos`), `Puzzle`, `Flow` (filled tiles, leaks, solved per mode, fill order), `NetworkSolver`,
`NetworkGenerator`, `PathGenerator`, `Scramble`, `Generator` and `PipePuzzleGame`. Generation is deterministic for a
seeded `Random`. Tests prove on every generated puzzle of 50 seeds per mode and size what the modes promise: Network
puzzles are spanning trees that the solver solves to exactly the stored solution (and a search over every turn finds
no second one on 5×5 and 7×7), Path solutions are sealed and reach the drain.

Left out (to be listed in [the games ideas](../ideas/games.md)): wrap-around edges, locking tiles, undo and redo, a rotation counter
against the minimum, a coloured mode with several source-drain pairs, a timed flow countdown, a move limit, a daily
puzzle, hexagonal tiles and resuming a game after a restart.

## Consequences
- A generated Network puzzle is unique and solvable by reasoning alone, which the tests prove per puzzle; a stronger or
  weaker solver changes which trees are kept, not the rules.
- On closed-edge boards the borders decide much: most random trees are solved at once, and generating a 12×12 board
  takes well under a millisecond.
- Path puzzles may have several solutions; the win check accepts every sealed route, and only the hint is tied to the
  generated one.
- The model, painters, view, feature page and keyboard shortcuts are added on top of this engine.

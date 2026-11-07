# 0138. Solitaire: a fifth game of the Games menu, with only winnable, numbered deals

- Status: accepted; follows [0131](0131-games-menu-minesweeper.md), [0132](0132-word-guess.md),
  [0136](0136-doubles.md) and [0137](0137-sudoku.md)
- Date: 2026-11-07

## Context
The Games menu gets Solitaire. There are several well-known patience games; a random deal of Klondike is often
unwinnable, which frustrates more than it challenges. Players like to replay or share a deal, so a deal needs a name
that stays valid. The rules of these games are not protected; card faces of existing products are.

## Decision
- **Solitaire**, the fifth item of the Games menu, a `WindowTool` like the others, with one tab and three games:
  - **Klondike**, Draw 1 or Draw 3, unlimited recycling of the waste;
  - **FreeCell**, 4 cells, with supermoves (a run moves as far as free cells and empty columns allow);
  - **Spider**, 1, 2 or 4 suits.
- **Pure engine** in the `games` module, package `dev.sluide.games.solitaire` ([0084](0084-testability-logic-out-of-controls.md)):
  an immutable `Table` of `Pile`s, `Move`s, and per game a `Rules` implementation that deals, lists and checks the
  legal moves, applies them (with flips and points) and tells won and decided (auto-finish) tables. No UI, no shared
  state.
- **Only winnable deals**: every deal offered is proven winnable by a solver that knows the whole deal (face-down
  cards and stock included). Solver and measurements: see "The solver" below.
- **Deal numbers** 1..1,000,000 per mode. A deal is a Fisher–Yates shuffle of the ordered deck (by suit, then rank)
  driven by an own SplitMix64 generator, seeded from the number and a constant per mode, so a number shows the same
  deal on every JDK and Kotlin version; the modes get different deals for the same number. Fixed test vectors guard
  it.
- **Winnable numbers**: New Game picks a random number and scans upward from it (after 1,000,000 on at 1) until the
  solver proves a deal winnable within a per-deal budget; deals it finds unsolvable or cannot decide in time are
  skipped. The set of numbers offered is therefore what the solver of that version proves: a solver change can change
  which numbers are offered, never what a number deals. Deal Number… plays a typed number only when it is proven
  winnable, otherwise it offers the next winnable one.
- **Classic points per mode**:
  - Klondike: waste→tableau +5, to a foundation +10, a tableau card turned face up +5, foundation→tableau −15,
    recycling the waste −100 (Draw 1) or −20 (Draw 3), −2 per 10 s, and for games of 30 s or more a time bonus of
    700,000 / seconds; never below 0;
  - Spider: start at 500, −1 per move, +100 per completed run;
  - FreeCell: +10 per card to a foundation, −1 per move, the same time bonus;
  - every game: undo −1, hint −20.
- **FreeCell rules**: 8 columns (the deal goes row by row, 7 cards on columns 0..3, 6 on the others), 4 cells, 4
  foundations; a run of n cards moves if n ≤ (free cells + 1) × 2^(empty columns), the target column not counted. A
  table is decided (auto-finish) when every column descends strictly from bottom to top: the lowest card left is then
  always playable to a foundation. Cell to cell is refused; `legalMoves` lists a column card only for the first
  empty cell.
- **Spider rules**: 10 columns, 54 cards dealt (only each column's top card face up), 50 in the stock; any top part of
  a same-suit run moves onto a card one higher of any suit or onto an empty column; dealing a row needs a stock and
  no empty column. A K..A run of one suit leaves for the next empty foundation by itself (checked after every move,
  flipping the card it uncovers); Spider is never decided, there is no auto-finish.
- **Own drawn cards and win effect**: the card faces (pip layouts for A to 10, own emblems for J, Q and K) and backs
  are drawn for Thoreau and cached as images per card size; no card set is copied. The win burst (cards flying out of
  the foundations in arcs with gravity, then fading) is our own physics, not a copy of another game's win effect.
- **Keyboard**: only the arrow keys move the cursor; D clicks the stock (draw, recycle, deal a row), so W A S do
  nothing (D would otherwise be both "right" and "draw"). Space picks up and drops, Enter drops or plays the best
  move, Esc cancels, F sends to a foundation.
- **Click and double-click**: a click on a card makes its best move, foundation first; a click on a foundation card does
  nothing (cards leave a foundation only by dragging); the second click of a double-click is ignored, so a
  double-click acts as one click and sends a card to a foundation when it can, with no separate double-click rule.
- **Hints show, they do not play**: the solver highlights the next move of a winning line. A hint costs −20 points only
  when it shows a move; "No winning line from here — undo N moves" (found by re-solving earlier positions) and "No
  hint found" are free.
- **Nothing moves by itself** until the game is decided; then auto-finish plays the rest. Spider has none beyond
  completed runs leaving by themselves.

Considered:
- Random deals: many Klondike deals cannot be won, and a lost game that never had a chance frustrates.
- `java.util.Random` (or `kotlin.random.Random`) for the shuffle: its sequence is not guaranteed to stay the same
  across versions, and deal numbers would silently change.

## Consequences
- The deal of a number must never change: the deck order, the seed constants and the shuffle are fixed; a change
  breaks every number players have noted, and the test vectors fail.
- Proving every deal winnable costs a solver run per new game; the solver package decides how and measures it.
- The rules are tested without a toolkit; the UI only renders a `Table` and sends `Move`s.

## The solver
Package `dev.sluide.games.solitaire.solver`, pure Kotlin. `Solver.solve(table, budget, cancelled)` returns `Solved`
(real `Move`s, replayed through the rules before they are returned), `Unsolvable` or `Unknown`; it holds no state, so
calls may run in parallel. The per-variant part is a `VariantSolver`: `KlondikeSearch`, `FreeCellSearch`,
`SpiderSearch`.
- **Klondike**: a "thoughtful" depth-first search on its own compact state (bytes per column, the stock and waste as
  one cycle in draw order), a transposition table of 64-bit position hashes (columns summed, so a permutation of
  columns hashes alike), a copy of the state per node. The stock is a macro move ("draw or recycle until card X is on
  top, then play X to a foundation or a column", unlimited recycling), expanded into `Draw`/`Recycle`/`Transfer`.
  Safe foundation moves are played without branching: Aces and 2s, and a card of rank r when both foundations of the
  other colour are at r-1 or more and the other suit of its colour is at r-2 or more (the last condition because a
  card taken back from a foundation may still need it as a base). Moves are tried by a score: foundation and uncovering moves first.
- **Two passes**. The first prunes heuristically (a run is moved partway only when the card below it can then go up;
  no card is taken back from a foundation; with Draw 3 the waste top is played automatically, which shifts the groups
  of three) and finds nearly every solution fast. Only when it is exhausted without a line, the second pass runs
  without these heuristics (it offers foundation-to-column moves and prunes only soundly: interchangeable empty
  columns, no King column to an empty column, safe foundation moves); only its exhaustion gives `Unsolvable` (up to a
  64-bit hash collision). Budget, cancel and a cap of two million positions end a search as `Unknown`.
- **Measured** (JDK 25, one slow-ish sandbox core, warm JVM, 3 × 200 consecutive numbers from 1, 250,000 and 600,000;
  the cold first solve took 31 ms):

  | Mode | Budget | Solved | Unsolvable | Unknown | Median / p90 time of a Solved deal |
  |---|---|---|---|---|---|
  | Draw 1 | 0.25 s | 78.5% | 1.0% | 20.5% | < 1 ms / 32 ms |
  | Draw 1 | 1 s | 82.0% | 2.8% | 15.2% | < 1 ms / 75 ms |
  | Draw 1 | 3 s | 84.8% | 3.5% | 11.7% | < 1 ms / 121 ms |
  | Draw 3 | 0.25 s | 77.8% | 8.0% | 14.2% | < 1 ms / 17 ms |
  | Draw 3 | 1 s | 80.3% | 9.2% | 10.5% | < 1 ms / 31 ms |
  | Draw 3 | 3 s | 80.8% | 9.8% | 9.3% | < 1 ms / 35 ms |

  Time until `firstWinnable` returns from 100 random numbers (median / p90 / max): Draw 1 at 0.25 s per deal
  1 / 250 / 512 ms, at 1 s 5 / 1000 / 3501 ms; Draw 3 at 0.25 s 2 / 257 / 750 ms, at 1 s 1 / 1000 / 2009 ms. Most
  winnable deals are decided in a few milliseconds; the rest are the hard tail, which a larger budget only slowly
  shrinks.
- **Memory**: the table of seen positions holds 64-bit hashes in an open-addressing array that is at most half
  full, so about 32 MB at the cap of two million positions per pass (more briefly while it doubles); a prefetch and a
  hint running at the same time double that. FreeCell and Spider hold more per position (the queued state, the
  node, its moves), so their caps are lower and one search keeps under about 64 MB of heap at any budget: a queued
  position is stored as packed bytes (about 70 bytes FreeCell, 130 Spider; unpacked when it is expanded), about 170 to
  200 bytes per position seen in all. Caps: FreeCell 250,000 positions; Spider 250,000 positions per stage of the
  beam (its table is new for every stage, because a deal cannot be undone, and a full table ends the stage, it does
  not fail the run), 20,000 for the sound search after the first beam and 250,000 for the last one. Checked with a
  small heap: Spider 4 suits with a 20 s budget runs without an out-of-memory error at -Xmx48m, FreeCell hard deals at
  -Xmx56m (the JVM and the tools included). The caps cost FreeCell about 0.5% of its deals (99.8% to 99.3% solved)
  against 400,000; the Spider beam gained from the per-stage table.
- **FreeCell**: a best-first search (`BestFirst`, shared with Spider) over `FreeCellState` (a byte array per column,
  cells as a set, foundation heights by suit) with the same 64-bit hash table; columns and cells are interchangeable
  in the hash. It prunes soundly only, so an emptied queue is `Unsolvable` (a full 52-card table with no move left is
  the test); the cap of 250,000 positions, budget and cancel give `Unknown`. The proof holds only up
  to a 64-bit hash collision (about n^2/2^65 for n positions). Pruning: safe foundation moves at once (a
  card goes up when both foundations of the other colour hold its rank minus 1 or more: nothing can still be put on
  it, and FreeCell never takes a card back from a foundation, so keeping it down helps nothing), a card goes to one
  cell not to every free cell, only the first empty column is a target and a whole column never moves to an empty
  one. A supermove is one move with its count within the rules' capacity. Priority: cards not up, cards lying on a
  lower card of their column, burial of each suit's next card, cells used, empty columns in favour, plus the depth.
- **Spider** (thoughtful: it sees the face-down cards): moves are runs of one suit, partial runs included, and
  `DealRow` (only without an empty column, as in the rules); completed runs leave by themselves. Two parts. A **staged
  beam** (`SpiderBeam`) searches the moves between two deals best-first for a limited number of positions, takes the
  best positions that can deal (by the priority: face-down cards, breaks between cards that do not continue a run of
  one suit, empty columns, runs done), deals them and repeats; if it loses, it restarts wider (2k nodes x 3, 8k x
  10, 30k x 30, 100k x 100 per stage, a stage also ends at its position cap). A best-first search without heuristic
  cuts (`SpiderSpace`, sound pruning only: first empty column, no whole-column move to an empty one) runs after the
  first beam (20,000 positions) and last (250,000), and only its exhaustion gives `Unsolvable` (up to a 64-bit hash
  collision, as in the other searches); for a real deal it never finishes, `Unknown` is the normal answer. Tried and dropped: plain best-first on the whole game (75% of one-suit deals at best, it floods the
  plateau after the deals), and heuristic cuts ("only moves that build a same-suit run, uncover a card or empty a
  column") in one best-first pass (worse: 40%). For four suits the weight of a break is what matters: with breaks
  weighing about as much as a face-down card, 4-suit deals went from 0% to 85% of a sample in 3 s.
- **Measured, FreeCell and Spider** (same setup, 3 x 200 numbers from 1, 250,000 and 600,000):

  | Mode | Budget | Solved | Unsolvable | Unknown | Median / p90 time of a Solved deal |
  |---|---|---|---|---|---|
  | FreeCell | 0.25 s | 99.3% | 0% | 0.7% | 5 ms / 28 ms |
  | FreeCell | 1 s | 99.3% | 0% | 0.7% | 4 ms / 28 ms |
  | FreeCell | 3 s | 99.3% | 0% | 0.7% | 5 ms / 28 ms |
  | Spider 1 suit | 0.25 s | 99.8% | 0% | 0.2% | 31 ms / 44 ms |
  | Spider 1 suit | 1 s | 100% | 0% | 0% | 32 ms / 46 ms |
  | Spider 1 suit | 3 s | 100% | 0% | 0% | 32 ms / 45 ms |
  | Spider 2 suits | 0.25 s | 93.2% | 0% | 6.8% | 38 ms / 152 ms |
  | Spider 2 suits | 1 s | 97.8% | 0% | 2.2% | 40 ms / 183 ms |
  | Spider 2 suits | 3 s | 99.3% | 0% | 0.7% | 43 ms / 207 ms |
  | Spider 4 suits | 0.25 s | 51.7% | 0% | 48.3% | 66 ms / 224 ms |
  | Spider 4 suits | 1 s | 76.3% | 0% | 23.7% | 186 ms / 751 ms |
  | Spider 4 suits | 3 s | 86.5% | 0% | 13.5% | 206 ms / 1277 ms |

  The Unknown rest is partly unwinnable deals (FreeCell: a few per thousand) and partly the search missing a line
  or reaching a cap; the solver cannot tell which. Time until `firstWinnable` returns from 100 numbers with the
  default budget of the mode (median / p90 / max): FreeCell 5 / 31 / 145 ms, Spider 1 suit 33 / 46 / 281 ms, Spider 2
  suits 39 / 211 / 1040 ms, Spider 4 suits (1 s) 244 / 1267 / 3033 ms. The first solve in a cold JVM is inside the
  maximum of the first sample (145 ms for FreeCell).
- **Default per deal** (`DealGenerator.defaultPerDeal`): 250 ms for Klondike and FreeCell, 500 ms for Spider 1 suit,
  1 s for Spider 2 and 4 suits. Skipped by the budget, about: FreeCell 0.7%, Spider 1 suit 0%, 2 suits 2%, 4 suits
  24% (a New Game then scans a few numbers more; the median is still about 0.25 s). For Klondike, New Game comes back
  in a few milliseconds at the median and within about half a second at the worst of the sample, and a prefetch hides
  that; the cost is that about 4% of the deals a 1 s budget would prove winnable are skipped. The set of offered
  numbers is "what the solver proves in its budget", and still every offered deal is proven. Those numbers can
  change with the solver (see Consequences).

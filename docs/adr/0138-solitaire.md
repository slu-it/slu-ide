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
  cards and stock included). Solver and measurements: to be filled by the solver package.
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
- **Own drawn cards**: the card faces and backs are drawn for Thoreau; no card set is copied.

Considered:
- Random deals: many Klondike deals cannot be won, and a lost game that never had a chance frustrates.
- `java.util.Random` (or `kotlin.random.Random`) for the shuffle: its sequence is not guaranteed to stay the same
  across versions, and deal numbers would silently change.

## Consequences
- The deal of a number must never change: the deck order, the seed constants and the shuffle are fixed; a change
  breaks every number players have noted, and the test vectors fail.
- Proving every deal winnable costs a solver run per new game; the solver package decides how and measures it.
- The rules are tested without a toolkit; the UI only renders a `Table` and sends `Move`s.

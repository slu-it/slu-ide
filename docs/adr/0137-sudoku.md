# 0137. Sudoku: a fourth game of the Games menu, with a generator that grades by human techniques

- Status: accepted; follows [0131](0131-games-menu-minesweeper.md), [0132](0132-word-guess.md) and [0136](0136-doubles.md)
- Date: 2026-11-06

## Context
The Games menu gets Sudoku. Puzzles have to come from somewhere, and "Easy" or "Expert" has to mean something. The
rules of Sudoku are not protected; the game gets a window like the other games.

## Decision
- **Sudoku**, the fourth item of the Games menu, a `WindowTool` like the others. Sizes 4×4 (2×2 boxes), 6×6 (boxes of
  2 rows × 3 columns) and 9×9 (3×3 boxes); levels Easy, Medium, Hard, Expert.
- **Runtime generator** instead of a bundled puzzle list: a list has licence questions and is not endless. Per
  attempt a random full grid is built by randomised backtracking, then cells are removed while the solution stays
  unique. Everything is pure Kotlin in the `games` module, package `dev.sluide.games.sudoku` (deterministic given a seeded
  `Random` and an injected clock, no UI, no shared state; [0084](0084-testability-logic-out-of-controls.md)).
- **Difficulty by a human-technique grader** instead of the givens count alone, which misgrades (a 24-given puzzle can
  be solved by singles, a 30-given one can need a Swordfish). The grader solves with candidate sets and always uses the
  easiest technique that makes progress; the puzzle's grade is the highest tier used:
  - T1: naked single, hidden single;
  - T2: locked candidates (pointing, claiming), naked pair, hidden pair;
  - T3: naked triple, hidden triple, X-Wing;
  - T4: Swordfish, XY-Wing.

  The techniques work for rectangular boxes, so 6×6 uses the same code.
- **Never guessing**: a puzzle is only offered when the grader solves it completely with these techniques (so it is
  also unique). Puzzles where the techniques get stuck are discarded, however hard they are (the Arto Inkala puzzle
  grades as "stuck"). The same grader gives the hint (the next deduction with its tier).
- **9×9**: Easy = T1, Medium = T2, Hard = T3, Expert = T4. Givens are removed in 180° rotational pairs, a pair only
  when the puzzle stays unique and its grade does not exceed the level's tier; Easy stops at 38 givens (lands at
  37 or 38: pairs plus the lone centre cell). An attempt is accepted when its grade equals the target tier.
  Otherwise the best attempt (highest grade within the target, then fewest givens) is remembered and a new attempt
  starts while the budget (3 s) lasts; then the best attempt is returned. The first attempt always completes, so a puzzle always comes back.
- **4×4 and 6×6**: no symmetry, graded by the number of givens: 4×4 Easy 8, Medium 6, Hard 5, Expert 4; 6×6 18, 15, 12,
  10. Single cells are removed in random order while the puzzle stays unique and gradeable; a retry with a new grid
  follows within the budget, then the attempt with the fewest givens is returned. All targets were reached within the
  budget in measurements (4×4 Expert needs 2–3 attempts on some seeds), so no number had to be adjusted.
- **Measured** (several seeds, warm JVM): 4×4, 6×6 and 9×9 Easy take under 1 ms and Medium (about 1–11 attempts)
  about 5–30 ms. 9×9 Expert needs 3–50 attempts, about 3–60 ms. 9×9 Hard is the slow one: 3–418 attempts, about
  3–500 ms, median about 170 ms, because T3 as the hardest technique is rare among greedily reduced puzzles. A cold
  first run (JIT not warm) can spend the whole 3 s budget on Hard and fall back to the best attempt, a T2 puzzle;
  prefetching the next puzzle in the UI package hides most of that.
- **UI decisions** (later packages): size and level are two separate toolbar menus; the best time is kept per size and
  level (12 best times). Mistake feedback shows conflicts only (a digit twice in a unit); a wrong entry without a clash
  stays hidden. The hint fixes a wrong entry first, otherwise fills a cell; each hint adds 30 s and the time keeps
  counting. Undo and redo are unlimited, notes (pencil marks) are supported. No resume after a restart, consistent
  with 0131, 0132 and 0136.

Considered:
- A bundled puzzle list: licence, not endless.
- Difficulty by the number of givens only: misgrades; kept only for the small sizes where it is good enough.
- Accepting puzzles that need guessing: the player could not solve them by reasoning.

## Consequences
- The generator is blocking and runs on a background thread; the UI package waits for the result.
- A new technique makes puzzles solvable that the grader now calls stuck, so the generator may offer
  new puzzles; a puzzle's grade can only stay or go down, since a technique fires only after the easier ones failed.

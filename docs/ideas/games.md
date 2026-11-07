# Idea: Games, what is left for later

**Status:** idea, collected 2026-11-05 while building Minesweeper ([ADR 0131](../adr/0131-games-menu-minesweeper.md)), Word Guess
([ADR 0132](../adr/0132-word-guess.md)), Doubles ([ADR 0136](../adr/0136-doubles.md)), Sudoku, Solitaire and Orbit Guard;
nothing decided or built. Each needs a decision of its own before it is built.

- **Statistics per size**: games played, won, win rate and streaks next to the best times. Needs a stored record per
  size and a place to show it, for example a dialog from the toolbar.
- **No-guess boards**: boards that can be solved without guessing. Needs a solver, and a board generator that retries
  until the solver succeeds; slower on Expert.
- **Resuming after a restart**: the running game (mines, opened cells, flags, elapsed time) stored in `shared.json`
  and restored. Today only the size and the best times are kept.
- **Word Guess: hard mode** (revealed hints must be used in later guesses), **statistics and streaks** (games
  played, win rate, guess distribution), a **daily word** (needs a calendar and a record per day), **more languages**
  (each needs allowed and answer lists with a usable license) and a **larger German answer list** (the news corpus
  yields few base forms, so it has 1,348 words against 1,868 in English).
- **Doubles**: **resuming after a restart** (board and score stored in `shared.json`), **statistics** (games
  played, highest tile, moves), **more than one undo step** (it would make the best score less meaningful) and a
  **running game kept per size** (today a size change starts a new game).
- **Sudoku** ([ADR 0137](../adr/0137-sudoku.md)): **resuming after a restart**, **statistics per level**, a **hint that
  explains the step** ("Hidden single: 4 in box 5"), **digit-first input on the pad** (pick a digit, then click
  cells), a **3-mistake mode** and **variants** (Killer, diagonal).
- **Solitaire** ([ADR 0138](../adr/0138-solitaire.md), [feature](../features/solitaire.md)): **statistics** (played,
  won, streaks), **more variants** (Pyramid, TriPeaks, Golf, Yukon), **FreeCell with fewer cells**, **Vegas scoring**,
  **resuming after a restart**, an option for **safe automatic moves to the foundations**, **card-back themes**, a
  **draw key that works with W A S D** (D is the stock key today, so only the arrows move the cursor) and **proving
  more Spider 4-suit deals** (about a quarter are skipped today).
- **Orbit Guard** ([ADR 0139](../adr/0139-orbit-guard.md), [feature](../features/orbit-guard.md)): **sound effects**,
  **mouse control**, **statistics** (games played, best wave), **resuming after a restart**, the **focus-loss pause for
  the turn-based games** (today only the real-time game pauses when its board loses the focus), **power-ups** (double
  shot, shield repair) and **other formations or diving enemies**.
- **Further games** in the `games` module, one package each, as items of the Games menu (Minesweeper, Word Guess,
  Doubles, Sudoku, Solitaire and Orbit Guard exist; for example Pyramid or Mahjong solitaire).
- **The WebView variant**: a self-contained HTML, CSS and JS page in a WebView, portable to any web server and freer
  to style. Rejected for Minesweeper (it needs an exception to "Kotlin only", its logic is testable only through
  WebView UI tests); it could be reconsidered for a game that is mostly animation.

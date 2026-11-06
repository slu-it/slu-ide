# Idea: Games, what is left for later

**Status:** idea, collected 2026-11-05 while building Minesweeper ([ADR 0131](../adr/0131-games-menu-minesweeper.md)) and Word Guess
([ADR 0132](../adr/0132-word-guess.md));
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
- **Further games** in the `games` module, one package each, as items of the Games menu (Minesweeper and Word Guess
  exist; for example Solitaire, 2048, Sudoku).
- **The WebView variant**: a self-contained HTML, CSS and JS page in a WebView, portable to any web server and freer
  to style. Rejected for Minesweeper (it needs an exception to "Kotlin only", its logic is testable only through
  WebView UI tests); it could be reconsidered for a game that is mostly animation.

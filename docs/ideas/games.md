# Idea: Games, what is left for later

**Status:** idea, collected 2026-11-05 while building Minesweeper ([ADR 0131](../adr/0131-games-menu-minesweeper.md));
nothing decided or built. Each needs a decision of its own before it is built.

- **Statistics per size**: games played, won, win rate and streaks next to the best times. Needs a stored record per
  size and a place to show it, for example a dialog from the toolbar.
- **No-guess boards**: boards that can be solved without guessing. Needs a solver, and a board generator that retries
  until the solver succeeds; slower on Expert.
- **Resuming after a restart**: the running game (mines, opened cells, flags, elapsed time) stored in `shared.json`
  and restored. Today only the size and the best times are kept.
- **Further games** in the `games` module, one package each, as items of the Games menu (for example Solitaire,
  2048, Sudoku).
- **The WebView variant**: a self-contained HTML, CSS and JS page in a WebView, portable to any web server and freer
  to style. Rejected for Minesweeper (it needs an exception to "Kotlin only", its logic is testable only through
  WebView UI tests); it could be reconsidered for a game that is mostly animation.

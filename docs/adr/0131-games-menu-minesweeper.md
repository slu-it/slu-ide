# 0131. Games menu: Minesweeper as a window tool in the new `games` module

- Status: accepted; amends [0110](0110-tools-menu.md) and [0118](0118-services-menu.md) (a Games menu after Services, a third `ToolsMenu`); amended by [0132](0132-word-guess.md) (package layout)
- Date: 2026-11-05

## Context
Builds, tests and downloads leave the user waiting with little to do. A small game in the IDE is a distraction for
those minutes. Like the tools of the Tools and Services menus it belongs to no file, keeps state the user comes back to
and is shown once per window.

## Decision
- A **Games** menu between Services and Help, a third instance of `ToolsMenu` (title "Games"). Its first item is
  **Minesweeper**, a `WindowTool` without a key binding. It behaves as in ADR 0110: one tab per window, a second
  choice selects it, Shortcut+W closes it, it is not restored after a restart.
- A new module **`games`**, with room for more games: one package each, Minesweeper in `dev.sluide.games.minesweeper`.
- The window bean **`Minesweeper`** offers `createTab()` and `showTab` (set by `ide`). It owns the game model, so the
  state outlives the tab: after closing it, a new tab shows the same game.
- Rules: the first opening is safe (mines are placed afterwards, never on that cell or its neighbours), flags without
  `?` marks, chording on opened numbers.
- User-wide best times per preset size and the last size are kept in `shared.json`, field `minesweeper`
  ([ADR 0101](0101-shared-data-file.md)).
- The timer pauses automatically while the tab is hidden, and by hand with a button or P.
- JavaFX controls; the logic is in `MinesweeperModel` and the pure game classes, outside the controls
  ([ADR 0084](0084-testability-logic-out-of-controls.md)).

Considered: a self-contained HTML, CSS and JS page in a WebView, portable to any web server and freer to style.
Rejected: it needs an exception to "Kotlin only", its logic would be testable only through WebView UI tests, and
portability is not a goal.

## Consequences
- A new module `games` and its dependencies (`editor-area`, `project-data`, `background-tasks`); `ide` depends on it;
  see [../architecture.md](../architecture.md).
- No key binding, so nothing changes in the global shortcut table.
- Feature page: [../features/minesweeper.md](../features/minesweeper.md); open ideas:
  [../ideas/games.md](../ideas/games.md).

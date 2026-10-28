# 0068. Window parts in ide

- Status: accepted
- Date: 2026-10-21

## Context
`ide` wires unrelated feature modules through callbacks instead of module dependencies
([ADR 0047](0047-module-dependencies-and-shared-modules.md)), so every interaction between two features lands in
`MainWindow`. It had grown from 151 lines on 2026-10-14 to 682 by 2026-10-21, over the 600 lines of
[ADR 0054](0054-keep-source-files-small.md), with 17 constructor parameters and the state of the trust bar, the git
log and the tool menus next to the layout. `WindowScratches` ([ADR 0066](0066-scratch-files.md)) had already shown a
way out.

## Decision
A window's integration of one feature is a **window part**: an `internal` class in `dev.sluide.ide.<feature>`
([ADR 0055](0055-sub-packages-within-modules.md)). It owns that feature's window state, its wiring with the explorer,
the editor area and the other beans, and its menu items. It gets the beans it needs and `MainWindow`'s hooks as
late-forwarding lambdas (`showError = { s, m -> showError(s, m) }`, so tests that replace the hook still take effect),
and keeps its own test hooks. Stateless logic stays in top-level `internal` functions.

`MainWindow` coordinates: the layout, the wiring between explorer and editor, the folder subscription, closing, the
File menu, and the menu bar, which it assembles from the parts' menus. It keeps parts private unless tests reach them,
which are `internal val`s.

The parts:
- `WindowScratches` (`ide.scratches`): scratch files in the explorer and editor, File → New Scratch File….
- `WindowTrust` (`ide.trust`): the trust bar, asking about findings, the `confirmTrust` hook; with the trust dialog.
- `WindowGitLog` (`ide.git`): the git log's place and height, the Git menu, the View → Git Log item, history and
  write hooks, following the folder's trust; it uses `GitDiffTabs` for the diff tabs of the log.
- `WindowTodos` (`ide.todos`): the TODO panel at the right end of the main split, its width, the View → TODOs item.
- `WindowTools` (`ide.tools`): the Terminal and Docker Sandbox menus and the tabs they open.

## Consequences
- `MainWindow` is under 300 lines; a new cross-feature integration gets a part instead of growing it.
- Tests reach the parts through `mainWindow.trust`, `.tools` and `.scratches` (`.gitLog` is private);
  `showSettings`, `showError`, `confirmCloseWindow`, `openWebPage` and `openFolder` stay on `MainWindow`.
- The order in which `MainWindow.show` wires the parts still matters (e.g. the trust subscription before the folder
  subscription); it is read top to bottom there.
- Listed in [../index.md](../index.md).

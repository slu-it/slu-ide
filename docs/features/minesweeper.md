# Minesweeper

**Modules:** `modules/games` (package `dev.sluide.games.minesweeper`: the pure game in `MinesweeperGame`, the FX-free
`MinesweeperModel`, `GameClock`, `MinesweeperPreferences`, UI in `ui`); menu in `ide` (`ToolsMenu`, `WindowTools`);
decisions in [ADR 0131](../adr/0131-games-menu-minesweeper.md) and [ADR 0110](../adr/0110-tools-menu.md)

A game to pass the time while a build runs. It is a tab of the editor area with a toolbar, the board and a status line.

## Opening
- **Games → Minesweeper** opens the tab. There is one per window: choosing the item again selects the open tab.
- Shortcut+W closes the tab. The game belongs to the window: after closing, a new tab shows the same game, with the
  clock paused meanwhile. After a restart a new game starts; only the size and the best times are kept.

## Sizes
The size control in the toolbar is a menu button labelled with the current size ("Beginner", "Custom 20×12/40").
Choosing any size, also the current one, starts a new game without asking.

| Size | Width × height | Mines |
|---|---|---|
| Beginner | 9 × 9 | 10 |
| Intermediate | 16 × 16 | 40 |
| Expert | 30 × 16 | 99 |
| Custom… | 5–50 × 5–30 | 1 to width · height − 9 |

Custom… opens a dialog (width, height, mines, starting with the last custom values); Cancel keeps the current game
and size.

## Rules
- The first opening is safe and opens an empty area: the mines are placed after it, never on that cell or its eight
  neighbours.
- Opening a cell with no mine around it opens the whole empty region and the numbers around it. Flagged cells are
  never opened by that.
- A right-click flags a closed cell, another removes the flag. There are no `?` marks. The counter shows mines minus
  flags and can go negative.
- **Chording**: opening a number whose neighbouring flags match its number opens its other closed neighbours. A wrong
  flag loses the game then.

## Mouse and keys
| Input | Action |
|---|---|
| Left click | Open the cell; on an opened number, chord |
| Right click | Flag or unflag |
| Middle click | Chord |
| Arrow keys | Move the cursor cell (shown while the board has focus) |
| Space, Enter | Open the cursor cell, or chord it |
| F | Flag or unflag the cursor cell |
| F2 | New game |
| P | Pause or resume |

Keys with Shortcut, Alt or Ctrl pressed are left to the menus.

## Timer and pause
The timer starts with the first opening and shows whole seconds. It pauses while the tab is not shown (another tab
selected, window minimised) and resumes when shown again. The **Pause** button and P pause by hand; the board is then
covered by an overlay titled "Paused" until you resume with the button, P or a click. Its hint reads "Press P or click
to resume" for a manual pause and "Resumes when the tab is shown again" for the automatic one. The status line keeps
its normal text. Pause is available only while a game runs; the board cannot be played while covered.

## Game end
There is no dialog; the board and the status line show it, and the board stays inert until a new game (F2 or the New
Game button).
- **Lost**: all mines are shown, the mine you hit in red, wrong flags crossed out. Status: "Boom — try again (F2)".
- **Won**: the remaining mines are flagged. Status: "Cleared in 42.3 s", with " — new best!" when a preset's record
  was beaten.
- While playing a preset size the status line shows the record, "Best: 42.3 s", when there is one. Custom sizes have no
  records.

## Stored
User-wide in `~/.sluide/shared.json`, field `minesweeper` ([ADR 0101](../adr/0101-shared-data-file.md)): the best time
per preset size and the last size (preset or custom values):
```json
"minesweeper": {
  "size": "custom",
  "custom": { "width": 20, "height": 12, "mines": 40 },
  "best": { "beginner": 12345, "expert": 98765 }
}
```
`size` is `beginner`, `intermediate`, `expert` or `custom`; `best` holds milliseconds per preset. Written a moment after a change, off the FX thread;
invalid values read as defaults and stored sizes are clamped to the limits.

## Scaling
The cells are square and sized to fill the tab, between 16 and 40 px, and the board is centred. Below the minimum the
board scrolls.

## Not included
Statistics, no-guess boards, resuming after a restart and further games: [ideas/games.md](../ideas/games.md).

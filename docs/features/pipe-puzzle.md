# Pipe Puzzle

**Modules:** `modules/games` (window bean `PipePuzzle` in `dev.sluide.games`; package `dev.sluide.games.pipepuzzle`: the
pure engine (`Puzzle`, `PipePuzzleGame`, `Flow`, `NetworkSolver`, `NetworkGenerator`, `PathGenerator`, `Generator`), the
FX-free `PipePuzzleModel`, `KeyAction`, `PipePuzzlePreferences`, and in `ui` the tab, view, toolbar, `BoardCanvas`, the
timing in `BoardAnimation`, `BoardGeometry` and the painters; shared with the other turn-based games: `GameClock` in
`dev.sluide.games.common`, `TabVisibility`, `PauseOverlay` and `focusBoardOnPress` (`BoardFocus.kt`) in
`dev.sluide.games.common.ui`, and `FocusWhenShown` from `file-viewers`); menu in `ide`
(`ToolsMenu`, `WindowTools`); decisions in [ADR 0153](../adr/0153-pipe-puzzle.md)

The pipe puzzle: every cell holds a tile, and turning the tiles has to bring the flow from the fixed source to the fixed
drain. A tab of the editor area with a toolbar, the board and a status line. Puzzles are made on the spot and can always
be solved.

## Opening
- **Games → Pipe Puzzle** opens the tab. There is one per window: choosing the item again selects the open tab.
- Shortcut+W closes the tab. The game belongs to the window: after closing, a new tab shows the same game, with the
  clock paused meanwhile. After a restart a new puzzle starts; only the last mode, size and look and the best times are
  kept.
- The first puzzle is made when the tab first shows; "Generating…" covers the board and it takes no input until the
  puzzle is there.

## Modes, sizes and looks
Three toolbar menus, each showing the current choice:

| Menu | Choices |
|---|---|
| Mode | Network, Path |
| Size | 5×5, 7×7, 9×9, 12×12 (closed edges, no wrap-around) |
| Look | Water, Power, Light |

- **Network:** every tile is part of one tree that includes the source and the drain. It is solved when every tile is
  connected to the source and no opening is left unmatched.
- **Path:** lead the flow from the source to the drain. It is solved when the flow reaches the drain and the filled
  network is sealed, every opening of a filled tile meeting a matching opening, the source or the drain. Tiles the flow
  does not reach do not matter, and any sealed route wins.
- The default is Network 7×7, Water. Each mode and size has its own best time. Choosing the current value does nothing;
  another mode or size starts a new puzzle (after the guard question) and is remembered.
- **Looks** change only the drawing, at any time and without a question; the game stays. Water: grey metal pipes, blue
  water, a tap and a grate. Power: dark cables that light up yellow, a plant and a lamp. Light: dim fibres that glow
  cyan, an emitter and a target. The board is dark in both app themes; the toolbar and status line follow the theme.

## How puzzles are made
- Tiles are an end (one opening), a straight, a corner, a T and, rarely, a cross. The source and the drain have one
  opening, cannot be turned and lie at least a side apart. Turning a cross does nothing.
- **Network:** a random spanning tree of the grid, with two far-apart leaves as source and drain. A tree is kept only
  when a solver that only deduces (no guessing) solves it, so the puzzle has exactly one solution and can be solved by
  reasoning. At most 5 % of the tiles are crosses and 35 % ends.
- **Path:** a random self-avoiding route from the source to the drain (at least 1.5 × side tiles), a few short side
  spurs that end in end-caps, and random decoy tiles everywhere else. More than one solution is possible.
- Every turnable tile then gets a random turn; at least 60 % of the turnable tiles start wrong, and a start that is
  already solved is rolled again.
- Generation runs off the FX thread on its own thread. If no puzzle can be made, the status reads "Could not generate a
  puzzle — F2 to try again".

## Playing
- **Mouse:** a click on a tile turns it clockwise, a right click (also Ctrl+click, on every platform)
  counter-clockwise, with a quarter-turn animation of about 100 ms. A new turn finishes a running one at once. A click
  outside the board does nothing. There is no locking, no undo and no turn counter.
- **Keyboard** (arrows, Space, Enter): see the keys below. The cursor is drawn only while the board has the focus; the
  first arrow press puts it on the centre tile.
- **Live fill:** after every turn the tiles connected to the source through matching openings are drawn filled. An
  unmatched opening of a filled tile shows a leak mark (a drip, a spark or a glow, by look).
- **Win:** the flow runs out from the source layer by layer, about 40 ms per layer; then the drain glows and the board
  takes no more turns (R and F2 still work). The clock stops and the time is recorded as a best time if it is one.
- **Hint** (H or the lightbulb): turns one wrong tile into its solution turn, chosen at random among the wrong ones and
  compared by openings (a straight's two equivalent turns both count as right). In Path mode it follows the generated
  route. Each hint adds **30 seconds** to the time and is shown in the result.
- **Restart** (R or the button) turns every tile back to how the puzzle started and resets the clock to 0:00 and the
  hints to 0; so a known puzzle can be replayed for a better time. It asks first once a tile was turned: title
  "Restart", header "Restart this puzzle?", buttons "Restart" and Cancel (the default).
- **New Game** (F2 or the button) starts a new puzzle of the same mode and size.
- **Guard:** New Game, a mode change and a size change ask first when the puzzle is not solved and a tile was turned:
  header "Abandon this puzzle?", text "Its time of 1:12 is lost.", the verb as button and Cancel, which is the default.
  Closing the dialog counts as Cancel. R asks the restart question and F2 the abandon question.

## Timer and pause
The timer in the toolbar shows m:ss, hint penalties included. It starts with the first turn and pauses by itself while
the tab is hidden or the window is iconified; hiding also ends a running animation at once. P or the Pause button pauses
by hand (the button then shows Resume) and covers the board with the "Paused" overlay; clicking the overlay resumes.
While paused or generating, Restart and Hint are off, and so is Hint once solved; Restart stays available after a win,
to replay the puzzle for a better time. While paused, solved or generating, clicks and keys do not turn tiles; R while
paused does nothing and asks nothing.

## Layout
- The toolbar (a wrapping toolbar): Mode, Size, Look, New Game ("New Game (F2)"), Restart ("Restart Puzzle (R)"), Hint
  ("Hint (H)"), Pause ("Pause (P)"), and at the end the timer and the zoom group. The tab's tooltip is "Turn the tiles
  to connect the flow".
- The board is centred in the tab; a tile is between 28 and 72 pixels at Fit.

## Keys
| Input | Action |
|---|---|
| Click on a tile | Turn it clockwise |
| Right click, Ctrl+click on a tile | Turn it counter-clockwise |
| Arrow keys | Move the cursor |
| Space, Enter | Turn the tile at the cursor clockwise |
| Shift+Space, Shift+Enter | Turn it counter-clockwise |
| H | Hint |
| R | Restart |
| P | Pause or resume |
| F2 | New game |

The keys work only while the board has the focus (a click on it gives it), and not with Shortcut, Ctrl, Meta or Alt.

## Status texts
- "Generating a puzzle…" while one is made; "Could not generate a puzzle — F2 to try again" if that failed.
- While playing: "Best: 1:23" when the mode and size have a best time, else "Connect every tile to the source"
  (Network) or "Lead the flow to the drain without leaks" (Path).
- After solving: "Solved in 1:23", then " with 1 hint (+0:30)" or " with 2 hints (+1:00)" when hints were used,
  then " — new best!" for a record.

## Stored
User-wide in `~/.sluide/shared.json`, field `pipePuzzle` ([ADR 0101](../adr/0101-shared-data-file.md)): the last mode,
size and look and the best time per mode and size. Plain values, no secrets.
```json
"pipePuzzle": { "mode": "network", "size": 7, "look": "water", "best": { "network-7": 83000, "path-12": 140000 } }
```
`mode` is `network` or `path`, `size` the side (5, 7, 9 or 12), `look` is `water`, `power` or `light`, and the keys of
`best` are `<mode>-<side>` with the time in milliseconds, hint penalties included. An invalid or missing value reads as
the default (Network, 7×7, Water) and no best time; other fields stay as they are. Written a moment after a change, off
the FX thread.

## Scaling
The board fills the tab with tiles of 28 to 72 pixels and the drawing scales with them. The Paused overlay and the
Generating text never change the board's size.
Zoom: 25%–200% relative to Fit, see [game-zoom.md](game-zoom.md). The zoom group is the last item of the toolbar, stored
as `pipePuzzle`. The board lies in a scroll pane: when it is larger than the tab (above Fit, or a small tab) it scrolls.

## Not included
Wrap-around edges, locking tiles, undo and redo, a rotation counter against the minimum, a coloured mode with several
pairs, a timed flow countdown, a move limit, a daily puzzle, hexagonal tiles and resuming after a restart:
[ideas/games.md](../ideas/games.md).

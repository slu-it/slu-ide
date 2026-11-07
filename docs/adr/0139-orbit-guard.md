# 0139. Orbit Guard: a sixth game of the Games menu, the first in real time, with a pause that never lets the game run unseen

- Status: accepted; follows [0131](0131-games-menu-minesweeper.md), [0132](0132-word-guess.md),
  [0136](0136-doubles.md), [0137](0137-sudoku.md) and [0138](0138-solitaire.md)
- Date: 2026-11-07

## Context
The Games menu gets a fixed shooter. The request was "Space Invaders", but that is the title of one specific game; as
with Doubles for 2048 and Word Guess for Wordle, the game gets its own name and its own pixel art. The genre rules are
free, the sprites, colours and sounds of the original are not: a first sprite set was rejected in review as too close
to the classic aliens and redrawn. The game has no sound.

All earlier games wait for the player. This one runs on its own: the fleet marches and shoots while no key is pressed.
It therefore needs a pause that never lets the game run unseen, by hand (the P key, a button) and by itself when the
player's attention is elsewhere.

## Decision
- **Orbit Guard**, the sixth item of the Games menu, a `WindowTool` without key binding, one tab per window, the game
  outlives the tab (window bean `OrbitGuard`), not restored after a restart. Package `dev.sluide.games.orbitguard`.
- **Field**: 320 × 240 logical pixels (4:3), simulated in fixed steps of 1/60 s.
- **Fleet**: 5 rows of 11 enemies of three own kinds: the top row 30 points, rows 2 and 3 20 points, rows 4 and 5 10
  points. It marches sideways 2 px per march, drops 8 px and reverses at an edge. The interval between marches shrinks
  with the enemies left, from 50 steps at 55 enemies to 1 at the last one, divided by the difficulty's fleet speed;
  enemies toggle between two frames per march.
- **Enemy shots**: from the lowest enemy of a column, one in three aimed at the ship's column; at most 3 in flight (Hard
  4); the chance per step is 2 % times the difficulty's fire rate times the wave factor.
- **Player**: ← → or A D move while held; Space, ↑ or W fire. Up to 3 own shots in flight, a cooldown of 0.25 s between
  shots; holding fire repeats at the cooldown. A player shot and an enemy shot that meet both vanish.
- **Shields**: 4 erodible shields of 24 × 16 px of an own shape; a shot touching a set pixel clears a crumbly chunk
  there and ends; enemies overlapping a shield clear its pixels. A new wave restores them.
- **Bonus ship**: every 20 to 30 s (random) while at least 8 enemies remain it crosses the top from a random side;
  50, 100, 150 or 300 points.
- **Lives**: Easy 4, Normal 3, Hard 3, and one extra life at 1,500 points (once per game). A hit ship explodes for 1 s,
  enemy shots are cleared, the fleet stands still, then the ship is back at the centre.
- **Waves without end**: a cleared wave shows "Wave n" for 1.5 s; the next fleet starts 8 px lower per wave (at most
  32 px more, from wave 5) and fires 10 % more per wave (at most 50 % more, from wave 6).
- **Game over** when the lives are gone or an enemy reaches the ship's row.
- **Difficulty** Easy, Normal, Hard: fleet speed 0.8, 1, 1.25; fire rate 0.6, 1, 1.5. The last difficulty and the best
  score per difficulty are remembered; the best is recorded whenever the score changes, so an abandoned game counts.
- **States**: READY (a new game stands still), COUNTDOWN (3, 2, 1 at 0.5 s each over the frozen field), PLAYING, PAUSED
  and OVER. The first move or fire key in READY starts the countdown.
- **Pause**: by P, by the Pause button (labelled Resume while paused), and **automatically whenever the board loses the
  keyboard focus** while counting down or playing (pause reason FOCUS), whatever the cause: a menu opened (menu bar,
  the Difficulty menu or a context menu; a popup does not take the board's focus, so `RealtimeLoop` counts any open
  menu of the window as losing it; tooltips do not), another tab or tool clicked, a dialog (also the abandon
  question), another application activated, the window minimised, the tab closed. Toolbar button clicks are no cause:
  Pause pauses by its own action and New Game asks or resets. When the menus close the game stays paused. Held keys
  are released when the focus is lost, because their releases go elsewhere.
- **Resume** only by a move or fire key or P while the board has the focus (the key is eaten: no shot, no step), or by
  the Resume button, which focuses the board; then the 3-2-1 countdown runs again. Regaining the focus never resumes. A
  click on the Paused overlay only focuses the board. The overlay's hint says "Press P, ← → or Space to resume" with
  the focus and "Click the game, then press P, ← → or Space to resume" without it.
- **Shared real-time part**: the state machine and the timing are the FX-free `RealtimeSession` in
  `dev.sluide.games.common` (`onFocusLost` lets the game release held input); `RealtimeLoop` (focus and scene
  listeners, an `AnimationTimer`) and the pure `FieldLayout` (scale, offset and pixel snapping of a fixed-size field)
  are in `common.ui`. The next real-time game, a planned Block Breaker, uses them; keys and held input stay
  per game.
- **Timing**: fixed steps of 1/60 s, at most 5 per frame (the leftover time is dropped, so a stall does not make a
  burst); no timer runs while the game is not counting down or playing, and paused time never becomes steps.
- **Storage**: field `orbitGuard` of `shared.json`, `{"difficulty": "normal", "best": {"easy": 4210}}`, written as
  `DoublesPreferences` does (off the FX thread, coalesced).
- **Code**: the pure engine (`OrbitGuardGame`, `Fleet`, `Shield`, `Sprites`, collisions) knows no JavaFX; the FX-free
  `OrbitGuardModel` holds the held keys and delegates to the session; `KeyAction` maps keys; `ui` holds the view,
  the canvas, the painters and the sprite images cached per scale ([0084](0084-testability-logic-out-of-controls.md)).

Considered:
- **Minesweeper's visibility-only pause** (pause while the tab is not shown): an action game would run on while
  another application has the focus or a menu is open. Rejected.
- **Resuming when the focus returns**: the fleet would attack before the player's hands are back on the keys.
  Rejected; the player resumes by a key and gets the countdown.
- **Unlimited own shots**: no pressure to aim; the cap of 3 and the cooldown keep it a shooter, not a spray.
- **Sound**: not in the first version; no sound files or generated audio to license or maintain.
- **Mouse control**: keys only for now.

## Consequences
- Focus is the one signal for the pause; a board that has lost the focus never runs, which also covers what no event
  names (a notification stealing the focus, a screen lock).
- A click into the game is needed after switching back; the hint says so.
- A second real-time game gets the pause, the countdown and the timing by constructing a `RealtimeSession` and a
  `RealtimeLoop`.
- Feature page [orbit-guard.md](../features/orbit-guard.md), keyboard shortcuts and ideas
  ([games.md](../ideas/games.md)) are updated.

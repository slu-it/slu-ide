# Orbit Guard

**Modules:** `modules/games` (window bean `OrbitGuard` in `dev.sluide.games`; package `dev.sluide.games.orbitguard`: the pure engine in `OrbitGuardGame`, `Fleet`, `Shield`, the FX-free `OrbitGuardModel`, `KeyAction`, `OrbitGuardPreferences`, UI in `ui`; the shared `RealtimeSession` is in `dev.sluide.games.common`, `RealtimeLoop`, `FieldLayout` and `PauseOverlay` in `dev.sluide.games.common.ui`); menu in `ide` (`ToolsMenu`, `WindowTools`);
decisions in [ADR 0139](../adr/0139-orbit-guard.md)

A fixed shooter for the minutes of a build: you move a ship along the bottom and shoot the fleet that marches down.
It is a tab of the editor area with a toolbar, the field and a status line.

## Opening
- **Games → Orbit Guard** opens the tab. There is one per window: choosing the item again selects the open tab.
- Shortcut+W closes the tab; closing pauses a running game. The game belongs to the window: a new tab shows the same
  game, paused. After a restart a new game starts; only the difficulty and the best scores are kept.
- A new game stands still under "Press ← → or Space to start". The first move or fire key starts a 3-2-1 countdown
  over the frozen field, then the game runs.

## Rules
- The field is 320 × 240 logical pixels. The fleet is 5 rows of 11 enemies of three kinds: top row 30 points, rows 2
  and 3 20 points, rows 4 and 5 10 points. It marches sideways and drops a row at each edge; the fewer enemies are left,
  the faster it marches (50 steps between marches at 55 enemies, every step at the last one).
- Enemies shoot from the lowest enemy of a column, some aimed at your column; 3 enemy shots at most in flight (Hard 4).
- You have up to 3 shots in flight and a cooldown of 0.25 s; holding fire repeats at the cooldown. A shot of yours and
  an enemy shot that meet both vanish.
- Four **shields** protect you and erode from both sides: shots chip them, and enemies marching through clear them.
  Every new wave restores them.
- The **bonus ship** crosses the top every 20 to 30 s while 8 or more enemies remain: 50, 100, 150 or 300 points.
- **Lives**: Easy 4, Normal 3, Hard 3, plus one extra life at 1,500 points. A hit ship explodes for a second, enemy
  shots vanish and the fleet stands still; then the ship returns at the centre.
- **Waves** follow each other without end: after the last enemy "Wave n" shows for 1.5 s; each next fleet starts lower
  (up to 32 px more) and shoots more often (up to 50 % more).

## Difficulty
The toolbar's menu button shows the level: Easy (4 lives, fleet ×0.8, fire ×0.6), Normal (3 lives) and Hard (3 lives,
fleet ×1.25, fire ×1.5, 4 enemy shots). Choosing another level starts a new game. When a game with a score is under
way, New Game and a change of level ask "Abandon this game?" (Cancel is the default); the best score is kept.

## Keys
Only while the board has the focus (a click on it gives it), and not with Shortcut, Ctrl, Meta or Alt, which go to the
menus.

| Key | Action |
|---|---|
| ← → or A D | Move while held; also starts or resumes |
| Space, ↑ or W | Fire (held repeats); also starts or resumes |
| P | Pause or resume |
| F2 | New game |

## Pause
- **What pauses**: P and the Pause button (while counting down or playing), and **the board losing the keyboard
  focus** while counting down or playing: a menu opened (menu bar, the Difficulty menu or a context menu), another
  tab or tool clicked, a dialog (also the abandon question; Cancel leaves the game paused), another application
  activated, the window minimised, the tab closed. Tooltips do not pause, and neither do clicks on the toolbar buttons
  (Pause pauses by its own action, New Game asks or resets). When the menus close the game stays paused. Held keys
  are released.
- **What resumes**: with the board focused, a move or fire key or P, or the **Resume** button (the Pause button's label
  while paused; it also focuses the board). Regaining the focus does **not** resume. The key that resumes is eaten (no
  shot, no step). F2 and a click do not resume. In READY and after the game is over, P does nothing.
- **Countdown**: every resume runs 3-2-1 (0.5 s per digit) over the frozen field. Movement keys held during it move
  the ship once it ends; fire pressed during it is ignored. Pausing during the countdown pauses again; resuming restarts
  it at 3.
- **Overlay**: the paused field is covered by "Paused" with the hint "Press P, ← → or Space to resume" while the board
  has the focus and "Click the game, then press P, ← → or Space to resume" while it has not. A click on the overlay
  only gives the board the focus; it does not resume.
- No timer runs while the game is paused, ready, over or its tab is hidden.

## Game end
The game ends when the lives are gone or an enemy reaches the ship's row. The field shows "Game over" and "Score
12,340", with " — new best!" when the best of the level was beaten, and "F2 for a new game". Only F2 or New Game start
over, so a held Space does not restart by accident.

## Look
- A dark starfield-like field in both themes (like the terminal); toolbar and status line follow the theme. Own pixel-art
  sprites: a cyan ship, magenta, amber and lime enemies, a red bonus ship, slate blue shields, white and orange shots.
  No sound.
- The HUD on the field shows SCORE, BEST and WAVE at the top and the reserve ships at the bottom left: the lives minus
  the one on the field.
- The status line shows "← → move · Space fire · P pause · F2 new game" and " · Best: 12,340" when the level has a best.

## Scaling
The field keeps its 4:3 ratio, is centred in the tab and scaled to fill it, at least scale 1 (one view pixel per field pixel; below 320 × 240 the field is cropped); the
edges of each logical pixel are snapped to device pixels, so it stays sharp at fractional scales. The toolbar wraps when
the tab is narrow.

## Stored
User-wide in `~/.sluide/shared.json`, field `orbitGuard` ([ADR 0101](../adr/0101-shared-data-file.md)): the last
difficulty and the best score per difficulty. Plain values, no secrets.
```json
"orbitGuard": { "difficulty": "normal", "best": { "easy": 4210, "normal": 12340 } }
```
`difficulty` is `easy`, `normal` or `hard` (default `normal`); `best` holds a whole number per level, missing for a
level never played. Invalid values read as defaults; other fields stay as they are. The best is recorded whenever the
score changes, so an abandoned game counts. Written a moment after a change, off the FX thread.

## Not included
Sound effects, mouse control, statistics, resuming after a restart, the focus-loss pause for the turn-based games,
power-ups and other formations or diving enemies: [ideas/games.md](../ideas/games.md).

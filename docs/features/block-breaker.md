# Block Breaker

**Modules:** `modules/games` (window bean `BlockBreaker` in `dev.sluide.games`; package `dev.sluide.games.blockbreaker`: the pure engine in `BlockBreakerGame`, `Ball`, `Bricks`, `Levels`, `PowerUps`, the FX-free `BlockBreakerModel`, `KeyAction`, `BlockBreakerPreferences`, UI in `ui`; the shared `RealtimeSession` and `DifficultyPreferences` are in `dev.sluide.games.common`, `RealtimeLoop`, `FieldLayout` and `PauseOverlay` in `dev.sluide.games.common.ui`); menu in `ide` (`ToolsMenu`, `WindowTools`);
decisions in [ADR 0140](../adr/0140-block-breaker.md)

A paddle-and-bricks game for the minutes of a build: you move a paddle along the bottom, bounce the ball and clear the
wall. It is a tab of the editor area with a toolbar, the field and a status line.

## Opening
- **Games → Block Breaker** opens the tab. There is one per window: choosing the item again selects the open tab.
- Shortcut+W closes the tab; closing pauses a running game. The game belongs to the window: a new tab shows the same
  game, paused. After a restart a new game starts; only the difficulty and the best scores are kept.
- A new game stands still, the ball resting on the paddle, under "Press ← →, Space or click to start". The first move
  or launch key, or a click on the field, starts a 3-2-1 countdown over the frozen field, then the game runs. That
  input does not act: the ball still rests on the paddle afterwards.

## Playing
- **Move** the paddle with ← → or A D while held (240 px/s) or with the **mouse** over the field: the paddle follows
  the pointer, at most 10 px per step, so it never jumps. Keys win over the pointer until the mouse moves again. The
  pointer is hidden over the field while the game runs.
- **Launch** the ball with Space, ↑, W or a click. It starts straight up, tilted 15° to the side the paddle moved last (to a random side when it has not moved).
  The ball rests on the paddle and moves with it at the start, after a lost ball and after each level. A held Space
  never serves the next ball: release it and press again.
- The paddle bounces the ball at an angle that depends on where it hits (up to 60° from vertical); only a ball moving
  down is caught.
- The ball gets **faster** the longer you play: +5 % per 10 paddle hits, at most ×1.6; losing a life and a new level reset
  it (an extra Multi ball dropping out does not).
- A lost ball (below the paddle) costs a **life**; with no life left the game is over.

## Bricks
- **Normal** bricks break with one hit and give 10 to 50 points by colour. **Tough** bricks need 2 hits (60 points),
  **hard** bricks 3 (90 points) and show cracks as they weaken. **Steel** bricks never break and do not count for
  clearing the level.

## Levels
Ten own layouts, each symmetric, with at least 40 breakable bricks and no breakable brick sealed in by steel. A cleared
level shows the next level's number, "Level n", for 1.5 s, then the next wall comes. After level 10 the set starts over and the ball is 10 % faster
for each loop, at most 30 % faster.

## Power-ups
A breakable brick that breaks sometimes drops a capsule with a letter (the chance depends on the difficulty); at most 2
fall at a time. Catching one with the paddle gives 50 points.
- **Wide**: the paddle is 1.5 times as wide for 20 s; catching another restarts the time.
- **Multi**: two extra balls split from the first, at most 5 balls; a lost ball costs a life only when the last one is
  gone.
- **Slow**: the ball is 30 % slower for 15 s.
- **Life**: one extra life, at most 9.

The times only run while the game is playing. Losing the last ball or clearing a level ends all effects and removes the falling capsules.

## Difficulty
The toolbar's menu button shows the difficulty:

| Difficulty | Ball speed | Paddle | Lives | Capsule chance |
|---|---|---|---|---|
| Easy | 120 px/s | 48 px | 5 | 15 % |
| Normal | 150 px/s | 40 px | 3 | 12 % |
| Hard | 180 px/s | 32 px | 3 | 10 % |

Choosing another difficulty starts a new game. When a game with a score is under way, New Game and a change of difficulty ask
"Abandon this game?" (Cancel is the default); the best score is kept.

## Keys and mouse
Keys only while the board has the focus (a click on it gives it), and not with Shortcut, Ctrl, Meta or Alt, which go to
the menus.

| Key / mouse | Action |
|---|---|
| ← → or A D | Move the paddle while held; also starts or resumes |
| Mouse move over the field | Move the paddle towards the pointer |
| Space, ↑ or W | Launch; also starts or resumes |
| Click on the field | Launch; also starts, or resumes (see Pause) |
| P | Pause or resume |
| F2 | New game |

## Pause
- **What pauses**: P and the Pause button (while counting down or playing), and **the board losing the keyboard
  focus** while counting down or playing: a menu opened (menu bar, the Difficulty menu or a context menu), another tab
  or tool clicked, a dialog (also the abandon question; Cancel leaves the game paused), another application activated,
  the window minimised, the tab closed. Tooltips do not pause, and neither do clicks on the toolbar buttons. When the
  menus close the game stays paused. Held keys are released.
- **What resumes**: with the board focused, P, a move or launch key (the key does not act: no launch, no step), a click
  on the field or the overlay, or the **Resume** button (the Pause button's label while paused; it also focuses the
  board). Regaining the focus does **not** resume. F2 does not resume. In READY and after the game is over, P does
  nothing.
- **The mouse**: a click on a paused board that **has the focus** resumes. A click on a board that has **no focus** (you
  came back from another tab or a menu) only gives it the focus, and the second click resumes. A click that **activates
  an inactive window** (you came back from another application) resumes at once if the game had the focus when you left
  the window; otherwise it only focuses. This differs from Orbit Guard, where
  a click only focuses.
- **Countdown**: every resume runs 3-2-1 (0.5 s per digit) over the frozen field. Moves held during it and the pointer
  position apply once it ends; a launch during it is ignored. Pausing during the countdown pauses again; resuming
  restarts it at 3.
- **Overlay**: the paused field is covered by "Paused" and one of three hints: "Press P, ← →, Space or click to resume"
  while the board has the focus, "Click the game to resume" while the window is inactive and the game had the focus
  when it was left, and "Click the game to focus it" otherwise.
- No timer runs while the game is paused, ready, over or its tab is hidden.

## Game end
The game ends when the lives are gone. The field shows "Game over" and "Score 12,340", with " — new best!" when the
best of the difficulty was beaten, and "F2 for a new game". Only F2 or New Game start over, so a held Space does not restart
by accident.

## Look
- A dark field in both themes (like the terminal); toolbar and status line follow the theme. Own palette and pixel art:
  bevelled bricks in colour tiers, cracks on tough and hard bricks, steel bricks, capsules with a letter. No sound.
- The HUD on the field shows the score, the level and the best at the top; lives are small dim paddles at the bottom
  left, one per spare ball, at most 8.
- The status line shows "← → or mouse move · Space or click launch · P pause · F2 new game" and " · Best: 12,340" when
  the difficulty has a best. The tab's tooltip is "Clear the wall with paddle and ball".

## Scaling
The field keeps its 4:3 ratio (320 × 240 logical pixels), is centred in the tab and scaled to fill it, at least scale 1
at Fit (the zoom goes below it);
the edges of each logical pixel are snapped to device pixels, so it stays sharp at fractional scales. The mouse position
is mapped back to logical pixels. The toolbar wraps when the tab is narrow.

Zoom: 25%–Fit, see [game-zoom.md](game-zoom.md); the field then gets smaller than the tab, centred, with the board
background around it.

## Stored
User-wide in `~/.sluide/shared.json`, field `blockBreaker` ([ADR 0101](../adr/0101-shared-data-file.md)): the last
difficulty and the best score per difficulty. Plain values, no secrets.
```json
"blockBreaker": { "difficulty": "normal", "best": { "easy": 4210, "normal": 12340 } }
```
`difficulty` is `easy`, `normal` or `hard` (default `normal`); `best` holds a whole number per difficulty, missing for a
difficulty never played. Invalid values read as defaults; other fields stay as they are. The best is recorded whenever the
score changes, so an abandoned game counts. Written a moment after a change, off the FX thread, by the same
`DifficultyPreferences` as Orbit Guard's.

## Not included
Sound effects, resuming after a restart (a new game starts), statistics, more levels or a level editor, and the laser,
sticky and shrink power-ups: [ideas/games.md](../ideas/games.md).

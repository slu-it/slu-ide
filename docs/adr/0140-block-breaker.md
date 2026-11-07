# 0140. Block Breaker: a seventh game of the Games menu, paddle and ball with keys and mouse, on the shared real-time pause

- Status: accepted; follows [0131](0131-games-menu-minesweeper.md), [0132](0132-word-guess.md),
  [0136](0136-doubles.md) and [0139](0139-orbit-guard.md)
- Date: 2026-11-07

## Context
The Games menu gets a paddle-and-bricks game, the second in real time after Orbit Guard. As with the earlier games, the
request named one specific title; the game is called by its genre, **Block Breaker**, and has its own levels, bricks
and pixel art. The rules of the genre are free, the layouts, colours and sounds of the originals are not. The game has
no sound.

The pause is the same problem as in Orbit Guard: a game that runs on its own must never run unseen. It therefore builds
on the shared `RealtimeSession` and `RealtimeLoop` ([0139](0139-orbit-guard.md)) and copies none of that code.

## Decision
- **Block Breaker**, the seventh item of the Games menu, after Orbit Guard: a `WindowTool` without key binding, one tab
  per window, the game outlives the tab (window bean `BlockBreaker`), not restored after a restart. Package
  `dev.sluide.games.blockbreaker`. The name is a genre name, not the title of one game.
- **Field**: 320 × 240 logical pixels (4:3), fixed steps of 1/60 s, scaled by the shared `FieldLayout`. Walls: top
  wall at y 12 to 16 (HUD above, inner edge at y 16), sides 4 px. Bricks are 24 × 8 px in 13 columns, from y 32, at most 10 rows. The paddle's top is at
  y 220, 6 px high; the ball is 4 × 4 px. A ball is lost when its top passes y 240.
- **Keyboard and mouse**: ← → or A D move the paddle at 240 px/s while held; the mouse over the field moves its centre
  towards the pointer, at most 10 px per step, so it never jumps (for example after a resume). **Keys win over the
  pointer until the mouse moves again**: a key press drops the pointer target, so a resting mouse does not fight the
  keys. The pointer is **hidden over the field while playing**: it would only cover the ball's path.
- **The ball rests on the paddle until a launch**: at the start, after a lost ball and after each level it moves with
  the paddle until Space, ↑, W or a click launches it, straight up, tilted ±15° by the paddle's last direction (to a random side when the paddle has not moved).
  **Launch is a one-shot request with a key latch**: it is passed to the game once, and a launch key counts only on
  its first press until its release, so a held Space (auto-repeat) never serves the next ball by itself. A request that
  is not played yet is dropped when the session leaves PLAYING.
- **Bounce**: off the paddle only while the ball moves down, at an angle set by the hit offset, −60° to +60° from
  vertical; off walls and bricks on the axis of the smaller overlap. A ball that overlaps the paddle slightly below its
  top is still caught, so a ball squeezed against a wall never sticks to the paddle or passes through it.
- **No tunnelling**: the ball moves in **sub-steps of at most 2 px**, one brick hit per sub-step, so even at the speed
  cap it cannot pass a brick of 8 px.
- **Anti-loop nudge**: after 20 bounces in a row without touching the paddle or a breakable brick (a ball bouncing
  between walls and steel), its angle turns by 5°.
- **Speed formula**: base speed of the difficulty × (1 + 0.1 × completed loops of the level set, at most 3) × speed-up
  (+5 % per 10 paddle hits, at most ×1.6) × 0.7 while Slow runs. The speed-up resets when a life is lost (not when an extra Multi ball drops out) and on a new
  level.
- **Bricks**: normal (1 hit, 10 to 50 points by colour tier), tough (2 hits, 60 points) and hard (3 hits, 90 points),
  with cracks as they weaken; steel is indestructible and does not count for clearing a level.
- **10 own layouts that loop faster**: ten symmetric layouts as string rows in `Levels.kt`, each with at least 40
  breakable bricks and no steel that seals off a breakable one (a test checks that every one is reachable from the open
  area below). A cleared level shows the next level's number, "Level n", for 1.5 s; after level 10 the set starts over with the base speed +10 %
  per loop, at most +30 %.
- **The four power-ups**: a breakable brick that breaks drops a capsule (12 × 6 px, falls at 60 px/s) by the
  difficulty's chance, at most 2 falling at a time; catching one gives 50 points. Kinds: **Wide** 35 % (paddle ×1.5 for
  20 s, catching it again restarts the timer), **Multi** 30 % (two extra balls at ±30° from the first, at most 5
  balls), **Slow** 25 % (speed ×0.7 for 15 s), **Life** 10 % (+1 life, at most 9). Losing the last ball costs a life and
  ends all effects and falling capsules, and so does clearing a level. **Effect timers only run while playing**:
  pause and the countdown do not use them up.
- **Difficulties**: Easy, Normal, Hard: ball speed 120, 150, 180 px/s; paddle 48, 40, 32 px; lives 5, 3, 3; capsule
  chance 15, 12, 10 %. The last difficulty and the best score per difficulty are remembered; the best is recorded
  whenever the score changes, so an abandoned game counts.
- **Lives are drawn as the spare balls**: small dim paddles at the bottom left of the field, below the paddle (y 232),
  one per spare ball (lives minus the ball in play), at most 8. The HUD row at the top holds only score, level and
  best.
- **States and pause** as in Orbit Guard: READY (a new game stands still, ball on the paddle), COUNTDOWN (3-2-1, 0.5 s
  per digit), PLAYING, PAUSED, OVER. Pause by P, by the Pause button (Resume while paused) and automatically whenever
  the board loses the keyboard focus, menus included (`RealtimeLoop`). Focus coming back never resumes. The key that
  starts or resumes does not act (no launch, no step); moves held during the countdown and the pointer apply when it
  ends, launches during it are ignored.
- **The mouse differs from Orbit Guard**: the view calls the model's `clicked()` **before** it takes the focus. Inside
  the window the first click on a paused board that has no focus therefore only focuses it, and the **second click
  resumes**; a click on a focused paused board resumes at once. The click that **activates an inactive window**
  resumes at once when the game had the focus as the window was left (it was the window's focus owner); this was
  decided by the user. Otherwise it only focuses. The overlay's hint has three texts: "Press P, ← →, Space or click to
  resume" with the focus, "Click the game to resume" while the window is inactive and the game had the focus when it
  was left, and "Click the game to focus it" in every other case without the focus.
- **Game over** when the lives are gone; only F2 or New Game start over. New Game and a difficulty change ask
  "Abandon this game?" while a game with a score is under way.
- **Storage**: field `blockBreaker` of `shared.json`, `{"difficulty": "normal", "best": {"easy": 4210}}`. Both games'
  preferences are thin components over the shared `common/DifficultyPreferences` (store, field, difficulty ids and
  default, coalesced writes off the FX thread), tested once; a copy of `OrbitGuardPreferences` would have been 400
  lines of duplicate code and tests.
- **Code**: the pure engine (`BlockBreakerGame`, `Ball`, `Bricks`, `Levels`, `PowerUps`) knows no JavaFX. Its random
  source is used only through `nextDouble` and `nextBoolean`, so a seeded game is reproducible. The FX-free
  `BlockBreakerModel` holds the keys, the pointer and the launch request and delegates to the session; `KeyAction` maps
  keys; `ui` holds the view, the canvas, the painters and the brick images cached per scale
  ([0084](0084-testability-logic-out-of-controls.md)).

Considered:
- **A click on the overlay only focuses the board** (Orbit Guard's choice): consistent, but a mouse player would have
  to click once and then press a key. Chosen here: a click on an already focused board resumes. The first click on an
  unfocused board still only focuses it, so a stray click that merely switched to the tab does not start the ball.
- **Launching by itself after the countdown**: a ball that leaves the paddle while the hands are still moving is a
  lost ball. The player launches.
- **A portrait field**: the genre is often portrait, but the games share a 4:3 field and `FieldLayout`; a tall field
  would need a second layout mode of the tab.
- **Generated levels**: random walls are often unfair or dull; ten drawn, symmetric layouts can be checked by a test.
- **No power-ups**: the plain game is short of surprises; four kinds with limits (balls, lives, falling capsules) are
  enough variety.
- **Sound**: not in the first version, as in Orbit Guard.

## Consequences
- A second real-time game costs a model, a view and an engine; the pause, the countdown and the timing are the shared
  ones, and a change of the pause rules is made once.
- The mouse rule differs from Orbit Guard's; the [feature page](../features/block-breaker.md) says so.
- A held key never serves a ball, and the paddle never jumps to the pointer.
- Feature page [block-breaker.md](../features/block-breaker.md), keyboard shortcuts and ideas
  ([games.md](../ideas/games.md)) are updated.

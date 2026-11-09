# 0152. Mini Golf: an eighth game of the Games menu, a top-down course of 18 holes putted with a slingshot drag

- Status: accepted; follows [0139](0139-orbit-guard.md) and [0140](0140-block-breaker.md)
- Date: 2026-11-09

## Context
The Games menu gets a mini golf game: a whole course played hole by hole, seen from above so that the holes are simple
to draw and to design. The request named no specific title; the game is called by its genre, **Mini Golf**, and has
its own course, palette and drawings. It is the third real-time game: moving obstacles keep moving while the player
aims, so a game that runs unseen is the same problem as in Orbit Guard and Block Breaker. It builds on the shared
`RealtimeSession`, `RealtimeLoop`, `FieldLayout` and `PauseOverlay` in `dev.sluide.games.common` and copies none of
them.

Ball physics is the heart of the game, and a course of hand-drawn holes is only fair if every hole can be holed in par;
both must be checkable by unit tests, without a scene.

## Decision
**Game**
- **Mini Golf**, the eighth item of the Games menu, after Block Breaker: a `WindowTool` without key binding, one tab per
  window; the round outlives the tab (window bean `MiniGolf`) and is lost on a restart. Package
  `dev.sluide.games.minigolf`, `shared.json` field `miniGolf`. Tab tooltip "Putt your way through 18 holes".
- **Solo, one hand-drawn course of 18 holes**, played in order, each with a par of 2 to 4 (about 52 in total). One new
  element per hole early on (straight lane, dogleg, bank shot, sand, slope, bumpers, water, rotating bar, tunnel), then
  combinations.
- **Stroke cap**: when the stroke count (penalties included) reaches 7 and the ball is not in, it is picked up, the hole
  scores **8**, and the game goes on to the next hole. A splash that brings the count to 7 counts too.

**Field and look**
- A 4:3 logical field of 320 × 240 units (doubles), scaled by `FieldLayout`, fixed steps of 1/60 s. The HUD is a strip
  at the top (y 0 to 16); holes lie in y 16 to 240 and keep 6 units of margin to the edges, so their rails show.
- **Smooth antialiased vector drawing**, not pixel art: green felt, rails, sand, water, slope shading with arrows,
  round bumpers, tunnel mouths, the cup with a flag, a white ball with a shadow. The field is dark in both themes; the
  toolbar and status line follow the theme. No sound.
- HUD "Hole 7 · Par 3 · Stroke 2 · Total +1". Status line "Drag to aim · release to putt · Esc cancel · P pause ·
  F2 new round", plus " · Best: 49 (−3)" when there is a best.

**Shot (mouse only)**
- **Slingshot**: while the ball rests, press anywhere on the field and drag; the shot goes opposite to the drag vector
  (pointer minus press point, in logical units), and its power grows linearly with the drag length up to 80 units.
  Releasing shoots; a release under 4 units, Esc or a right click cancels. The pointer stays visible.
- **Aim help**: a direction and power line from the ball, whose length and colour (green, yellow, red) show the power.
  No path prediction. The **ghost line** draws the previous shot of the hole faintly while the ball rests (sampled
  every 3 steps, cleared on a new hole).
- Keys: P pause and resume, F2 new round, Esc cancel the aim, Space, Enter or a click continue past the scorecard. No
  aiming keys.

**Physics** (the values of the engine's `Rules`)
- Ball radius 2.5, cup radius 4.5, tunnel mouth radius 5. A shot of power 0..1 starts at 0 to **300 u/s**.
- Constant deceleration: **60 u/s²** on felt, **300 u/s²** in sand, **20 u/s²** on a slope. The ball rests below
  **2 u/s**, never on a slope. A slope pulls with its own constant acceleration, about 50 u/s²; the builder refuses a
  pull that does not beat the slope friction. Slopes must not overlap sand, and a ball left on a slope must roll off
  it (the course test drops one on a 6-unit grid of every slope).
- **Cup**: a ball whose centre enters the cup at ≤ **90 u/s** drops; a faster one lips out, once per pass over the
  cup: turned toward the centre by at most 0.3 rad and 30 % slower. A ball that comes to rest over the cup drops.
- **Walls** are segments (straight or diagonal), treated as capsules of the ball's radius: the ball is pushed out and
  the normal component reflected with restitution **0.75**. **Bumpers** are circles with restitution **1.15**, the
  speed capped at 300. Holes are drawn as greens (felt polygons whose outlines are rails; they neither overlap nor
  touch), blocks (solid polygons) and loose walls (`Hole.looseWalls`, for the painters); the physics only sees the
  segments (`Hole.walls`).
- **No tunnelling**: the ball moves in sub-steps of at most **1 unit**, and collisions are resolved before the first
  sub-step too.
- **Water**: the ball's centre in a water polygon is a splash. After a shot it costs +1 stroke and the ball goes back
  to where the shot was played from, at rest. **A push never costs a stroke**: after a push by a moving obstacle (a
  roll or a weaker move) the ball goes back, without a penalty, to where it rested before the push. A ball put back is
  moved out of a sliding block that now covers the spot, and a tunnel mouth it lies in stays closed to it.
- **Moving obstacles**: a rotating bar (pivot, length, period, phase, thickness 4) and a sliding block (two end points,
  size, period, cosine easing). Their position is a function of the hole's step count, which starts at 0 on each
  hole, so a hole always starts the same way. They move while aiming, rolling and during the drop, and stand still
  while paused, on the scorecard and during the countdown. A hit reflects the velocity relative to the surface (0.75)
  and adds the surface velocity. A bar is a capsule; a **sliding block is solid**: a ball whose centre is inside it
  is pushed out on the side the block moves toward (the nearest side when it stands still), or on another side when
  a wall is in the way. A resting ball that gets hit hard enough to reach the rest speed rolls without a stroke (a
  weaker push only moves it); such a roll is not a shot and leaves no ghost line. The course test checks that no point
  of a mover moves by the ball radius or more per step, that no mover comes near the tee or covers it, and that every
  mover keeps 2 × ball radius + half its thickness to every wall, bumper and water over its whole period, so that it
  can neither pin a ball nor push it into the water at every step.
- **Tunnels**: pairs of mouths. A ball whose centre enters one leaves the other in that mouth's fixed exit direction
  at the same speed, and cannot re-enter the mouth it came out of until it has left it.
- **Roll limit**: a roll that has not ended after 20 s (1200 steps) stops where it is.
- **Deterministic**: no randomness and no wall clock; the trigonometry uses `StrictMath`, so the same shots give
  bit-identical rounds on every platform.

**Flow, pause, end**
- States as in Block Breaker (`RealtimeSession`): READY (hole 1 shown, "Click to start"), COUNTDOWN 3-2-1, PLAYING,
  PAUSED, OVER. Pause by P, the Pause button (Resume while paused) and losing the focus (`RealtimeLoop`). Resume as in
  Block Breaker: a click on the focused paused board, P or Resume; a click on an unfocused board only focuses it; the
  window-activation click resumes when the game had the focus. A pause cancels an aim in progress. Overlay hints
  "Press P or click to resume", "Click the game to resume", "Click the game to focus it".
- **Hole done**: the ball drops, and 1 s later the **scorecard** appears: 18 columns with par, strokes, ± par and the
  best per hole, and the totals. Click, Space or Enter goes on to the next hole, without a countdown. Nothing moves
  while the card is shown. A **hole in one** shows a 1.5 s particle burst at the cup and "Hole in one!" before the
  card.
- **Round end** after hole 18: the final card with the total, ± par, " — new best!" when beaten, and "F2 for a new
  round". Only F2 or New Game start over; New Game asks "Abandon this round?" once a stroke has been played (Cancel is
  the default).

**Storage**: field `miniGolf` of `shared.json` ([0101](0101-shared-data-file.md)),
`{"bestTotal": 49, "bestHoles": [2, 2, 3, null, ...]}`. The best per hole is recorded when a hole ends, so abandoned
rounds count for it; the best total only for a completed round. Invalid values read as missing. Writes are coalesced
and made off the FX thread, like `DifficultyPreferences`.

**Code** ([0084](0084-testability-logic-out-of-controls.md)): the pure engine knows no JavaFX: `geometry` (`Vec`,
`Segment`, `Circle`, `Polygon`), `Hole` with a small builder DSL (`hole(n, par) { green(...); tee(...); cup(...) }`),
the movers, `BallPhysics` and `MiniGolfGame` (phases AIMING, ROLLING, HOLED, CARD, FINISHED; `shoot(angle, power)`,
`step()`, `continueToNext()`). `COURSE` joins three lists in three files (holes 1-3, 4-9, 10-18). An FX-free model holds
the drag, the click rules and the session; `ui` holds the view, the canvas and the painters.

**Course test**: every hole passes structural checks (numbering, par, margin, greens apart, tee and cup on clear
felt, tunnel mouths on clear felt and apart, mover speed, reach and room to the walls, slopes that drain), each of
which a test also shows failing on a deliberately bad hole, and has a **reference solution**, a list of shots (wait steps,
angle, power) that a test replays and that must hole the ball in at most par. A test-only `ShotSearch` (a beam search
over angles, powers and wait steps, ranked by the walking distance to the cup) finds them; the build does not run it.

Considered:
- **Pixel art**, as in Block Breaker: round shapes, diagonal walls and slopes look poor in coarse pixels, and vector
  drawing scales cleanly with the tab.
- **Keyboard aiming or a power meter**: a drag shows direction and power at once and needs no timing skill; keyboard
  aiming would add a second input path for little gain.
- **Path prediction**: it would take the skill out of bank shots; the ghost line of the previous shot is the help.
- **Hot-seat multiplayer**: a second scorecard and turn handling for a game played alone at a desk; left for later.
- **Resuming a round after a restart**: the round is short, and no other game keeps its state across restarts.
- **Scrolling holes larger than the field** instead of **one screen per hole**: scrolling needs a camera and hides the
  cup; every hole fits the 320 × 224 area, which keeps them small and readable.

## Consequences
- A third real-time game reuses the shared session, loop, layout and overlay; a change of the pause rules is made once.
- Physics values live in one `Rules` object; changing one may break reference solutions, which `ShotSearch` finds
  again. The course test proves that each hole's reference solution holes the ball in par, that a ball left on a slope
  (on a 6-unit grid) rolls off it, and that movers move less than the ball radius per step, never reach or cover the
  tee and keep room for a ball to every wall, bumper and water; it does not prove that a hole cannot trap a ball
  elsewhere.
- Holes are data written with the builder, so the course can be extended in parallel without touching the engine.
- The feature page, keyboard shortcuts, the Games list in the UI conventions and the ideas page are added with the
  view.

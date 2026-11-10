# Mini Golf

**Modules:** `modules/games` (window bean `MiniGolf` in `dev.sluide.games`; package `dev.sluide.games.minigolf`: the pure engine in `MiniGolfGame`, `BallPhysics`, `Hole`, `Obstacles`, `Course*`, the FX-free `MiniGolfModel`, `KeyAction`, `MiniGolfPreferences`, painters and the tab, view and canvas in `ui`; `RealtimeSession` in `dev.sluide.games.common`, `RealtimeLoop`, `FieldLayout` and `PauseOverlay` in `dev.sluide.games.common.ui`); menu in `ide` (`ToolsMenu`, `WindowTools`);
decisions in [ADR 0152](../adr/0152-mini-golf.md)

A solo mini golf course of 18 holes for the minutes of a build, seen from above. It is a tab of the editor area with a
toolbar (New Game, Pause), the field and a status line.

## Opening
- **Games → Mini Golf** opens the tab. There is one per window: choosing the item again selects the open tab.
- Shortcut+W closes the tab; closing pauses a running round. The round belongs to the window: a new tab shows the same
  round, paused. After a restart a new round starts; only the best scores are kept.
- A new round stands still on hole 1 under "Click or press Space to start". A click on the field or Space or Enter
  starts a 3-2-1 countdown over the frozen field, then the round runs. That click does not aim.

## Playing
- **Aim** while the ball rests: press anywhere on the field and drag. The shot goes opposite to the drag, like a
  slingshot, and its **power** grows with the drag length up to 80 field units. A line from the ball shows direction
  and power: longer, and green to yellow to red, with more power. There is no path prediction.
- **Release** to putt. A release after less than 4 units, **Esc** or a **right click** cancels the aim. The pointer
  stays visible, and a drag that leaves the field keeps aiming.
- The **ghost line** draws the previous shot of the hole faintly while the ball rests; a new hole starts without one.
- Moving obstacles keep moving while you aim.

## Holes and elements
Eighteen hand-drawn holes in a fixed order, one new element at a time early on and combinations later: felt greens
with rails, diagonal walls, sand (slows the ball strongly), slopes (a constant pull, shown by arrows), water, round
bumpers that bounce the ball faster, tunnels (in at one mouth, out at the other), rotating bars and sliding blocks.
Every hole can be holed in par; the course tests prove it. The physics is deterministic: the same shots give the same
round. Details and values: [ADR 0152](../adr/0152-mini-golf.md).

## Strokes and par
- The HUD strip on the field shows "Hole 7 · Par 3 · Stroke 2 · Total +1". The stroke is the one the next shot will be
  while the ball rests; the total (strokes relative to par of the finished holes) is left out until a hole is done.
- **Water** costs one stroke and puts the ball back where the shot was played from; a push by a moving obstacle never
  costs a stroke.
- **Stroke cap**: when the stroke count, penalties included, reaches 7 and the ball is not in, it is picked up and the
  hole scores **8**.
- A **hole in one** shows a 1.5 s burst of sparks at the cup and "Hole in one!".

## Scorecard
One and a half seconds after a hole in one, else one second after the ball drops, a card covers the field: all 18
holes with par, your strokes, the difference to par and the best you had per hole, and the totals. **Click, Space or
Enter** goes on to the next hole, without a countdown. Nothing moves while the card is shown.

## Keys and mouse
Keys only while the board has the focus (a click on it gives it), and not with Shortcut, Ctrl, Meta or Alt, which go to
the menus.

| Key / mouse | Action |
|---|---|
| Press, drag, release | Aim and putt |
| Right click, Esc | Cancel the aim |
| Space, Enter | Start in READY; continue past the scorecard |
| Click on the field | Start in READY; continue past the scorecard; resume a paused board that has the focus |
| P | Pause or resume |
| F2 | New round |

## Pause
Like [Block Breaker](block-breaker.md#pause): P and the Pause button pause, and so does the board losing the keyboard
focus (a menu, another tab or tool, a dialog, another application, the tab closed). P, a click on the focused paused
board or the Resume button resume, with a 3-2-1 countdown; a click on a board without focus only focuses it, and a
click that activates an inactive window resumes at once if the round had the focus. A pause cancels an aim in
progress. The overlay says "Press P or click to resume", "Click the game to resume" or "Click the game to focus it".
No timer runs while the round is paused, ready, over or its tab is hidden.

## Round end
After hole 18 the final card shows the total, the difference to par and " — new best!" when the best total was beaten,
and "F2 for a new round". Only F2 or New Game start over, so a held Space does not restart by accident. New Game, and
F2, ask "Abandon this round?" once a stroke has been played (Cancel is the default); the best scores of finished holes
are kept.

## Look
A dark field in both themes; toolbar and status line follow the theme. Smooth vector drawing: felt, rails, sand, water,
slope arrows, bumpers, tunnel mouths, the cup with a flag, a white ball with a shadow. No sound. The status line shows
"Drag to aim · release to putt · Esc cancel · P pause · F2 new round" and " · Best: 49 (−3)" once there is a best
total. The tab's tooltip is "Putt your way through 18 holes".

## Scaling
The field keeps its 4:3 ratio (320 × 240 units), is centred in the tab and scaled to fill it, at least scale 1 at Fit
(the zoom goes below it). The pointer is mapped back to field units. The toolbar wraps when the tab is narrow.

Zoom: 25%–Fit, see [game-zoom.md](game-zoom.md); the field then gets smaller than the tab, centred, with the board
background around it.

## Stored
User-wide in `~/.sluide/shared.json`, field `miniGolf` ([ADR 0101](../adr/0101-shared-data-file.md)): the best total
and the best score per hole (lower is better). Plain values, no secrets.
```json
"miniGolf": { "bestTotal": 49, "bestHoles": [2, 2, 3, null] }
```
The best of a hole is recorded when the hole ends, so an abandoned round counts for it; the best total only for a
completed round. Invalid values read as missing; other fields stay as they are. Written a moment after a change, off
the FX thread.

## Not included
Hot-seat multiplayer, practising a single hole, statistics, more courses or a course editor, keyboard aiming, a power
meter, path prediction, resuming after a restart and sound: [ideas/games.md](../ideas/games.md).

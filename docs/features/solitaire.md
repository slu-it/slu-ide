# Solitaire

**Modules:** `modules/games` (window bean `Solitaire` in `dev.sluide.games`; package `dev.sluide.games.solitaire`: the
cards and tables (`Cards`, `Table`, `Move`), the rules per game (`KlondikeRules`, `FreeCellRules`, `SpiderRules`),
deals (`Deals`, `SplitMix64`, `Dealer`), the pure game in `SolitaireGame` with `Scoring`, the FX-free `SolitaireModel`,
`SolitairePreferences`, `KeyAction`; `solitaire.solver` with the solver and the deal generator; UI in `solitaire.ui`;
shared with the other games: `GameClock` in `dev.sluide.games.common`, the pause's `TabVisibility` and `PauseOverlay` in
`dev.sluide.games.common.ui`); menu in `ide` (`ToolsMenu`, `WindowTools`); decisions in
[ADR 0138](../adr/0138-solitaire.md) and [ADR 0131](../adr/0131-games-menu-minesweeper.md)

The card games Klondike, FreeCell and Spider in one tab of the editor area: a toolbar, a green table and a status
line. Every deal can be won: a solver has proven it before it is offered.

## Opening
- **Games → Solitaire** opens the tab. There is one per window: choosing the item again selects the open tab.
- Shortcut+W closes the tab. The game belongs to the window: after closing, a new tab shows the same game, with the
  clock paused meanwhile. After a restart a new game starts; only the last game and options and the high scores are
  kept.
- The first deal is found when the tab first shows; the table reads "Dealing…" and takes no input until it is there.

## Games and options
Three toolbar menus: **Game** (Klondike, FreeCell, Spider), **Draw** (Draw 1, Draw 3; only in Klondike) and **Suits**
(1 Suit, 2 Suits, 4 Suits; only in Spider). The menus show the current choice. That makes six modes, each with its own
high score:

| Mode | Key in `shared.json` |
|---|---|
| Klondike, Draw 1 | `klondike-1` |
| Klondike, Draw 3 | `klondike-3` |
| FreeCell | `freecell` |
| Spider, 1 suit | `spider-1` |
| Spider, 2 suits | `spider-2` |
| Spider, 4 suits | `spider-4` |

The default is Klondike, Draw 1; Spider starts with 1 suit. Each game remembers its own option: Draw is kept while
you play Spider, and back in Klondike it is as you left it. Choosing the current entry does nothing; another one starts
a new deal (after the guard question, see below) and is remembered.

## Rules
- **Klondike:** 7 columns, the stock turns 1 or 3 cards onto the waste (Draw 1 or Draw 3). Build columns downward in
  alternating colours; only a King, or a run headed by a King, goes onto an empty column. Foundations build up by suit
  from the Ace. A card may come back from a foundation onto a column. The waste goes back to the stock any number of
  times.
- **FreeCell:** all 52 cards face up in 8 columns, 4 free cells that hold one card each, 4 foundations. Build columns
  downward in alternating colours. A run of cards moves as far as free cells and empty columns allow: n cards if
  n ≤ (free cells + 1) × 2^(empty columns), the target column not counted. The game is decided when every column
  descends from bottom to top; then the rest goes to the foundations by itself.
- **Spider:** 104 cards, 10 columns, 8 foundations, 1, 2 or 4 suits. Any card goes onto a card one higher, whatever the
  suit, but only a run of one suit going down moves together. Any card or run goes onto an empty column. A click on
  the stock deals a row of ten, one card on each column, and only while no column is empty. A complete King to Ace run
  of one suit leaves for a foundation by itself; the foundation shows its King.

## Deals
- A deal is named by a **deal number** from 1 to 1,000,000 per mode: the shuffle comes from our own SplitMix64
  generator seeded from the number, so a number always shows the same deal. The same number gives different deals in
  different modes.
- **Only deals that can be won** are offered. A solver that sees the whole deal proves it; deals that cannot be won or
  are not proven within the budget of their mode (a quarter of a second, up to a second for Spider) are skipped. New Game takes a random number and goes upward from it to
  the first deal that is proven. The solver, its budget and how many deals it skips per mode (about a quarter of Spider
  4 Suits): [ADR 0138](../adr/0138-solitaire.md).
- Generation runs off the FX thread. When a game starts, the next deal of the same mode is made in advance, so New Game
  is usually instant; a mode change drops a prefetch that no longer fits. If no deal can be made, the status reads
  "Could not deal a game — F2 to try again".
- While a deal is being looked for (New Game or Deal Number…), the clock of the old game stands still. The Game, Draw
  and Suits menus stay usable meanwhile; New Game and Deal Number… do not.
- **Restart** replays the current deal from the start (see below).
- **Deal Number…** (toolbar) asks "Play which deal? (1–1,000,000)"; Play is off until the text is a number in that
  range. The status reads "Looking for deal #N…" while the solver checks it. A number that is proven winnable is dealt.
  Otherwise the dialog "Deal Number" asks, header "Deal #N cannot be shown to be winnable.", text "Play deal #M
  instead?", buttons Play and Cancel; M is the next winnable deal above N (wrapping after 1,000,000). If none is
  found, the status reads "No winnable deal found near #N" and the game stays.

## Playing
- **Drag** a card, or a run that can move, to another pile. While you drag, every legal drop target is outlined.
  Dropped anywhere else, the cards slide back.
  A drag stays with the table until the release, even when the pointer crosses other views or split dividers.
- **Click** on a card plays it the best way: onto a foundation if it can go there, else onto a column that has cards
  (the leftmost one that takes it), else onto an empty column, and a single card into a free cell. A click on a run
  moves the run. A click on a foundation card does nothing: cards come back from a foundation only by dragging.
- **Double-click:** the second click of a double-click is ignored, so a double-click acts as one click, and when the
  card can go to a foundation, that is where it goes. A second click that starts a drag still drags.
- **Stock:** a click draws (Klondike), recycles the waste when the stock is empty, or deals a row (Spider). Quick
  clicks on the stock each count. Spider with an empty column says "Fill every empty column before dealing a row".
- **Keyboard cursor:** only the four arrow keys move it (D is the stock key, so W A S D is not a cursor block here).
  Left and Right stay within their row (the top row or the columns). Up in a column selects deeper cards; past the run it goes to the top-row pile above (or the nearest to its left). Down from the top row goes to the column below, and in a column it selects fewer cards. The cursor
  is outlined in yellow. Space picks up the cursor's cards (outlined in blue) and, with cards picked up, drops them on
  the cursor's pile; Enter drops them too, and without a pick-up plays the cursor's cards as a click does. On the stock
  Space and Enter draw. Esc puts the picked cards back. F sends the cursor's top card to a foundation. A drop the rules
  refuse says "Can't move there".
- **Auto-finish:** nothing moves by itself until the game is decided: stock and waste empty, every card face up and the
  rest can go to the foundations (Klondike), or every FreeCell column descending. Then the cards go to the foundations
  one after another, about every 0.09 s. Spider has no auto-finish; full runs leave by themselves.
- The game is won when every card is on a foundation: the clock stops, the table takes no more input, the win burst
  plays and the score is recorded as a high score if it is one. The "Won with …" status shows at once, while the burst plays.

## Score
Classic points per mode, shown in the toolbar next to the moves and the time:

| Event | Klondike | FreeCell | Spider |
|---|---|---|---|
| Start | 0 | 0 | 500 |
| Waste to column | +5 | | |
| Card to a foundation | +10 | +10 | |
| Column card turned face up | +5 | | |
| Foundation back to a column | −15 | | |
| Recycling the waste | −100 (Draw 1), −20 (Draw 3) | | |
| Every move | | −1 | −1 (a stock row too) |
| Completed run | | | +100 |
| Time | −2 per full 10 s | | |
| Win | time bonus | time bonus | |

- **Time bonus** at the win, Klondike and FreeCell, when the game took at least 30 s: 700,000 divided by the whole
  seconds (23,333 at 30 s, 1,944 at 6 minutes). Spider has none.
- Klondike never shows less than 0; FreeCell and Spider may go below 0.
- **Undo** costs −1 and takes back the move's points; **redo** gives them back. **Hint** costs −20, and only when it
  shows a move (see below). Restart keeps these penalties.
- The **high score** is kept per mode and is the highest score of a won game.

## Undo, Redo, Restart, Hint
- **Undo and Redo** (U, Shift+U): unlimited, each counts as a move. A new move clears the redo steps. Undoing a move
  that turned a card up turns it face down again, and the cards slide back. Both are off while dealing, paused,
  auto-finishing and once the game is won.
- **Restart** (toolbar) is off until a move was made. It goes back to the first position of the current deal, replays the
  deal cascade, resets the move count and forgets the undo history; the clock keeps running, and the undo and hint
  penalties stay. It always asks first: title "Restart", header
  "Start this deal over?", text "Every move is undone; the clock keeps running.", buttons "Restart" and Cancel.
- **Hint** (H or the lightbulb): the solver looks, off the FX thread, for the next move of a winning line from the
  current position, within 2 seconds; the status reads "Thinking…". The cards to move and the target pile are outlined
  in amber for about 1.5 s; the move is not played. It costs 20 points, only when it shows a move.
  - When no winning line exists from here, the status reads "No winning line from here — undo 3 moves" (or "1 move"):
    the solver tries earlier positions of the game, newest first, each with a quarter of the budget and all within
    twice the budget, and says how far back the game can be won. Free.
  - When the solver cannot decide within the budget: "No hint found". Free.
  - Pausing the game or hiding the tab cancels a hint that is being looked for.
- **Guard:** New Game, a Game, Draw or Suits change and Deal Number… ask first when a move was made and the game is not
  won: title "New Game", "Change Game", "Change Draw", "Change Suits" or "Deal Number", header "Abandon this game?",
  text "Its score of 320 is lost.", the verb (New Game, Change or Abandon) as button and Cancel, which is the default.
  Closing the dialog counts as Cancel.

## Timer and pause
The timer in the toolbar shows m:ss (h:mm:ss from an hour). It starts when the deal appears, not while it is looked
for. It pauses by itself while the tab is hidden or the window is iconified. P or the Pause button pauses by hand (the
button then shows Resume) and covers the table with the "Paused" overlay, which hides the cards; clicking the overlay
resumes. While paused or won, input and the buttons that change the game are off; New Game, Deal Number… and the menus stay on.

## Layout and look
- The toolbar (a wrapping toolbar), in this order: Game, Draw or Suits, New Game ("New Game (F2)"), Restart ("Restart
  Deal"), Deal Number… ("Deal Number…"), Undo ("Undo (U)"), Redo ("Redo (Shift+U)"), Hint ("Hint (H)"), Pause ("Pause
  (P)"), and at the end the score ("Score: 320"), the moves ("Moves: 12"), the timer and the zoom group. The menus'
  tooltips are "Game", "Draw" and "Suits". The tab's tooltip is "Move every card to the foundations".
- The **table** is felt green in both themes, darker in the dark theme. Empty piles show a rounded outline. The status
  line below shows "Deal #48213 · High score: 4,210" (without the high score when there is none), or the hint and
  result texts.
- **Cards are drawn by Thoreau** in JavaFX, no existing card set is copied. A to 10 have the classic pip layouts, with
  rank and a small suit sign in two corners, the lower one turned. Jack, Queen and King have our own emblems: a framed
  panel with the large rank letter and a crown, diadem or shield in the suit colour. The back is a geometric pattern in
  a fixed blue, darker in the dark theme. Faces stay white in both themes, a little softer in the dark one. Each face is drawn once per
  card size into an image and cached, so 104 cards move smoothly.
- **Scaling:** the card width follows the tab width between 48 and 160 pixels; below the minimum the tab scrolls.
  Columns that grow too long squeeze the overlap of their cards to fit the height, down to half, before the tab
  scrolls.
- **Outlines:** the keyboard cursor is yellow, picked-up cards blue, a hint amber with a glow, and every legal drop target
  while dragging light green.

## Animations
- A move slides the cards in 150 ms; turning a card up flips it in 120 ms; undo and redo slide back and forth.
  Auto-finish moves slide in 80 ms, one every 90 ms.
- A new game deals in a quick cascade of about one second; the table takes no input meanwhile. A click or a key skips it.
- **Win burst:** the cards fly out of the foundations one after another in arcs with gravity and bounce, and fade
  out, about 3 to 4 seconds in all. Any click or key skips it. The status shows "Won with 4,210 points in 6:12" (with " — new high score!" for a record) from the moment of the win. The effect is our own, not a copy of another game's.

## Keys
| Input | Action |
|---|---|
| Arrow keys | Move the cursor; Up and Down in a column select a longer or shorter run |
| Space | Pick up the cursor's cards, or drop the picked ones on the cursor's pile; on the stock: draw |
| Enter | Drop the picked cards, else play the cursor's cards the obvious way; on the stock: draw |
| Esc | Put the picked cards back |
| D | Click the stock: draw, recycle the waste or deal a row, wherever the cursor is |
| F | The cursor's top card to a foundation |
| F2 | New game |
| U, Shortcut+Z | Undo |
| Shift+U, Shortcut+Shift+Z | Redo |
| H | Hint |
| P | Pause or resume |

W A S do nothing. The keys work only while the table has the focus (a click on it gives it), and not with Ctrl, Meta or
Alt; with Shortcut only Z and Shift+Z count (Cmd on macOS). Shortcut+Z has no menu entry; the table takes it. The
mouse: see [Playing](#playing). The full list is in [keyboard-shortcuts.md](keyboard-shortcuts.md).

## Status texts
- "Dealing…" while a deal is found; "Looking for deal #48213…" for Deal Number…; "Could not deal a game — F2 to try
  again" if that failed; "No winnable deal found near #48213" when no deal could be proven near a typed number.
- While playing: "Deal #48213", then " · High score: 4,210" when the mode has one.
- "Thinking…" while a hint is looked for; "No winning line from here — undo 3 moves"; "No hint found".
- "Fill every empty column before dealing a row" (Spider) and "Can't move there" (a refused drop by key).
- After winning: "Won with 4,210 points in 6:12", then " — new high score!" for a record.

## Stored
User-wide in `~/.sluide/shared.json`, field `solitaire` ([ADR 0101](../adr/0101-shared-data-file.md)): the last game,
its draw and suits options and the high score per mode. Plain values, no secrets.
```json
"solitaire": { "game": "spider", "draw": 3, "suits": 2, "high": { "klondike-1": 4210, "spider-2": 812 } }
```
`game` is `klondike`, `freecell` or `spider` (default `klondike`), `draw` is 1 or 3 (default 1), `suits` is 1, 2 or 4
(default 1), and the keys of `high` are the mode keys of the table above with the score as a whole number, which may
be negative. An invalid or missing value reads as its default and no high score; other fields of `solitaire` stay as
they are. Written a moment after a change, off the FX thread.

## Scaling
The cards follow the tab between 48 and 160 pixels wide, and the toolbar wraps when the tab is narrow. The Paused
overlay and the Dealing… text never change the size of the table.
Zoom: 25%–200% relative to Fit, see [game-zoom.md](game-zoom.md). The zoom group is the last item of the toolbar, stored
as `solitaire`; above Fit the table is larger than the tab and scrolls.

## Not included
Statistics (played, won, streaks), more games (Pyramid, TriPeaks, Golf, Yukon), FreeCell with fewer cells, Vegas
scoring, resuming after a restart, an option for safe automatic moves to the foundations, card-back themes, a draw key
that works with W A S D, and proving more Spider 4-suit deals (about a quarter are skipped today):
[ideas/games.md](../ideas/games.md).

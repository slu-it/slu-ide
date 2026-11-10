# Word Guess

**Modules:** `modules/games` (window bean `WordGuess` in `dev.sluide.games`; package `dev.sluide.games.wordguess`: the pure round in
`WordGuessGame`, the FX-free `WordGuessModel`, word lists, `WordGuessPreferences`, UI in `ui`); menu in `ide`
(`ToolsMenu`, `WindowTools`); decisions in [ADR 0132](../adr/0132-word-guess.md) and
[ADR 0131](../adr/0131-games-menu-minesweeper.md)

A five-letter word game: guess the hidden word in six tries. A tab of the editor area with a toolbar, the grid, an
on-screen keyboard and a status line. It is endless: a new word follows every round, and nothing is counted.

## Opening
- **Games → Word Guess** opens the tab. There is one per window: choosing the item again selects the open tab.
- Shortcut+W closes the tab. The round belongs to the window: after closing, a new tab shows the same round. After a
  restart a new round starts; only the language is kept.

## Playing
- Type five letters (keyboard or the on-screen keyboard) and press Enter. Each tile then turns green (right letter,
  right place), yellow (the letter is in the word elsewhere) or grey (not in the word). Repeated letters are counted:
  a letter is marked yellow only as often as it occurs in the word. The on-screen keys take the best colour known.
- A guess must be a word of the language's list. "Not in the word list" (the row shakes) and "Not enough letters"
  cost no try.
- After the round, Enter (or F2, or the Next Word button) starts the next word. Words do not repeat within a window
  until all of the language's answers have been used.

## Languages
The toolbar menu offers **English** and **Deutsch**. Choosing the other language starts a new word at once; choosing
the current one does nothing. In German, Ä, Ö and Ü are letters of their own, and ß is written SS. The on-screen
keyboard is QWERTZ with Ü, Ö, Ä for German, which also serves keyboards without umlauts.

## Keys
| Input | Action |
|---|---|
| Letters (of the language's alphabet; Ä, Ö, Ü in German) | Type into the active row |
| Backspace | Delete the last letter |
| Enter | Submit the row; after the round, the next word |
| F2 | Next word (a running round is skipped) |
| Click on an on-screen key | Same as the key |

Letters are typed also with Alt or Option held (the app has no Alt+letter binding, and some layouts type letters with
it), but not with Shortcut, Ctrl or Meta, which are left to the menus. Enter, Backspace and F2 act only without
modifiers (Shift aside). Known limit: on Windows, AltGr umlauts arrive as Ctrl+Alt and are ignored; use the on-screen
keys.

## Status texts
- "Guess 3 of 6" while playing.
- "Solved in 4 of 6 — Enter for the next word".
- "The word was CRANE — Enter for the next word" after the sixth wrong guess.
- "Skipped: the word was CRANE" after Next Word in a running round, until you type a letter of the new one.
- "Not enough letters", "Not in the word list".

## Word lists
Four lists in the module's resources: allowed guesses and answers per language.

| List | Words | Source and license |
|---|---|---|
| English, allowed | 8,672 | ENABLE2K, public domain |
| English, answers | 1,868 | SCOWL size 35, MIT-like license |
| German, allowed | 9,536 | enz/german-wordlist, CC0 |
| German, answers | 1,348 | picked from the allowed list by frequency (Leipzig Corpora Collection) |

The answers are common base forms, trimmed by hand; offensive words are left out. Sources, steps, hashes and license
texts: `modules/games/src/main/resources/dev/sluide/games/wordguess/NOTICE-word-lists.txt`.

## Scaling
The tile side follows the tab between 24 and 60 px, the keys with it.
Zoom: 25%–200% relative to Fit, see [game-zoom.md](game-zoom.md).

## Stored
User-wide in `~/.sluide/shared.json`, field `wordGuess` ([ADR 0101](../adr/0101-shared-data-file.md)): the last
language.
```json
"wordGuess": { "language": "de" }
```
`language` is `en` or `de`; a missing or invalid value reads as English. Written a moment after a change, off the FX
thread.

## Not included
Statistics, hard mode, a daily word, further languages and resuming after a restart; the German answer list is smaller
than the English one: [ideas/games.md](../ideas/games.md).

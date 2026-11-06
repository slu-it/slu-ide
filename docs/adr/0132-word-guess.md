# 0132. Word Guess: a second game of the Games menu, English and German, own word lists

- Status: accepted; amends [0131](0131-games-menu-minesweeper.md) (package layout of the `games` module)
- Date: 2026-11-06

## Context
The user asked for "Wordle" in the Games menu, with endless play (no daily word) and English and German words.
"Wordle" is a trademark of the New York Times and the name of one specific game; the rules of the genre (guess a
five-letter word in six tries, tiles show right, misplaced and absent letters) are not protected. The game needs word
lists in two languages whose licenses allow shipping them.

## Decision
- **Word Guess**, the second item of the Games menu, a `WindowTool` like Minesweeper (one tab per window, no key
  binding). Own name, own word lists and own look: nothing is taken from the NYT game but the genre's rules.
- **Endless play, no statistics**: a new word after every round. A bag per language, shuffled, gives every answer once
  per window before any repeats; the round outlives the tab like Minesweeper's game.
- **Languages** (English, Deutsch) in a toolbar menu. The last language is remembered user-wide in `shared.json`,
  field `wordGuess.language` ([ADR 0101](0101-shared-data-file.md)). Choosing another language starts a new word.
- **German**: Ä, Ö, Ü are letters of their own (not A, O, U); ß is written SS, so words with ß have five letters.
- **Rejecting non-words**: a guess must be in the language's allowed list ("Not in the word list"); the answers come
  from a smaller list of common words. A rejected guess costs no try.
- **On-screen keyboard** below the grid: it colours the letters by what is known, and it is needed for umlauts on
  keyboards without them. Typing works too: letters also with Alt/Option held (some layouts need it), not with Shortcut, Ctrl or Meta.
- **Next Word** (button, F2) is always enabled; in a running round it reveals the word and skips it.
- **Word lists**, with details, hashes and licenses in `NOTICE-word-lists.txt` next to them: English allowed words
  from ENABLE2K (public domain, 8,672 words); English answers from SCOWL size 35 (MIT-like license that requires the
  notice, 1,868 words); German allowed words from enz/german-wordlist (CC0, 9,536 words); German answers (1,348
  words) picked from the allowed list by word frequency of the Leipzig Corpora Collection (used only to rank, no
  corpus text is shipped). The downloadable Leipzig corpora are CC BY per its Terms of Usage (checked 2026-11-06);
  the CC BY-NC terms there cover the website's other data and applications, not the downloads, so commercial use of
  Thoreau is not restricted; the NOTICE gives the attribution. The answer lists are hand-trimmed to base forms (no
  plurals, no regular past forms), without offensive words.
- **Package layout** (amends ADR 0131): the window beans `Minesweeper` and `WordGuess` are in the module's root
  package `dev.sluide.games` (its API, as the modularization rule says); everything else of a game is in the package
  of that game (`minesweeper`, `wordguess`).
- JavaFX controls with the logic outside them ([ADR 0084](0084-testability-logic-out-of-controls.md)).

Considered:
- The NYT word lists: not licensed for reuse.
- Accepting any letters without validation: guesses would be trivial to game and meaningless.
- Transliterating umlauts as AE, OE, UE: it makes words longer than five letters and breaks real German words.
- A daily word: needs a calendar and statistics, and the user wanted endless play. Kept in the ideas.

## Consequences
- The word lists add to the jar, and their notice has to ship with them.
- Key bindings only while the grid has the focus; see [../features/keyboard-shortcuts.md](../features/keyboard-shortcuts.md).
- Feature page: [../features/word-guess.md](../features/word-guess.md); open ideas:
  [../ideas/games.md](../ideas/games.md).

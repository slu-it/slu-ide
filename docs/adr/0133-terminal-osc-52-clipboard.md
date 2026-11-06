# 0133. Terminal programs may write the clipboard with OSC 52, never read it

- Status: accepted; amends [0007](0007-terminal.md) (clipboard of the terminal)
- Date: 2026-11-06

## Context
Programs in a terminal copy to the system clipboard with the escape sequence OSC 52
(`ESC ] 52 ; <targets> ; <base64> BEL`): Claude Code (in a Docker sandbox tab), tmux, and neovim over ssh do it. A
program that turns on mouse reporting, like Claude Code, gets the mouse drags, so Thoreau's own selection is out of
reach without Shift+drag, and the program's copy is the natural way to copy. JediTerm 3.76 ignores OSC 52, and its
OSC handling (`JediEmulator.doProcessOsc`) is private, so nothing reached the clipboard.

OSC 52 also has a read form (`?` as the payload): the terminal answers with the clipboard's content. Terminals differ:
kitty, WezTerm, Ghostty and Windows Terminal allow writes without asking and refuse or ask for reads; iTerm2 asks
before the first write; xterm allows them only when configured to.

## Decision
- Programs may **write** the clipboard with OSC 52, always, without a setting or a question. **Reads are never
  answered**: a query is ignored, so no program can learn what the user copied elsewhere.
- All targets (`c`, `p`, `s`, `0`-`7`, any mix, none) mean the system clipboard: JavaFX has no primary selection.
  An empty payload, which would clear the clipboard in xterm, is ignored, as is invalid base64.
- Payloads over 4 MiB of base64 (3 MiB of text) are read to their end and dropped, so a runaway sequence neither
  fills the memory nor ends up on the screen.
- Line breaks in the payload are skipped, so the output of GNU `base64` (broken every 76 characters) decodes.
- Filtered below JediTerm, where the output is read: `TerminalSession`'s connector passes every read through an
  `Osc52Filter`, which takes complete `ESC ] 52 ; ...` sequences (ended by BEL, `ESC \` or ST) out of the output and
  hands their arguments to `clipboardText` (`Osc52.kt`); everything else reaches JediTerm unchanged and in order. A
  hook in the emulator (`TerminalStarter.createEmulator` returning a `JediEmulator` with its own `processChar`) was
  tried first and misses sequences inside synchronized-output blocks (`ESC [ ? 2026 h` ... `l`, in which Claude Code
  draws its frames): JediTerm reads such a block from the stream itself and replays it through a fresh plain emulator.
  A sequence may be split across reads: the filter keeps its state between reads and holds back a possible start
  (at most `ESC ] 5 2`, 4 characters) until the next read decides; at the end of the output it is passed on. An ESC
  inside a payload that is not `ESC \` aborts the sequence: nothing is copied and the ESC is processed as output.
- The text goes to `TerminalSession`'s `onCopy` on the thread that reads the output (`copyToClipboard`, which moves
  to the FX thread). Only lengths are logged, never the text.

## Consequences
- Copying in Claude Code, tmux and neovim (also over ssh) puts the text on the system clipboard, in tabs, command
  tabs and embedded terminals.
- A program, or a file shown with `cat` that contains the sequence, can replace the clipboard without the user
  noticing; pasting it then pastes what that file chose (paste injection). Accepted, like the terminals above:
  bracketed paste keeps a pasted text from running in shells that support it, and the program could do worse with
  the user's rights anyway.
- The filter does not parse other sequences: it recognizes `ESC ] 52 ;` wherever it appears, also inside another
  sequence (where JediTerm would have skipped it), and only in this exact form (no 8-bit introducer, no `052`).
- Updating JediTerm may change how it reads the connector (a read of 0 counts as the end, so the filter reads again
  when a read held only a clipboard write); the OSC 52 test of `TerminalSessionTest`, with a synchronized-output
  block, notices it.

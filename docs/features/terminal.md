# Terminal

**Module:** `modules/terminal` (package `dev.sluide.terminal`), depends on `modules/editor` (terminals are
`EditorTab`s); decision in [ADR 0007](../adr/0007-terminal.md)

- Terminal → New Terminal (Ctrl+Shift+T, Cmd+Shift+T on macOS) opens a terminal tab in the editor area
  ([editor-tabs.md](editor-tabs.md)). The shell starts in the project folder, or in the home folder if none is open.
- The shell is the user's login shell (`$SHELL -l`, falling back to `/bin/bash -l`) in a pseudo terminal with
  `TERM=xterm-256color`. `PWD` is set to the start folder, so shells show it as given, not with symlinks resolved.
- Tabs are named "Terminal 1", "Terminal 2", …; the tooltip shows the start folder. When the shell exits, the tab
  shows "(exited)" and stays open until it is closed.
- Closing the tab ends the shell. Programs it runs get a hangup (SIGHUP) from the closed pseudo terminal: most end,
  a JVM runs its shutdown hooks. bash also hangs up on its background jobs; `sh` does not, and `nohup` jobs keep
  running.
- While the shell runs programs (foreground or background jobs), closing the tab from the UI (×, middle click,
  context menu) asks first: "Terminal 1 is still running java." Cancel keeps the tab. Opening another project folder
  closes all tabs without asking, and quitting SluIde ends all shells without asking (`Terminals` is closed with the
  Spring context).
- Display: 16, 256 and true colours, bold, underline, inverse, dim; full-screen programs (`vim`, `less`, `top`) with
  the alternate screen. The grid follows the size of the tab, and the shell is told about every resize.
- Keyboard: the terminal takes the focus when its tab is shown. Enter, Tab, Esc, Backspace, arrows, Home/End,
  PgUp/PgDn, Insert/Delete and F1–F12 are sent as xterm sequences, Ctrl+letter as control characters (Ctrl+C, Ctrl+D, …).
  Alt+letter sends Esc and the letter, except on macOS, where Option types characters. Ctrl+Shift+letter and Cmd
  shortcuts are not sent to the shell, so menu shortcuts keep working.
- Scrollback: the mouse wheel scrolls through the history (not in full-screen programs). Typing scrolls back down.
- Copy and paste: dragging selects text. Ctrl+Shift+C / Ctrl+Shift+V (Cmd+Shift+C/V on macOS) or the context menu
  copy it and paste the clipboard. Pastes use bracketed paste when the shell asks for it.
- Structure: `Terminals` (Spring bean: opens terminals, ends them on shutdown), `TerminalTab` (`EditorTab`),
  `TerminalView` (canvas, input, selection; JediTerm `TerminalDisplay`), `TerminalSession` (pty4j process and
  JediTerm emulator), `TerminalKeys` (key translation), `TerminalColors` (palette).
- Not yet implemented: asking before opening another folder or quitting while programs run, mouse reporting to programs (e.g. clicking in `vim`), window titles set by programs,
  clickable links, search, font and colour settings, split terminals, Windows shells.

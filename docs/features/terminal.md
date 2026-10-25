# Terminal

**Module:** `modules/terminal` (package `dev.sluide.terminal` and its sub-packages, [ADR 0055](../adr/0055-sub-packages-within-modules.md)), depends on `modules/editor-area` (terminals are
`EditorTab`s); decision in [ADR 0007](../adr/0007-terminal.md)

- Terminal → New Terminal (Ctrl+Shift+T, Cmd+Shift+T on macOS) opens a terminal tab in the editor area
  ([editor-tabs.md](editor-tabs.md)). The shell starts in the project folder, or in the home folder if none is open.
- The explorer's context menu has Open in Terminal: the same kind of tab with a folder as working directory (the
  folder, the folder of a file, or the project folder on the empty area; tooltip "Terminal in <folder>"), with the same
  error if the shell cannot start ([ADR 0093](../adr/0093-explorer-open-in-terminal-and-reveal.md)).
- The shell is the user's login shell (`$SHELL -l`, falling back to `/bin/bash -l` if `SHELL` is unset or blank) in a
  pseudo terminal with `TERM=xterm-256color`. `PWD` is set to the start folder, so shells show it as given, not with
  symlinks resolved.
- If the shell cannot be started (e.g. `SHELL` names a missing program), an error dialog says so ("The terminal
  cannot be started. /bin/nosh could not be started in …") and no tab opens; `Terminals.open` throws
  `TerminalStartException`.
- Tabs are named "Terminal 1", "Terminal 2", … (a failed start uses up no number); the tooltip shows the start
  folder. When the shell exits, the tab shows "(exited)" and stays open until it is closed.
- Closing the tab ends the shell. Programs it runs get a hangup (SIGHUP) from the closed pseudo terminal: most end,
  a JVM runs its shutdown hooks. bash also hangs up on its background jobs; `sh` does not, and `nohup` jobs keep
  running.
- While the shell runs programs (foreground or background jobs), closing the tab from the UI (×, middle click,
  context menu) asks first: "Terminal 1 is still running java." Cancel keeps the tab. Tabs that run a command
  (`Terminals.open(folder, name, command)`, e.g. Docker sandboxes) ask as long as the command runs ("Claude Code 1 is
  still running: sbx run claude ."): the login shell runs the command in its own process, so there are no child
  programs to look for. Closing the window asks once for all its tabs ([open-folder.md](open-folder.md)) and then ends
  its shells (each window has its own `Terminals`, closed with the window).
- Font: the terminal font from the settings ([settings.md](settings.md)); running terminals follow changes and tell
  the shell the new grid size.
- Display: 16, 256 and true colours, bold, italic, underline, inverse, dim and hidden text; full-screen programs
  (`vim`, `less`, `top`) with the alternate screen. The grid follows the size of the tab, and the shell is told about
  every resize. The cursor is filled while the terminal has the focus and outlined otherwise.
- Colours: a fixed dark palette (light grey on `#1e1e1e`, xterm colours), whatever the Light/Dark/System theme
  ([settings.md](settings.md)) is.
- Keyboard: the terminal takes the focus when its tab is shown. Enter, Tab, Esc, Backspace, arrows, Home/End,
  PgUp/PgDn, Insert/Delete and F1–F12 are sent as xterm sequences, Ctrl+letter as control characters (Ctrl+C, Ctrl+D, …),
  and so are Ctrl+Space, Ctrl+2, Ctrl+[, Ctrl+\, Ctrl+] and Ctrl+-. Alt+letter sends Esc and the letter, except on
  macOS, where Option types characters. Ctrl+Shift+letter and Cmd shortcuts are not sent to the shell, so menu
  shortcuts with them keep working (New Terminal, and on macOS all menu shortcuts except Option+F1), and so does Ctrl+, (Settings).
  Menu shortcuts that the terminal sends to the shell do not reach the menu: on Linux, Ctrl+O sends ^O to the shell
  instead of opening File → Open Folder, Ctrl+W goes to the shell instead of closing the tab (on macOS Cmd+W finds
  File → Close Tab disabled for terminals and leaves the tab open), Ctrl+K sends ^K instead of opening
  Git → Commit…, and Alt+F1 (Option+F1 on macOS too) is sent to the shell as the xterm sequence for Alt+F1 instead of choosing View → Select Opened File, while a terminal has the focus; use the menu or click outside the terminal first.
- Scrollback: the mouse wheel scrolls through the history (the last 5000 lines, JediTerm's default; older lines are
  dropped). Typing or pasting scrolls back down. Full-screen programs scroll themselves: the wheel is reported to them
  if they asked for mouse reports (e.g. Claude Code's fullscreen TUI, `vim` with `mouse=a`), otherwise sent as Up/Down
  arrow keys (e.g. `less`, `man`).
- Scroll speed: every scroll event is worth 3 lines at 100 %, scaled by the trackpad or mouse wheel speed of
  Terminal → Scrolling ([settings.md](settings.md), 10–200 %); fractions of lines add up over the next events in the
  same direction (at 50 % two events scroll 3 lines) and are dropped when the direction turns or the view jumps back
  to the bottom. An event counts as a trackpad's if it arrives between JavaFX's `SCROLL_STARTED` and
  `SCROLL_FINISHED` (a gesture with fingers on the pad), has touch points, or is an inertia event (sent after the
  fingers lift); everything else is a wheel. This relies on the events macOS sends: there a Magic Mouse counts as a
  trackpad too, while on Linux and Windows touchpads send plain wheel events and use the Mouse wheel speed. A
  trackpad swipe sends dozens of small events, so it scrolled far too fast at 3 lines each. The speeds apply to the scrollback and to arrow keys sent to full-screen programs (one per
  line); programs that asked for mouse reports get one report per 3 lines (at 100 % one per event, at 50 % one every
  second event, at 200 % two). Shift+wheel is never reported: it scrolls the scrollback (normal screen) or does
  nothing (full screen), as before. Open terminals follow speed changes right away.
- Mouse: programs that ask for mouse reports (normal, button-motion and any-motion tracking; xterm, UTF-8, SGR and urxvt
  formats, encoded by JediTerm) get left and middle clicks, drags, moves and the wheel, with Ctrl/Alt. Shift+drag
  still selects text; the right button always opens the context menu.
- Copy and paste: dragging selects text. Ctrl+Shift+C / Ctrl+Shift+V (Cmd+Shift+C/V on macOS) or the context menu
  copy it and paste the clipboard. Pastes use bracketed paste when the shell asks for it.
- Other modules can run a command in a terminal tab: `Terminals.open(folder, name, command)` runs it with
  `$SHELL -l -i -c` (an interactive login shell, so the same startup files as a tab apply, e.g. `.zshrc`;
  [ADR 0087](../adr/0087-commands-in-an-interactive-login-shell.md)) in a tab with the given name; the tab shows
  "(exited)" when the command ends (used by [docker-sandbox.md](docker-sandbox.md)). `loginShellRunning(command,
  environment)` returns that shell's command line. `loginShellFinds(program, environment)` asks that shell whether it
  finds a program (`command -v`; the `sbx` and `gradle` checks), in a pseudo terminal of its own, so the interactive
  shell never touches the terminal Thoreau was started from.
- Views of other modules can embed a terminal that runs one command, in a pane of their own instead of a tab
  (`Terminals.embed(folder, command)`, an `EmbeddedTerminal`; [ADR 0086](../adr/0086-embedded-command-terminals.md)).
  It runs `$SHELL -l -i -c` like a command tab and follows the font and scroll settings. When the command ends,
  `exited` turns true and `exitCode` holds its exit code (none if it was closed before); the output stays visible.
  `interrupt()` sends Ctrl+C through the pseudo terminal (exit code 130 for a command that dies of it), `close()` ends
  the command with a hangup like closing a tab. Embedded terminals do not count as open tabs, but are closed with the
  window. Each run gets a fresh terminal. Unlike a tab, an embedded terminal does not take the focus when it is shown,
  only when it is clicked, so the view keeps the focus where the user was.
- Structure: `Terminals` (window-scoped Spring bean: opens terminals, ends them when its window closes),
  `TerminalTab` (`EditorTab`), `EmbeddedTerminal` with `EmbeddedTerminalView` (one-command terminal for another
  view), `TerminalView` (canvas, input, selection; JediTerm `TerminalDisplay`),
  `TerminalPainter` (`TerminalPainter.kt`: draws the canvas), `TerminalSession` (pty4j process and JediTerm emulator),
  `TerminalKeys` (key translation), `TerminalMouse` (mouse translation), `ScrollAccumulator` (scroll speeds, fractions
  of lines), `TerminalColors` (palette), `settings` package: `TerminalScrollSettings` (Spring bean, trackpad and wheel
  speed), `TerminalScrollPage` with `TerminalScrollPageModel` (Terminal → Scrolling,
  [ADR 0067](../adr/0067-feature-settings-in-their-module.md)).
- Not yet implemented: window titles set by programs, clickable links, search, colour settings (including a light
  palette), split terminals, Windows shells.

# Settings

**Module:** `modules/settings` (package `dev.sluide.settings` and its sub-packages, [ADR 0055](../adr/0055-sub-packages-within-modules.md)), decision in [ADR 0008](../adr/0008-settings.md)

- File → Settings… (Ctrl+, / Cmd+,) opens the settings dialog: a tree of pages on the left, the selected page on the
  right. Selecting a group (e.g. Display) shows its first page.
- OK applies all edits and closes (unless storing fails, see below), Apply applies and keeps the dialog open, Cancel
  drops the edits. Nothing changes in the IDE before OK or Apply. The dialog always opens with the current settings.
- Edits are applied only once they are stored. If a page's edits cannot be stored (e.g. `~/.sluide` is read-only or
  the settings file cannot be read), that page changes nothing, the other pages are still applied, an error message
  names the failed pages, and OK keeps the dialog open.
- Settings are stored in `~/.sluide/settings.properties` (keys and formats in [ADR 0008](../adr/0008-settings.md))
  and apply live to open views. Values that cannot be used (e.g. an unknown style or a size outside 6–72) fall back
  to the defaults; one unusable value of a font discards that area's whole font (family, size and style); a scroll
  speed that is not a multiple of 5 from 10 to 200 means 100 %. A file that cannot be read at all counts as no
  settings and is not overwritten.
- Display → Appearance: the theme, Light, Dark or System (default; follows the operating system's light or dark mode,
  also while the IDE runs). It applies to all windows, menus and dialogs, including syntax colours; the terminal stays
  dark. See [ADR 0009](../adr/0009-themes.md).
- Display → Fonts: font, size (6–72) and style (Regular, Bold, Italic, Bold Italic) separately for
  - File Explorer (default: the system UI font),
  - Editor, i.e. all open file views and the diffs opened from the git log (default: Monospaced at the system font
    size); syntax highlighting keeps its own bold/italic tokens,
  - Terminal (default: Monospaced 13). Only monospaced fonts are offered, so columns line up; bold and italic text
    use the bolder or italic variant of the chosen font. The shell is told the new grid size.
  - Font names can be typed; Enter takes a name only if it is in the list. "Restore Defaults" resets one area.
  - A preview below shows a sample tree, editor text and terminal output in the edited fonts.
- Terminal → Scrolling: the scroll speed of the terminal, separately for Trackpad and Mouse wheel, as a slider from
  10 to 200 % in steps of 5 % (default 100 %, 3 lines per scroll event). Lower the trackpad speed if small swipes scroll
  too far, e.g. with a Magic Trackpad on macOS. Only macOS tells trackpads apart (a Magic Mouse counts as one); on
  Linux and Windows touchpads use the Mouse wheel speed. Open terminals follow at once; details in
  [terminal.md](terminal.md).
- Structure: `FontSpec`, `FontStyle`, `FontArea` (values), `SettingsStore` (properties file), `FontSettings` (Spring
  bean, properties per area), `SettingsPage`/`SettingsPages` (pages as beans), `SettingsDialog`, `FontsPage` with
  `FontsPageModel` (drafts), `Theme`, `ThemeSettings` (Spring bean, theme and colour scheme), `ThemedWindows` (applies
  the colour scheme and `theme.css` to every window), `AppearancePage` with `AppearancePageModel`.
  Users: `FileExplorerView`, `EditorArea` (binds every open file view and diff tab),
  `Terminals` (each `TerminalView` follows the terminal font), `MainWindow` (installs
  `ThemedWindows`). The terminal scroll settings live in the `terminal` module (package `dev.sluide.terminal.settings`,
  [ADR 0067](../adr/0067-feature-settings-in-their-module.md)): `TerminalScrollSettings` (Spring bean, trackpad and
  wheel speed), `TerminalScrollPage` with `TerminalScrollPageModel`; they are stored in the same file under the same
  keys and the page is a `SettingsPage` bean like the others.
- Not yet implemented: font settings for menus and dialogs, line spacing, per-project settings, a light terminal,
  custom colours.

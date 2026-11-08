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
- Display → Appearance: the theme, Light, Dark (default) or System (follows the operating system's light or dark mode,
  also while the IDE runs). It applies to all windows, menus and dialogs, including syntax colours; the terminal stays
  dark. In the dark theme, text fields and areas are a little brighter than the panes and have a lighter edge, so they read as inputs (code editors keep the pane colour), and their placeholder text is lighter than the field, so it stays readable.
  See [ADR 0009](../adr/0009-themes.md).
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
- Docker: the Executable of the Docker CLI (`docker.executable`, default `docker`): `docker`, `podman`, or the full
  path of either. A name is found through the login shell like in a terminal; an alias or function there needs the
  full path. Lives in the `docker` module (`DockerSettings`, `DockerSettingsPage` with `DockerSettingsPageModel`,
  package `dev.sluide.docker.settings`); a window that uses Docker connects again when it changes
  ([ADR 0117](../adr/0117-docker-through-the-cli.md)).
- Databases: rows per result page (`databases.pageSize`, default 500, 10 to 10,000), the most rows Last and export
  fetch (`databases.fetchAllCap`, default 100,000, 1,000 to 10,000,000) and the statement timeout in seconds
  (`databases.statementTimeoutSeconds`, default 0 for none, up to 86,400). They apply at once. Lives in the
  `databases` module (`DatabasesSettings`, `DatabasesSettingsPage` with `DatabasesSettingsPageModel`, package
  `dev.sluide.databases.settings`; [ADR 0121](../adr/0121-databases-tab.md)).
- Speech: the optional path of espeak-ng (`speech.espeak`, empty finds it on the login shell's PATH) and the Kokoro voice
  model per variant (fp32, fp16, int8) with its status, Download and Delete, and the variant in use
  (`speech.kokoro.variant`). Lives in the `speech` module (`SpeechSettings`, `SpeechSettingsPage` with
  `SpeechSettingsPageModel`, package `dev.sluide.speech.settings`; [read-aloud.md](read-aloud.md),
  [ADR 0124](../adr/0124-read-aloud-speech.md)).
- Translation: the downloaded translation models, one row per pair and quality with its size and Delete (after a
  question, Cancel is the default), the total size on disk (shared files once) and the attributions of OPUS-MT and
  Lingua. A model whose download or delete failed is listed as "incomplete" with the message; Delete removes what is
  left. Nothing to apply. Lives in the `translation` module (`TranslationSettingsPage` with
  `TranslationSettingsPageModel`, package `dev.sluide.translation.settings`; [translate.md](translate.md),
  [ADR 0126](../adr/0126-translate-tool.md)).
- Languages → Python: ty, the language server of Python: its status, Install/Cancel, Check for Updates with Update and
  the release notes, Roll Back, Delete… (after a question, Cancel is the default), and the ty executable, Thoreau's
  download or a custom path (`python.ty.path`, applied on OK or Apply). Lives in the `python` module (`PythonSettings`,
  `PythonSettingsPage` with `PythonSettingsPageModel`, package `dev.sluide.python.settings`; [python.md](python.md),
  [ADR 0144](../adr/0144-python-via-ty.md)).
- Editor: the check box "Open library code read-only" (`editor.libraryCodeReadOnly`, on by default) with a note on
  what counts as library code; applies on OK or Apply, to open tabs at once. Lives in `file-viewers`
  (`EditorSettingsPage` with `EditorSettingsPageModel`, package `dev.sluide.fileviewers.settings`;
  [library-code.md](library-code.md), [ADR 0150](../adr/0150-library-code-read-only.md)).
- Secrets: the secret store, saved passphrases with Remove and Forget All Saved Secrets. Lives in the `secrets` module
  (`SecretsSettingsPage` with `SecretsSettingsPageModel`; [secrets.md](secrets.md)).
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

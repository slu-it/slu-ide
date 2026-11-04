# UI conventions

How Thoreau's features look and behave, so a new feature reuses them instead of rediscovering them. Lines name the
decision record where there is one; deviate only with a reason in the feature's ADR. Key bindings: `features/keyboard-shortcuts.md`.
Safety of file operations, runs, secrets and network: `.claude/rules/safety.md`.

## File views with a second side
- **Two families.** Previews (Markdown, JSON, XML, YAML, SVG, ...) use the `previews` module's `PreviewMode`
  (Source | Split | Preview, ADR 0016, 0017). Run views (shell script, Gradle, Compose, Dockerfile) use the public
  `SourceSplit` from `file-viewers` (Source | Split | `<label>`, e.g. Output, Gradle, Compose, Build; ADR 0085).
  A new view with a second side uses `SourceSplit`; moving previews onto it is planned (`features/file-viewers.md`).
- **Default mode:** Split, unless the second side is useless without the first or the first is rarely needed:
  Preview alone for notebooks, SVG, PEM; Source for Bicep, workflow graphs, and Gradle (until a wrapper is found).
- **Focus on open** goes to the source editor (ADR 0050). Exceptions: a view showing only its second side, and
  zooming previews in Split. Opening from the explorer or the git log always focuses the tab: its editor, or the
  web view of a file that opens as Preview alone (SVG, notebooks, PEM).
- **Mode switches by themselves** only to show what the user asked for: a run turns Source into Split (Output stays
  Output); jumping to a line turns Preview/Output into Split. The mode is not remembered after the tab closes.
- **Mode toggles** are non-focusable `ToggleButton`s at the right end of the toolbar; a click leaves the focus where
  it was, clicking the selected one keeps it selected. The divider position survives hiding a side.

## Toolbars above views
- Order from left to right: the view's own buttons (Run, Export, TOC, folds, Copy Formatted), a spacer, the
  preview's `actions` (e.g. the workflow's Fetch), document zoom (ADR 0083) and the mode toggles at the far right.
  Images and diagrams keep their zoom with Fit on the left. Controls that only make sense for one side hide while it
  is hidden.
- Toolbar controls are not focus-traversable. Icon-only buttons get a tooltip; one with a key binding says it:
  `"Zoom In (Shortcut++)"`.
- Icons: Ikonli Material Design 2 `FontIcon` (ADR 0012), coloured by the stylesheet with
  `-fx-icon-color: -fx-text-base-color` so they follow the theme. Style the toolbar like `preview-view.css`
  (`.preview-toolbar`): no new colours, small padding.

## Tool and service tabs
- Tools menu (Coloring, Codecs, Converting) and Services menu (Docker, Databases) after Docker Sandbox in the menu bar
  (ADR 0110, 0118, 0121). A tool is a `WindowTool` in `ide` (`ide/.../tools/ToolsMenu.kt`), not a module API.
- One tab per window: the menu selects the open tab or creates one. Closes with Shortcut+W without a question and is
  not restored after a restart. Tools have no key binding; a service may get an Alt+N panel key (Docker: Alt+8,
  Databases: Alt+7).
- The module exposes a window bean with `createTab()` and callbacks that `ide` sets (`showTab`, `openTab`,
  `openScratch`, ...) in `WindowTools` or `MainWindow`; a callback left unset fails silently, so the wiring gets a
  test.
- State lives in the window and outlives the tab; anything stored is opt-in per tool and never holds pasted secrets
  (Codecs and Converting store nothing).
- A tab's content leaves the scene whenever another tab is selected (`EditorArea` shows only the selected content):
  a view that unsubscribes when its scene becomes `null` subscribes again and catches up when it is back
  (`ConnectionCardList`), or never unsubscribes on scene loss at all.

## Context menus and entry points
- Explorer: `FileAction(label, icon, appliesTo, perform)` items appear only for a single file they apply to, after
  Open, and are never shown disabled (Run, ADR 0089). Folder actions use `FolderAction` (ADR 0056). Convert to ▸ is
  its own submenu hook (ADR 0115), shown the same way: only for a file it applies to.
- Editor: extensions add items after a separator through `CodeEditorExtension` (ADR 0108). For new items (only
  Converting does it so far): "Open Selection in X" with a selection and "Open in X" without; when an item cannot
  act, disable it with the reason in the label ("(larger than 1 MiB)").
- Opening something at a place: `FileViewHost.openFile(file, line)` / `FileView.showLine(line)` (ADR 0077).
  Generated text the user may keep opens as a scratch file (ADR 0066, 0114).

## Running commands
- Through `command-runs`: `RunPane` (Arguments field with history, status line with Stop and Rerun, embedded
  terminal) and `CommandRunner`, one job per view (ADR 0086, 0088, 0091). Commands run in `$SHELL -l -i -c`
  (ADR 0087). An interactive shell opens as a terminal tab (`Terminals.open`).
- A running view marks its tab (`FileView.activity`) and asks before closing (`closeWarning`).
- Window-wide services (`CommandRunSession`, `DockerSession`, `BackgroundTasks`) are registered as window services
  and read with `FileViewHost.windowService(Type)` (ADR 0070); without one, the action is disabled with a reason.

## Dialogs and questions
- Plain JavaFX `Alert`/`Dialog` owned by the view's window (`initOwner`), a title naming the action,
  `headerText = null` or the question, verb buttons (`ButtonType("Delete", ButtonData.OK_DONE)`), and a dismissed
  dialog counts as Cancel. There is no shared helper.
- Unsaved file before a run: "X has unsaved changes." with "Save and <Verb>", "<Verb> Saved Version", Cancel
  (ADR 0089). Closing an editor: Save, Don't Save, Cancel. Destructive or replacing questions name the consequence
  and make Cancel the default button.
- Tests replace dialogs through an overridable function property (`askUnsaved`) or an FX-free dialogs interface
  (`DockerDialogs`), per ADR 0084.
- A dialog with larger content (a `TabPane`, a table) sets `minHeight = Region.USE_PREF_SIZE` on that content and the
  dialog pane: on Linux the window can be sized before the content's preferred height is known, and a `VBox` then
  shrinks it to nothing (the connection dialog of the Databases tab showed only its buttons).

## Storing state
| What | Where | API |
|---|---|---|
| User settings, applied live | `~/.sluide/settings.properties` | `SettingsStore` + a settings bean and `SettingsPage` in the feature module (ADR 0008, 0067) |
| Per project, or per file in a project | `~/.sluide/projects/<uuid>.json` | `ProjectDataStore`, one top-level field per module (ADR 0090); option stores write only values that differ from the defaults (`ComposeStore`) |
| User-wide, and files outside any project | `~/.sluide/shared.json` | `readShared()` / `updateShared { }` (ADR 0101) |
| Modes, zoom, tool texts | view or window lifetime | none |
- Histories ("remembered per X") are newest first, capped (run arguments: 20 per file), and record only runs that
  started (ADR 0091). Explorer moves and deletes re-key per-file data.

## Long-running work
- Anything that can take noticeable time runs off the FX thread on `NamedThreads` (ADR 0060); work the user should see
  is a `BackgroundTasks` row with a cancel function when it can be stopped (ADR 0061); rows show only after 0.5 s.
  Frequent reads the user did not start (commit details, diffs, highlighting) are logged, not registered as tasks.
- `FxWatchdog` warns after 1 s of a blocked FX thread; git on the FX thread logs a warning.

## Theming
- Dark is the default theme (ADR 0009). `theme.css` re-tints Modena for every window, dialogs and popups included;
  a module needs no dependency on `settings`.
- A stylesheet that uses only looked-up colours (`-fx-background`, `-fx-text-base-color`, `derive(...)`) needs no
  dark rules; say so in its first comment. One with its own colours adds an `@media (prefers-color-scheme: dark)`
  block with single-class selectors (`.css` rules: `.claude/rules/theming.md`).
- Text inputs use `theme.css`'s `.text-input` fill and border; a control that draws its own input box copies those
  values with a "keep in sync" comment (`git-log.css`). Code editors keep the pane colour. The terminal is dark in
  every theme, and the video area of the media view is black in every theme. WebView pages follow the scene's scheme
  through a `dark` body class (ADR 0016).
- Check new UI in the real app in dark (`testing.md`, `run-app.sh`), and in light too when colours changed.

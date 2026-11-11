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
- A toolbar at the top of an editor tab is a `WrappingToolbar` (`file-viewers`, ADR 0135), not an `HBox` with a
  spacer: what came before the spacer goes into `leading`, what came after it into `trailing`; `hgap` (`-fx-hgap`)
  replaces `HBox(n)`/`-fx-spacing`, `WrappingToolbar.setMargin` replaces `HBox.setMargin`. In a narrow view the
  trailing controls stay at the right end of the first row and the leading ones wrap onto further rows; a nested
  `HBox` wraps as one group, and so do the toggle buttons of one `ToggleGroup` (the mode toggles). A wrapped
  trailing group leaves its last row's free space to the leading controls. Docker logs keep their `ToolBar` with the »
  overflow.
- Order from left to right: Soft Wrap (`CodeEditor.softWrapButton`, ADR 0130) in a view that embeds a code editor,
  the view's own left controls (Run, TOC, folds, Copy Formatted, Scripts or image zoom), the
  items code editor extensions contribute (`CodeEditor.toolbarItems`, e.g. Read Aloud; previews place them, in every
  mode; other views that embed a code editor don't yet); then, trailing, the preview's `actions` (e.g. the workflow's
  Fetch), Export (hidden in Source), document zoom (ADR 0083) and the
  mode toggles at the far right. Images and diagrams keep their zoom with Fit on the left.
  The extensions' items are a group of their own: previews set them apart by a 12 px gap (twice the gap within a
  group); without items the gap goes too.
  Controls that only make sense for one side hide while it is hidden; Soft Wrap hides while the source is hidden (the
  embedding view decides, the editor never sets its visibility). A code view of its own (not embedded) always shows
  its row: Soft Wrap, then the extensions' items.
- Toolbar controls are not focus-traversable. Icon-only buttons get a tooltip; one with a key binding says it:
  `"Zoom In (Shortcut++)"`. A `Slider` still takes the focus on a click (its behaviour asks for it regardless) and
  then eats the arrow keys: hand the focus back to the view (`MediaControlBar.install`).
- Icons: Ikonli Material Design 2 `FontIcon` (ADR 0012), coloured by the stylesheet with
  `-fx-icon-color: -fx-text-base-color` so they follow the theme (the game logos are the exception: fixed colours
  on purpose, ADR 0156). Style the toolbar like `preview-view.css`
  (`.preview-toolbar`): no new colours, small padding.
  Icons in a control's context menu (`Control.contextMenu`) are the exception: that menu is shown relative to the
  window, so only the scene root's stylesheets reach it, not the module's. Colour them inline by appending to the
  icon's own style (a `FontIcon` keeps its glyph font there) with `-fx-icon-color: -fx-text-background-color`, which
  also follows the highlighted item (`FILE_ACTION_ICON_STYLE` in the explorer's `ExplorerMenu.kt`).
- Fonts: a file view inherits the editor's (monospace) font. Its UI parts that are not text to edit — toolbars, side
  panels, lists, notices, overlays — set `-fx-font-family: "System";` in the module's stylesheet, as `code-view.css`
  does for the toolbar, banners and find bar (the ebook reader's Contents and Search panels showed in the code font
  until they did). Plain `Text` nodes in a cell (a `TextFlow`) get no `.text` style class: give them a class of their
  own to colour them, or they stay black in the dark theme.
- Below the text: `CodeEditor.addStatusItem` adds items to a bar below the editor text (hidden while empty), e.g.
  the Python project of a file (ADR 0151).

## Tool and service tabs
- Tools menu (Coloring, Codecs, Converting, Read Aloud, Translate), Services menu (Databases, Docker) and Games menu
  (Block Breaker, Doubles, Minesweeper, Mini Golf, Orbit Guard, Pipe Puzzle, Solitaire, Sudoku, Word Guess; the Services and Games lists are sorted by title, so a new entry lands in place) after Docker Sandbox in the menu bar (ADR 0110, 0118, 0121, 0131, 0132, 0136, 0137, 0138, 0139, 0140, 0152, 0153, 0156), then Help (Capture Diagnostics, Reveal Diagnostics Folder, About Thoreau; ADR 0127) last. A tool is a `WindowTool` in `ide` (`ide/.../tools/ToolsMenu.kt`), not a module API.
- A game shows a self-drawn, coloured 16 px logo on its Games menu item and its tab header (`<Game>Logo.kt` in the game's `ui` package, fixed colours, vector shapes; `WindowTool.graphic` and `EditorTab.icon()`, a fresh node per call); a new game adds its logo (ADR 0156).
- One tab per window: the menu selects the open tab or creates one. Closes with Shortcut+W without a question and is
  not restored after a restart. Tools have no key binding; a service may get an Alt+N panel key (Docker: Alt+8,
  Databases: Alt+7).
- The module exposes a window bean with `createTab()` and callbacks that `ide` sets (`showTab`, `openTab`,
  `openScratch`, ...) in `WindowTools` or `MainWindow`; a callback left unset fails silently, so the wiring gets a
  test.
- State lives in the window and outlives the tab; anything stored is opt-in per tool and never holds pasted secrets
  (Codecs and Converting store nothing; Read Aloud stores the last voice and speed user-wide, Translate the last source, target and quality; neither the text; Word Guess the last language; Doubles the last size and best scores; Sudoku the last size and level and the best times; Solitaire the last game, draw and suits and the high scores; Orbit Guard and Block Breaker the last difficulty and the best score per difficulty; Mini Golf the best total and the best per hole; Pipe Puzzle the last mode, size and look and the best time per mode and size; every game its zoom, in `gameZoom`).
- A game tab has the zoom group (zoom out, level menu, zoom in, Fit) at the far right of its toolbar, after its own
  trailing controls, and zooms with Shortcut+Plus/Minus/0, Shortcut+wheel and pinch anywhere in the tab
  (`GameZoomControls` in `games/common/ui`, ADR 0155).
- A game tab sets `EditorTab.focusOnShow`: the editor area focuses its content when it is opened or chosen (ADR 0050),
  and the view passes the focus on to its board or grid with `FocusWhenShown`, as that is in a scene only after the
  first layout. A press anywhere in the tab that no child consumes returns the focus to the board via
  `focusBoardOnPress` in `games/common/ui`; a `ScrollPane` focuses itself on every press and consumes it, so the
  scroll panes hand their focus on to the board.
- A real-time game (Orbit Guard, Block Breaker, Mini Golf) uses `RealtimeSession` and `RealtimeLoop` from `games/common`: the game pauses when its board loses the keyboard focus, resumes only by a key, the Resume button or, in Block Breaker and Mini Golf, a click on the focused game, with a 3-2-1 countdown, and no timer runs while it is not animating (ADR 0139, 0140, 0152). Menus (menu bar, toolbar `MenuButton`s, context menus) open as
  popups that never take the board's focus; `RealtimeLoop` counts any open menu of the board's window as a focus loss.
- A tab's content leaves the scene whenever another tab is selected or it moves to the other side of the editor area
  (`EditorArea` shows only the selected content): a view that unsubscribes when its scene becomes
  `null` subscribes again and catches up when it is back (`ConnectionCardList`), or never unsubscribes on scene loss at all.

## Context menus and entry points
- Menu bar, in this order: File, View, Navigate (Back, Forward | Go to Definition, Find Usages, File Structure, Go to Symbol…; ADR 0146, ADR 0149), Git,
  Terminal, Docker Sandbox, Tools, Services, Games, Help (the tool and service menus: see Tool and service tabs).
- Explorer: `FileAction(label, icon, appliesTo, perform)` items appear only for a single file they apply to, after
  Open, and are never shown disabled (Run, ADR 0089). Folder actions use `FolderAction` (ADR 0056). Convert to ▸ is
  its own submenu hook (ADR 0115), shown the same way: only for a file it applies to.
- Editor: extensions add items after a separator through `CodeEditorExtension` (ADR 0108). For new items (Converting:
  "Open Selection in X" with a selection and "Open in X" without; Read Aloud: "Read Selection Aloud" and "Read Aloud"; Translate: "Translate Selection" and "Translate"),
  when an item cannot act, disable it with the reason in the label ("(larger than 1 MiB)").
- Opening something at a place: `FileViewHost.openFile(file, line)` / `FileView.showLine(line)` (ADR 0077).
  Generated text the user may keep opens as a scratch file (ADR 0066, 0114).
- Caret popups and hover: lists at the caret in a code editor (completion, choosers, usages) use `CaretPopup`, and
  information about a place in the text goes into the hover popup as a `HoverProvider` section (module
  `code-intelligence`, ADR 0145): below the anchor, above when there is no room; the focus stays in the editor, the
  popup's keys are editor key bindings taken only while it shows; it hides on Escape, a click outside, scrolling, focus
  loss and window moves. Its stylesheet goes onto the popup's content, as popups do not inherit the editor's; in dark the
  popups get a fill a little brighter than the editor and a light edge, so they stand out from the text. A popup
  the user types into (a query, a new name) is a `SearchPopup` (field over a list, at the top centre of the editor or
  window) or a field in a plain `Popup` like the rename field: it takes the focus, its field handles the keys, and it
  gives the focus back when it hides (ADR 0149). Never a `CaretPopup` or `EditorPopupWindow` for that: they strip focus
  traversal.
- Restyling a live code area: every `setStyleSpans` of an editable code view goes through `CodeArea.restyleKeepingView`
  (file-viewers `code`); a plain call drops the cells of the shown lines and the view jumps to the top or the caret line
  to the top (RichTextFX/Flowless).

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
- The About dialog (`aboutDialog`) uses the app title as its header text and a Copy button that keeps it open.
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

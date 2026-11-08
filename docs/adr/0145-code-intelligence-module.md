# 0145. Code intelligence in the editors: module `code-intelligence`, bindings, banners and popups

- Status: accepted; amends [0142](0142-language-server-foundation.md) (`ServerInstaller.toolName`,
  `ServerAvailability.Failed.canRetry`), [0141](0141-editor-api-for-language-features.md) (`popupOwner`),
  [0108](0108-code-editor-extensions-and-context-menu.md) (`CodeEditorExtensions.extensions` public); see
  [0141](0141-editor-api-for-language-features.md), [0144](0144-python-via-ty.md),
  [0108](0108-code-editor-extensions-and-context-menu.md), [0070](0070-window-services.md),
  [0101](0101-shared-data-file.md), [0084](0084-testability-logic-out-of-controls.md),
  [0054](0054-keep-source-files-small.md)
- Date: 2026-11-08

## Context
The language-server client ([ADR 0142](0142-language-server-foundation.md)) and the editor API for language features
([ADR 0141](0141-editor-api-for-language-features.md)) are in place, and Python has its server spec and installer
([ADR 0144](0144-python-via-ty.md)). What connects them is still missing: something that opens an editor's file on its
server and keeps it in sync, tells the user when the server is missing, installing, stopped or waiting for the trust,
restarts servers when their executable changes, and gives the next features (navigation, completion, hover,
diagnostics, rename) one place to attach to and the popups they share. Three packages build on it in parallel, so the
attachment point must let each add its feature without editing the others' code.

## Decision
- **Module `code-intelligence`** (package `dev.sluide.codeintelligence`), in the editor family: it works inside the
  code editors. It depends on `file-viewers` and `language-servers` (`api`), `project-data` (`api`, for
  `InstallPrompts`) and `background-tasks`. Root package: `CodeIntelligence` and `InstallPrompts` (what `ide` uses);
  `binding` (the binding, the feature hook, LSP conversions), `server` (banners, restart item), `ui` (popups, hover).
  Its one Spring bean is the singleton `InstallPrompts`; the rest are plain classes `ide` creates per window.
- **Extension:** `CodeIntelligence(languageServers, prompts)`, one per window, is a `CodeEditorExtension`
  ([ADR 0108](0108-code-editor-extensions-and-context-menu.md)). It attaches to every code editor and binds it while
  `LanguageServers.specFor(file)` names a spec (inside the project folder, matching file name). `editor.file` is
  followed: a rename to or from `.py` binds or unbinds, a move within the language keeps the binding and sends
  `SyncedDocument.moved(to)`, a move out of the project unbinds. The extension's handle unbinds when the editor is
  disposed. Each editor has one context menu provider for its life, asking the current binding for items: the editor's
  menu providers cannot be removed, and a file renamed back and forth binds again.
- **Binding:** `DocumentBinding` opens the file (`documents.open(file, languageId, editor.text)`), sends every
  `TextChange` as a `DocumentChange` (with `editor.text` for the resync fallback), `onSaved` as `saved()`, and closes
  the document when it ends; it also calls `ensureStarted()`, so a new editor retries a server that was missing or
  untrusted. It exposes the editor, the spec, the window's `LanguageServers`, the session, the `SyncedDocument`, hover
  providers, context menu items and handles that live as long as the binding, and conversions on the document's copy:
  `positionOf`/`offsetOf`, `rangeOf(start, end)`, `offsetsOf(Range)` (`start until end`), `editOf`/`editsOf` (LSP
  `TextEdit` → `OffsetEdit`), `positionParams(offset)`, `documentId`. `SourcePosition` and `toSourcePosition()` /
  `toSourcePositions()` turn `Location`s and `LocationLink`s (the link's selection range) into a file, line and column
  for `FileViewHost.openFile`, dropping URIs that are no local file.
- **Feature hook:** `fun interface BindingFeature { fun attach(binding: DocumentBinding): AutoCloseable? }`. The list is
  `builtInFeatures` in `CodeIntelligence.kt`: the server banner, the restart item, the hover popup; each later feature
  adds one line. Features attach in that order after the document is open (so hover providers are asked in that
  order) and their handles close in reverse when the binding ends; one that throws is logged and left out.
- **Banners** (`ServerBannerModel`, `noticeFor`, `ServerBanner`), one per editor at a time, styled as the code view's
  banners (`editor-banner`). While the installer is not ready its notice wins: Missing ("Code intelligence for Python
  needs ty (14 MB download)." with Install, Not Now, Don't Ask Again), Installing (progress bar, Cancel), Failed ("ty
  could not be installed: …" with Retry, Not Now; or, when `ServerAvailability.Failed.canRetry` is false because only a
  setting can help, such as an unusable custom path or no build for the platform, the message and "Change it in
  Settings → Languages → Python." with Not Now only). Then the server's state: `Failed` ("The Python language server
  stopped: …" with Restart), `Untrusted` ("… waits for this project to be trusted.", no button). Nothing for a server
  without a command and without an installer. Install passes the window's `BackgroundTasks` (window service). Not Now
  is a set of spec ids of the window, gone with a restart; Don't Ask Again is `InstallPrompts`: an observable set read
  on first use and written off the FX thread to `shared.json` as `{"codeIntelligence": {"installDeclined": ["python"]}}`
  ([ADR 0101](0101-shared-data-file.md)), so every window's banners follow it. Both put off only the installer's
  notices; Settings → Languages → Python still installs. The tool's name comes from `ServerInstaller.toolName` (default
  `null`: "its language server"), added to the installer API of ADR 0142; ty's installer says `ty`.
- **Restarts:** `CodeIntelligence` follows each installer: `onCommandChanged` restarts a server that has been started
  (Starting, Running, Restarting, Failed), not one that is stopped, missing or untrusted; availability turning `Ready`
  calls `ensureStarted()` if an editor is bound to that spec. "Restart Python Language Server" in a bound editor's
  context menu restarts it, disabled with the reason (not started, not installed, project not trusted).
- **Popups** (`ui`, internal to the module): `CaretPopup<T>` is a list in a `javafx.stage.Popup` at an anchor range,
  below it and above when it does not fit the screen (`popupPosition`), with a cell (label, secondary text, icon),
  an optional detail pane beside the list, a placeholder ("Searching…", "No results") and items that may change while
  it shows (the selected item stays selected if still there, else the first; `PopupListModel`). The focus stays in the
  editor: Up/Down (wrapping), PageUp/PageDown, Home/End, Enter, Tab (optional) and Escape are editor key bindings
  taken while it shows, a click selects and a double-click takes. `HoverPopup` stacks the sections of the binding's
  `HoverProvider`s (`fun hover(offset, done: (List<Node>?) -> Unit): AutoCloseable`, asynchronous, shown as they
  arrive in the providers' order) after the mouse rests on a character for 500 ms (`HoverModel`), and at the caret on
  F1; another character (except on the way to the popup), typing, scrolling, a click and Escape hide it. Both hide on a
  click outside, when the editor loses the focus or scrolls (a wheel turn, another first line shown), and when the
  window moves, is minimized or loses the focus; an edit of a shown line places them again at their anchor
  (`EditorPopupWindow`). Their stylesheet is added to the popup's content, as popups do not inherit the editor's; it
  uses only looked-up colours.
- **Popup owner:** `ExtensibleCodeEditor.popupOwner` (added to the API of ADR 0141, default `null`) is the text area
  while it is in a scene; `EditorPopupWindow` shows popups in its window and follows its focus and scene. A popup does
  not hide on Escape by itself (`hideOnEscape` off): JavaFX would hide it and pass the key on to the editor, closing an
  open find bar as well; the popups' Escape bindings take the key instead. Nothing in a popup is focus traversable, so
  a button or link in a hover or detail section never takes the focus, Enter or Space.
- **Window close:** after `close()`, `CodeIntelligence.extend` extends no editor any more.
- **`ide` wiring:** `CodeIntelligenceWiring` (`ide/.../codeintelligence`, a window bean) creates the window's
  `LanguageServers` (every `LanguageServerSpec` bean, the project folder of `ProjectService`, the trust gate
  `ProjectTrust.awaitAllows`, the `FileWatcher`), registers it as a window service
  ([ADR 0070](0070-window-services.md)), gives `MainWindow` the extension for the `CodeEditorExtensions` block, and is
  closed by Spring when the window's scope is destroyed, after `MainWindow.dispose` closed the tabs (so the documents
  close before the servers stop). `MainWindow` grows by five lines.
- **Threading:** everything here runs on the FX thread and never waits: requests are asynchronous, results come back
  on the FX thread. The logic is in plain classes and functions (`noticeFor`, `ServerBannerModel`, `HoverModel`,
  `HoverQuery`, `PopupListModel`, `popupPosition`, `restartBlocker`), tested without a toolkit
  ([ADR 0084](0084-testability-logic-out-of-controls.md)).

Rejected:
- **One feature switch in the extension:** every package would edit the same `when`; a list of `BindingFeature`s costs
  one line each.
- **Restarts in `ide`:** the rule "only servers that were started" is logic and belongs where it can be unit-tested.
- **The popup owner from the text area's events** (the source of the events the editor's filters see): needs an event
  before the first popup, and a node the API does not name; `popupOwner` names it.

## Consequences
- Python files get document sync, the banners and the restart item as soon as ty is installed; navigation,
  completion, hover content and diagnostics are features that attach to the binding (ADRs 0146–0148).
- Every code editor gets the extension's file listener and one context menu provider, also those of other languages.
- The API names one node, the text area, for popups only; extensions still add everything else through its members.
- Hover and caret popups do not follow the editor font; they use the UI font.
- Listed in [../index.md](../index.md).

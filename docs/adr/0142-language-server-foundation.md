# 0142. Language servers: a general LSP client in the foundation module `language-servers`

- Status: accepted; amended by [0151](0151-workspace-folders-per-project.md) (several workspace folders per session, the spec's
  workspace hooks), [0145](0145-code-intelligence-module.md) (`ServerInstaller.toolName`, `ServerAvailability.Failed.canRetry`),
  [0149](0149-rename-file-structure-go-to-symbol.md) (`workspaceSymbol/resolve` in `ServerFeatures.supports`,
  `LanguageServerSpec.isIdentifier`), [0160](0160-document-gate-and-kept-diagnostics.md) (a gate per document, lazy
  start for gated specs, `didClose` only to the connection that got the `didOpen`, kept diagnostics of unopened files)
- Date: 2026-11-07

## Context
Thoreau's code views know only lexical highlighting. Go to definition, usages, completion, diagnostics and rename need
real name resolution, which [code-navigation.md](../ideas/code-navigation.md) left open between a syntax-only index and
a language server (its option 3). The round "Python code intelligence" decided for language servers: Python comes
first and more languages follow, and the Language Server Protocol gives every one of them through one client.
Spikes of basedpyright, ty and Pyrefly (2026-11-07) chose ty for Python; its details (pin, install, what it runs)
belong to the Python ADR (0144). What the spikes showed about servers in general shapes this client:
- Servers send requests the client must answer: `workspace/configuration` (Pyrefly rejects `null` and resets missing
  keys), `client/registerCapability` (pull diagnostics and file watchers are registered dynamically, so a client that
  reads only the `initialize` answer misses them), `window/workDoneProgress/create` and the refresh requests
  `workspace/diagnostic/refresh`, `workspace/semanticTokens/refresh`, `workspace/inlayHint/refresh`.
- `$/progress` may never come; definitions come as `Location` even when links are declared; completion lists may
  always be `isIncomplete`.
- A server is a child process that lives as long as its window, and window beans are not destroyed on SIGTERM
  (`ProjectWindows`), so a server could outlive Thoreau.

## Decision
- **Module:** `modules/language-servers`, package `dev.sluide.languageservers`, a foundation library without Spring
  beans and without controls: JavaFX base for the observable states, `Platform.runLater` (graphics) as the default
  dispatch to the FX thread. Nothing in it is specific to a language. It depends on `background-tasks`
  (`NamedThreads`, and `BackgroundTasks` in the installer interface), on `file-watcher` (the project watch, below) and
  on lsp4j (Eclipse, latest release 1.0.0, with Gson, whose version the Spring Boot BOM manages inside lsp4j's range)
  as `api`: consumers use lsp4j's protocol types. Root package: the API; `connection` (lsp4j, lifecycle, client
  capabilities), `files` (the project watch, file watcher globs) and `process` (OS processes, the JVM hook) hold the
  rest ([ADR 0055](0055-sub-packages-within-modules.md)). Its test fixtures (`java-test-fixtures`) publish
  `FakeLanguageServers` and `FakeServer`: `LanguageServers` over lsp4j fake servers on in-memory pipes, so the modules
  that use language servers test their logic without a process.
- **Extension point `LanguageServerSpec`**, implemented by a language module: id and name, `matches(file)`,
  `languageId(file)`, `command()` (executable, arguments, environment changes, working folder; `null` = not available),
  `initializationOptions(root)`, `configuration(item)` (the answer per `workspace/configuration` item),
  `adjustCapabilities`, `runsProjectCode` with `trustCheckId`, and an optional `ServerInstaller`
  (availability `Ready`/`Missing`/`Installing`/`Failed`, `install(tasks)`, `cancel()`), defined here so that the
  module does not depend on `local-models`; the language module adapts its installer.
- **JSON from specs** (configuration answers, initialization options) is built without reflection: maps with string
  keys, lists, strings, numbers, booleans, `null` and Gson `JsonElement`s. Gson's reflection would turn other values
  (a Jackson node, a data class) into garbage without a word, so another type is logged and answered with `null`
  (initialization options: left out); so is a configuration answer that throws.
- **One server per window and spec** (`LanguageServers`, a plain class `ide` registers per window): one
  `LanguageServerSession` per spec, which outlives restarts (listeners stay, requests go to the server that runs).
  Only files inside the project folder have a server (`specFor`); a window without a folder has none. The server
  starts lazily: on `ensureStarted()` or with the first request or notification.
- **States:** `Stopped`, `Starting`, `Running`, `Restarting(attempt)`, `Failed(message)`, `Unavailable` (no command),
  `Untrusted` (the trust refuses), on the FX thread. `ensureStarted()` also retries `Unavailable` and `Untrusted`
  (installed or trusted since); a request or notification starts only a `Stopped` server, so keystrokes do not keep
  asking for a missing one.
- **Restarts:** a crash (process ended, could not be started, no `initialize` answer within 30 s) restarts after 1 s,
  4 s and 15 s; a crash after the third restart ends in `Failed`, which only `restart()` (the user's "Restart …
  Language Server", or an update) leaves. A server that ran for 3 minutes before a crash counts from the start
  again. Every start has a generation, so events of an earlier process are ignored (its end, its diagnostics and
  refreshes). An unexpected exception while starting (project folder, trust gate, spec, starter) ends in `Failed` at
  once. Requests still waiting for a server that stops (crash, restart) fail with
  `LanguageServerNotRunningException`: lsp4j leaves them incomplete when a connection ends, so each connection keeps
  its sent requests.
- **Close:** `close()` (window close) sends `shutdown`, waits up to 2 s for the answer, sends `exit` (also without
  an answer), and kills the process with its descendants if it has not ended 1 s later. The processes the server had
  started when the close began are killed in any case, since a server that exits by itself leaves them behind. One
  JVM shutdown hook (`LiveServerProcesses`, a registry of the live server processes of all windows) kills the
  remaining servers and their descendants when the JVM exits.
- **Trust ([ADR 0053](0053-project-trust.md)):** a server that runs code or tools the project configures
  (`runsProjectCode`) starts only once `TrustGate.awaitAllows(trustCheckId)` says yes, waited for off the FX thread
  and given up when the start is replaced; `ide` passes the window's `ProjectTrust::awaitAllows`. Each language's ADR
  lists what its server runs without a click and whether it needs a `ProjectTrustCheck`.
- **Processes:** `ProcessBuilder` with an argument list, never a shell; standard input and output are the LSP stream,
  standard error goes to the log at INFO, lines cut at 500 characters, at most 2,000 lines per process. Start, exit and
  initialization are logged with the process name (`lsp python#3 start …`, `… exit 0 after … ms`) as
  [ADR 0060](0060-logging-conventions.md) asks.
- **Client side:** declares the capabilities the round's features use (definition, declaration, type definition,
  references, highlights, completion without snippets with resolve, signature help, hover and documentation as
  Markdown or plain text, published and pulled diagnostics, code actions with literals and resolve, rename with
  prepare, hierarchical symbols, workspace symbols, full semantic tokens in the relative format with the standard
  types and modifiers, configuration, workspace folders, dynamically registered file watchers, work-done progress,
  UTF-16 positions as the editor's offsets, `didSave`). `rootUri` and one workspace folder are the project folder.
  It answers configuration from the spec, records registrations (`ServerFeatures.supports(method)` merges them with
  the `initialize` answer; `fileWatchers()`), answers progress creation and refreshes (and tells refresh listeners),
  logs `window/showMessage` (WARNING for errors and warnings) and `window/logMessage` (a level lower: errors at
  WARNING, warnings at INFO, log and info lines at DEBUG; at most 1,000 per connection), and refuses
  `workspace/applyEdit` for now.
- **Threading:** lsp4j reads on a thread per process. Everything sent to a server (the start too) runs in order on one
  outbound thread per session (`sluide-lsp-<id>`), so requests queued while a server starts reach it after
  `initialized`, and the FX thread never writes to a pipe. Results, listener calls and states reach the FX thread
  through `dispatch`; a listener that throws is logged and the others still run. `request` delivers the result or
  the error there; `PendingRequest.cancel()` drops the result and sends `$/cancelRequest` (if `call` returned the
  proxy's own future); `RequestSlot` cancels the previous request for keystroke-driven ones (latest wins); nothing
  is delivered after `close()`.
- **URIs:** `LspUris` sends what `Path.toUri()` makes of the absolute, normalized path (folders without a trailing
  slash) and compares URIs from servers after normalizing them (percent-encoding, `file:/`, `file://localhost/`).
- **Notifications belong to one connection:** every connection that reaches `Running` gets a number, and
  `onConnected` listeners run on the FX thread once the server is initialized, the moment from which notifications go
  to it, in the same FX step that sets the state to `Running` (an editor bound after seeing `Running` must not be
  re-synced and pulled a second time). `notify` (in order with requests on the outbound thread) reaches only the
  server announced when it was called; notifications made before that or for a server replaced since are dropped. So a document change is either
  in the state the `onConnected` re-sync sends (it happened before, on the FX thread) or sent as a notification
  afterwards, never both: a `didChange` applied twice would corrupt the server's copy.

### Document sync and file events
- **Documents:** `LanguageServerSession.documents` (`DocumentSync`, FX thread only) opens a file with
  `open(file, languageId, text)`: `didOpen` with version 1, and the server starts if it is stopped. Opens are counted
  per file: a second open returns another `SyncedDocument` handle to the same document (with its text), and `didClose`
  goes out when the last handle closes. One editor per file feeds the changes ([ADR 0134](0134-split-editor-area.md));
  other handles read positions and save. A handle does nothing once closed, so late editor events are harmless.
- **Changes:** a document keeps its own copy of the text, because the editor's text already shows a whole batch
  (Replace All, `applyEdits`) when it reports the batch's changes one by one, each in the text the one before left
  ([ADR 0141](0141-editor-api-for-language-features.md)). `changed(DocumentChange(start, removed, inserted),
  currentText)` computes the LSP range on the copy before applying it (lines from `\n`, which the editor normalizes to;
  characters in UTF-16 code units, the editor's offsets), applies it, bumps the version and sends one `didChange` per
  change (no coalescing): the range and the inserted text if the server syncs incrementally, the whole text if fully,
  nothing if it wants no changes. The sync kind is a dynamic registration of `textDocument/didChange` (its `syncKind`)
  if there is one, else the static `textDocumentSync` (`ServerFeatures.textDocumentSyncKind()`). The client still
  declares no dynamic registration for synchronization; reading one costs nothing.
- **A change that does not fit the copy** (its `removed` text is not at `start`: a bug) is logged once per document at
  WARNING, and the server gets `currentText()`, the editor's text, as a whole change with a new version. The changes of
  the same batch that follow are in that text already: while the editor's text equals the copy, they are skipped.
- **Saves and moves:** `saved()` sends `didSave` if the server wants it (a registration of `textDocument/didSave`, else
  the static `save` option; the old bare sync kind counts as without text, as VS Code reads it), with the text when it
  asks for `includeText`. `moved(to)` (a rename that keeps the editor) sends `didClose` for the old URI and `didOpen`
  for the new one with the current text and the language id given at open; the caller checks that the target is still
  in the project and served by the spec. A document still open at the target (a missed close: moves never replace,
  [ADR 0073](0073-explorer-multi-selection-and-moving.md)) is logged at WARNING and closed, and its handles do
  nothing any more, rather than the move failing on the FX thread. `positionOf(offset)` and `offsetOf(position)`
  convert on the copy (clamped) for the requests and results of the features.
- **Every new server gets the open documents again:** the session's `onConnected` sends `didOpen` for every open
  document with its current text and a new version. With the rule that a notification reaches only the server
  announced when it was made, a document opened or changed while a server starts is dropped there and arrives once,
  in that reopen, never twice. Requests made right after a start may reach the server before the reopen.
- **File events:** `LanguageServers.filesChanged(List<FileChange(path, kind)>)` (FX thread) sends
  `workspace/didChangeWatchedFiles` to every session whose server registered file watchers that want a change: a plain
  glob matches the absolute path, a relative pattern the path below its base URI (a URI or a workspace folder); `*`,
  `?`, `**`, `{a,b}`, `[a-z]` and `[!a-z]`; the watcher's kind bits (create, change, delete; all if left out) must
  include the change's kind. A server that does not run has no watchers, so this starts none. Callers feed what they
  know first (the explorer's moves and deletes, saves).
- **Project watch:** while any server of the window starts, runs or restarts, `LanguageServers` watches the project
  folder with the window's `FileWatcher`, one `watch` per folder ([ADR 0006](0006-file-watching.md) watches no
  trees), and feeds what it finds to `filesChanged`. It skips hidden folders (`.git`, `.venv`, `.gradle`, `.idea`),
  `node_modules`, `__pycache__` and `venv` anywhere, `build`, `target` and `dist` directly in the project folder (a
  Python package may be called `build`), and links to folders (no loops; only the project folder itself may be opened
  through a link). It watches at most 2,000 folders, 300 on macOS (below): past that it logs once and adds none. All
  of this is `ProjectWatchOptions`, a constructor parameter. Folders that appear are watched, with what they hold
  reported as created. `FileWatcher` reports paths without event kinds, so the folder that reported a path is listed
  again and compared with its last listing (existence, size, modification time) into created, changed and deleted; a
  deleted folder reports its known files as deleted. A folder that reports itself (deleted, or events lost) is
  watched again before its old watch is closed: `FileWatcher` keeps a live watch and renews one that ended with a
  deleted folder, and nothing on disk tells which (Linux reuses inode numbers, so `rm -rf gen && mkdir gen` may give
  the same file key). Each folder has its own listener, so a folder's report of itself is told apart from its
  parent's report of it as an entry. The listing runs on one thread (`sluide-lsp-files`); what one pass finds is one
  batch.
- **What ty registers (0.0.99, linux-aarch64, 2026-11-07):** `textDocumentSync` with `openClose`, `change`
  Incremental and `save` without text; dynamically one `workspace/didChangeWatchedFiles` registration with three
  relative patterns `**` without kinds (all): the project folder and the interpreter's two `dist-packages` folders
  (outside the project; the project watch does not cover them). So ty hears about every file below the project.

## Consequences
- A language is a spec plus, if Thoreau installs its server, an installer; the process, the protocol and the
  lifecycle are shared, and features in the editor work for every language a server supports.
- The app carries lsp4j and Gson (about 1 MB).
- A server that never answers `initialize` holds its session's outbound thread for 30 s; requests sent meanwhile wait.
- Server-initiated edits (`workspace/applyEdit`), dialogs (`window/showMessageRequest`, answered as dismissed) and
  `window/showDocument` are not supported yet.
- Every change copies part of the document's text and sends one `didChange`; a long Replace All sends many. Ranges
  are found by scanning the copy from its start, fine for source files, slow for huge ones.
- Changes in excluded or hidden folders, in folders past the cap and outside the project (ty's `dist-packages`
  watchers) reach no server; a restart (also after `pip install`) makes the server read them. On macOS the JDK polls
  (ADR 0006), so changes arrive up to about 2 s late. Watchers whose base is the project folder's real path where
  the project was opened through a link (macOS `/var` and `/private/var`) match nothing: the watch reports paths below
  the folder as opened, the form the servers got as root URI.
- On macOS the JDK's polling watch service lists every registered folder again about every 2 s: 300 folders are
  about 150 listings a second per window while a server runs (2,000 would be about 1,000), hence the lower cap there.
  On Linux each folder is one inotify watch; when `fs.inotify.max_user_watches` is used up, `FileWatcher` logs the
  failed registration only at DEBUG and that folder stays unwatched.
- In a JVM without a UTF-8 file-name encoding (no locale, as the sandbox's Gradle), paths with other characters
  cannot exist; the URI tests of those skip there and pass with `LC_ALL=C.UTF-8`.
- Listed in [../index.md](../index.md).

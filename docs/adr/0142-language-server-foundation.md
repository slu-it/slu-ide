# 0142. Language servers: a general LSP client in the foundation module `language-servers`

- Status: accepted
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
  dispatch to the FX thread. Nothing in it is specific to a language. It depends on
  `background-tasks` (`NamedThreads`, and `BackgroundTasks` in the installer interface) and on lsp4j (Eclipse, latest
  release 1.0.0, with Gson, whose version the Spring Boot BOM manages inside lsp4j's range) as `api`: consumers use
  lsp4j's protocol types. Root package: the API; `connection` (lsp4j, lifecycle, client capabilities) and `process`
  (OS processes, the JVM hook) hold the rest ([ADR 0055](0055-sub-packages-within-modules.md)). Its test fixtures
  (`java-test-fixtures`) publish `FakeLanguageServers` and `FakeServer`: `LanguageServers` over lsp4j fake servers on
  in-memory pipes, so the modules that use language servers test their logic without a process.
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
  to it. `notify` (in order with requests on the outbound thread) reaches only the server announced when it was
  called; notifications made before that or for a server replaced since are dropped. So a document change is either
  in the state the `onConnected` re-sync sends (it happened before, on the FX thread) or sent as a notification
  afterwards, never both: a `didChange` applied twice would corrupt the server's copy.
- **What P4 adds:** document sync (`didOpen`, incremental `didChange`, `didSave`, `didClose` with versions) and
  workspace file events (`didChangeWatchedFiles`), over the hooks this package leaves: `notify`, `onConnected` (where
  it opens the open files again with `notify`) and `ServerFeatures.fileWatchers()` with `onFeaturesChanged`. Requests
  made right after a start may reach the server before that re-sync.

## Consequences
- A language is a spec plus, if Thoreau installs its server, an installer; the process, the protocol and the
  lifecycle are shared, and features in the editor work for every language a server supports.
- The app carries lsp4j and Gson (about 1 MB).
- A server that never answers `initialize` holds its session's outbound thread for 30 s; requests sent meanwhile wait.
- Server-initiated edits (`workspace/applyEdit`), dialogs (`window/showMessageRequest`, answered as dismissed) and
  `window/showDocument` are not supported yet.
- In a JVM without a UTF-8 file-name encoding (no locale, as the sandbox's Gradle), paths with other characters
  cannot exist; the URI tests of those skip there and pass with `LC_ALL=C.UTF-8`.
- Listed in [../index.md](../index.md).

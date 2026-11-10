# 0160. Language servers: a gate per document, lazy start for gated specs, kept diagnostics of unopened files

- Status: accepted; amends [0142](0142-language-server-foundation.md) (documents a server never sees, start only once a
  document is served, `didClose` only to the connection that got the `didOpen`, diagnostics of files no editor shows),
  [0151](0151-workspace-folders-per-project.md) (the gate is asked with the workspace folder, watched folders per
  answer, the status line per file)
- Date: 2026-11-10

## Context
The round "TypeScript/JavaScript code intelligence through the TypeScript 7 server" serves TypeScript 6 and 7 projects
and explicitly not TypeScript 5 and lower. The spikes with the TypeScript 7 native server (7.0.2, 2026-11-10) showed:
- One server process with the window folder as its only workspace folder isolates the projects of a monorepo by their
  nearest `tsconfig.json`, so there is no workspace folder per project (ADR 0151's hook stays `null`).
- The server always uses its own 7.0 compiler; there is no compatibility mode. A TypeScript 5 project, one whose
  version cannot be decided (`"typescript": "latest"`) and a Deno project would get wrong diagnostics. Their files
  must not reach the server at all: the decision is per document, and reads files (`package.json`,
  `node_modules/typescript/package.json`), so it cannot run on the FX thread.
- A `textDocument/didClose` for a document the server never got a `didOpen` for crashes it (Go panic "overlay not
  found for closed file", exit 2). A second `didClose` after one `didOpen`, a `didChange` of an unopened document and a
  second `didOpen` are harmless.
- Options TypeScript 6 deprecated are hard errors in 7; they come as `publishDiagnostics` for `tsconfig.json`, a file
  that is usually not open. ADR 0142's session dropped pushes for files without a listener.
- After `npm install` adds a package to an existing `node_modules` (which the project watch excludes), the server keeps
  "Cannot find module"; reopening the document does not help, a `workspace/didChangeWatchedFiles` for the new package
  folder or its `package.json` does (the server registers one watcher, `<root>/**/*`).

## Decision
1. **Gate per document.** A spec with `LanguageServerSpec.gatesDocuments` answers `gate(file, projectFolder)` with a
   `DocumentGate(served, watched, reason)`. It is asked on the session's folder worker together with `workspaceFolder`
   (ADR 0151), in the same answer, when a document opens or moves, when `affectsWorkspaceFolders` names a change above
   it, and when a folder its last answer watches changed. A changed answer (served flag, reason or watched folders)
   counts as a workspace change. Specs that do not gate (ty) keep every default: always served, no extra call, nothing
   changes for them.
2. **Not served means never sent.** A document whose answer is not served gets no `didOpen`, `didChange`, `didSave` or
   `didClose`, also not on a reconnect, restart, move, refresh or re-resolution. Before the answer it counts as not
   served: for a gating spec the `didOpen` is queued on the FX thread once the answer said served (the outbound thread
   does not wait for it). A flip from served to not served sends `didClose`, the other way `didOpen` with the current
   text and the next version (the first `didOpen` a document ever gets carries the version it has then).
   `LanguageServerSession.gateOf(file)` is the answer (FX thread; `null` before it and for files not open;
   `DocumentGate.SERVED` from the open on for specs that do not gate).
3. **`didClose` only to the connection that got the `didOpen`.** Each document records, on the outbound thread, the
   connection that got its `didOpen` and no `didClose` since; a `didClose` for any other connection (a new server, a
   document never opened there, one closed while its gate answer was pending) is dropped there, whatever the FX thread
   believed. `didChange` and `didSave` are checked the same way. A `didClose` starts no server.
4. **Fail closed.** For a gating spec an answer that throws is not served; one that takes longer than
   `ServerTiming.workspaceFolderWait` (10 s) is not served (`gateOf` says so) until it comes, and then applies like a
   flip. Specs that do not gate keep ADR 0151's fallback to the window folder.
5. **Watched folders.** `DocumentGate.watched` names folders the project watch does not cover (excluded ones such as
   `node_modules`, folders outside the window folder) whose entries decide the answer. The session watches each one,
   not recursively, while some open document's answer names it (folders may not exist yet: they report nothing until a
   re-resolution watches them again). A change there
   - asks the gate of only the documents whose answer named that folder again, after `workspaceDebounce` (1 s);
   - reaches the server as file events (`workspace/didChangeWatchedFiles`: created, changed or deleted against the
     folder's entries as last listed), filtered by the server's registered watchers like the project watch's changes.
   The watches end when no open document names the folder any more and when the session closes. A folder that went
   and came back is watched again with the next re-resolution, and what changed in it meanwhile is reported then. Such a
   spec should also name the folders whose appearing matters, since the project watch runs only while a server starts
   or runs.
6. **Lazy start.** A gating spec's server starts only once a document is served: the session starts it itself then
   (as `ensureStarted` would, also after Unavailable). While no open document is served, nothing else starts it:
   `ensureStarted` does nothing, nor does `restart` ("Restart … Language Server") for a server not started yet
   (stopped, unavailable, untrusted), a request gets not running (`LanguageServerNotRunningException`) as for any
   server that does not run, and notifications are dropped. A window whose documents are all not served never starts
   the process. A server that was started (running, starting, restarting, failed) restarts as before, also once its
   last served document closed, so that an update of the server replaces the running one.
7. **Kept diagnostics.** A `publishDiagnostics` for a file no editor listens to (`onDiagnostics`) whose path
   `LanguageServerSpec.keepsDiagnostics` accepts is kept, the latest per normalized URI (an empty list removes it):
   `LanguageServerSession.keptDiagnostics(file)`, with `keptDiagnosticsChanges` on the FX thread. They are cleared when
   the server stops, restarts or crashes and when the session closes. Pushes for files an editor listens to are
   unchanged.
8. **Declared for the editor, not called by the session:** `LanguageServerSpec.configFiles(file, projectFolder)` (the
   config files whose kept diagnostics concern a file: its project's nearest config, since the TypeScript server
   reports an option an `extends` base sets on the project's own `tsconfig.json`) and
   `workspaceStatus(file, folder, projectFolder)` (a status line per file; by default the folder's line of ADR 0151).

## Consequences
- TypeScript 5, undecidable and Deno projects in a monorepo never reach the TypeScript 7 server, and a window with only
  those never starts it; a `package.json` edit or an install that makes a project served opens its documents without a
  restart.
- Every document notification of the session is decided on the FX thread and checked once more per connection on the
  outbound thread, so a server never gets a `didClose` for a document it did not open, also across restarts and races
  with pending answers.
- An unanswered gate keeps its document away from the server: a spec that hangs means no code intelligence for that
  file, never a wrong one. A gate answer costs a few small reads on the folder worker per open, move and change.
- A watched folder costs one non-recursive watch and one listing of its entry names (a `node_modules` with many
  packages lists once per watch).
- Listed in [../index.md](../index.md).

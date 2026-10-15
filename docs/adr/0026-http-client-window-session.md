# 0026. HTTP client: a session per window and a public editable code view

- Status: accepted
- Date: 2026-10-15

## Context
Running requests needs state that outlives a view but must not leak between projects: cookies, OAuth2 tokens, client
certificate passphrases, HTTP clients and the environment chosen per file. File viewers are singletons shared by all
windows ([ADR 0014](0014-project-windows.md)), so they cannot hold it, and a viewer cannot depend on window-scoped
beans. The view of a `.http` file also needs the code view of `file-viewers` (editing, saving, change markers, run
icons in the gutter), whose implementation is internal to that module.

## Decision
- **`HttpClientSession`** is a `@Scope("window")` bean in `http-client` (`AutoCloseable`, closed with the window). It
  owns the `HttpClients` with their in-memory cookie jar, the `RequestExecutor` (virtual threads), the
  `OAuth2TokenCache`, the `SslContexts` (replaced contexts stop their clients) and the `RequestRunner`, remembers the
  environment chosen per `.http` file, asks for passphrases ([ADR 0028](0028-http-client-secrets.md)) and gets from the
  window whether git would commit a file (`wouldCommit`).
- **Views find it through their host:** `FileViewHost.windowService(type)` (default `null`) answers with the first of
  `EditorArea.windowServices` that is an instance of `type`; `MainWindow` sets `windowServices = listOf(httpSession)`.
  The mechanism is generic, so other per-window services need no new host method. Without a session (a view shown
  elsewhere, tests of other modules) the view cannot run requests: Run is disabled with a tooltip.
- **Off the FX thread** (`HttpClientSession.inBackground`, results back through `dispatch`): reading the environment
  files (the environment list shows the previous one until they are read; a run reads them again on its worker),
  acquiring tokens, and decoding and formatting responses (`ResponseContent`); the FX thread only sets texts and styles.
  Highlighting of a response body (up to 1 MiB) still happens when it is shown.
- **Git:** `MainWindow` calls `HttpClientSession.gitStatusChanged()` whenever the window's git status changes, so views
  ask `wouldCommit` again for their private environment file.
- **Renames:** the view follows its file (`FileView.moved`): the editor reads and saves the new path, requests run from
  there (body and environment files), and the environment chosen for the file moves to the new path in the session.
- **Public code view API in `file-viewers`**, and only this: `CodeEditors.open(file, highlighter, lineComment,
  blockComment)` gives a
  `CodeEditor` (an `EditableFileView` and `GutterColumns` with `text`, `textProperty`, `caretLine`, `moveCaretTo`);
  `CodeEditors.display()` gives a read-only, highlighted `CodeDisplay` (response bodies). `CodeView` stays internal;
  the editor wraps it by delegation, so the module that owns editing keeps changing it freely. Comment syntax is given
  as plain strings (`.http` files comment with `#`), so `text-editing` stays an `implementation` dependency of
  `file-viewers` and its `CommentSyntax` does not become part of the API. Viewers of other modules
  that show files as text implement `SourceFileViewer` (`sourceHighlighter`), so `FileViewers.showsDiffs` and
  `openDiff` treat them like code viewers: change markers and highlighted diffs work for `.http` files.
- Also public for other modules: `SyntaxHighlighter`, `HighlightSpan`, `TokenStyle`, `Highlighters.forLanguage` and
  `isTextFile` ([ADR 0005](0005-syntax-highlighting.md) still holds: `org.fife` stays internal).

## Consequences
- Closing a window forgets its cookies, tokens and passphrases and aborts its requests; two windows never share them.
- The editor area and the window know nothing about HTTP besides putting one object into a list.
- New public types in `file-viewers` are an API to keep stable for `http-client`.
- Added to [../index.md](../index.md).

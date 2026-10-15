# 0025. HTTP client: module, file format subset and variables

- Status: accepted
- Date: 2026-10-15

## Context
Thoreau should run HTTP requests written in files, on the model of IntelliJ's HTTP client: `.http` files, environments
from `http-client.env.json` with secrets in `http-client.private.env.json`, variables and auth. The format is large
(WebSocket, GraphQL, gRPC, JavaScript handlers, imports, cURL, …), and IntelliJ's documentation was not reachable while
this was built, so some details had to be decided without checking them against IntelliJ.

## Decision
- **Module `modules/http-client`** (`dev.sluide.httpclient`) with packages `parse`, `env`, `vars`, `auth`, `send` and
  `ui`, plus `HttpFileViewer` and the window's `HttpClientSession` ([ADR 0026](0026-http-client-window-session.md)). It
  depends on `file-viewers` (`api`) for the editable code view and the highlighters, and uses Jackson 3 and the JDK's
  `java.net.http`; no new library. Everything except the viewer, the session and the view is plain Kotlin, tested
  without a toolkit.
- **Format subset** (`HttpFileParser`, a line state machine over the current, possibly unsaved text):
  - requests separated by `###` lines (the text after `###` names the request), comments `#` and `//`;
  - before the request line: tags `# @name`, `@no-redirect`, `@no-cookie-jar`, `@timeout`, `@connection-timeout`
    (durations `30`, `500 ms`, `30 s`, `2 m`); other `@` tags are comments;
  - request line `METHOD URL [HTTP/1.1|HTTP/2]` (method defaults to GET), indented continuation lines of the URL,
    headers with indented continuation lines, a blank line, the body;
  - body text, `< path` file bodies (also VS Code's `<@ path` and `<@encoding path`; the encoding is ignored),
    url-encoded forms (line breaks around `&` dropped), multipart bodies split on the declared boundary;
  - `@name = value` variables outside requests;
  - `< {% … %}` and `> {% … %}` scripts and `> file.js` handlers are parsed and skipped with a note ("Scripts are not
    supported"); `<>` previous-response lines are ignored; `>>` (save response) is reported as unsupported.
  - Not supported: WebSocket, GraphQL and gRPC requests (reported), `import`/`run`, response history, running all
    requests, cURL conversion, `$shared` environments and `.env` files.
- **Highlighting** (`HttpSyntaxHighlighter`) follows the parser's `lineKinds`, so what looks like a header is one.
- **Variables** (`Template`, `VariableResolver`): `{{name}}`, `{{obj.key}}` (a path into environment objects) and calls
  `{{$random.integer(1, 10)}}`. A name is looked up in this order: dynamic `$` variables and `$auth.token("id")` /
  `$auth.idToken("id")`; the environment (private values win over public ones, deep-merged); the in-file `@variables`
  (the last definition wins). Environment and in-file values may refer to variables themselves; cycles count as
  unresolved. Dynamic variables: `$uuid`, `$random.uuid`, `$timestamp`, `$isoTimestamp`, `$randomInt`,
  `$random.integer/float/alphabetic/alphanumeric/hexadecimal`, `$random.email`, `$env.NAME`.
- **Unresolved variables** in the URL, the headers or body text stop the run before anything is sent, with one error
  naming all of them. In **body files** (text files only) variables are replaced where they have a value and the
  others are sent as written, with a note, since files may contain braces of their own. Binary files and multipart
  file parts are sent unchanged.
- **Environment files** are found independently, each the nearest one from the `.http` file's folder up to the project
  folder, and read again on every run and whenever the environment list opens.

Unverified IntelliJ behaviours, decided here and to be checked against IntelliJ or `ijhttp`:
- whether in-file variables or environment variables win (here: the environment);
- whether `< file` (not only `<@ file`) gets its variables replaced (here: both do);
- `Authorization: Basic user:password` (here encoded like `Basic user password`);
- how form values are encoded (here: sent as written, only the line breaks around `&` are removed);
- what `Use ID Token` does exactly (here: `$auth.token` then gives the ID token).

## Consequences
- Requests run from unsaved text, so what is shown is what is sent.
- Every further syntax element needs a parser line kind, highlighting and request building; the subset is listed in
  [features/http-client.md](../features/http-client.md).
- Added to [../index.md](../index.md).

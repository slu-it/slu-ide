# 0116. cURL and `.http` requests: a requests family from the HTTP client, Copy as cURL, Paste cURL as Request

- Status: accepted; see [0115](0115-converting-tool.md) (format families), [0025](0025-http-client.md) (format,
  variables), [0026](0026-http-client-window-session.md) (view, session), [0027](0027-http-client-sending.md)
  (sending), [0028](0028-http-client-secrets.md) (secrets), [0108](0108-code-editor-extensions-and-context-menu.md)
  (context menu)
- Date: 2026-10-30

## Context
API docs, bug reports and browsers' "Copy as cURL" hand out `curl` commands; the HTTP client runs `.http` requests
(ADR 0025). Turning one into the other by hand is tedious and error-prone: quoting differs between shells, curl implies
headers and a method (`-d` means POST with a form content type), and the HTTP client implies others (it follows
redirects, curl does not). The other way round, a request should be shareable as a command that runs as it is, without
leaking the secrets of the private environment file (ADR 0028).

## Decision
- **A requests family contributed through the Converting tool's extension point** (ADR 0115): `http-client` depends
  on `converting` (both in the editor family) and declares the `@Component` `RequestFamily` ("Requests") with the
  formats cURL (`curl`, highlighted as bash, `.sh`) and HTTP request (`http`, `.http`/`.rest`). Detection: text that
  starts (after blank lines, comment lines and a `$ ` prompt) with `curl` or `curl.exe` scores 100; text that the
  `.http` parser reads without problems and whose first request line is `METHOD url` or an `http(s)://` URL scores 90.
  The only option is the output **Shell** of cURL: POSIX (default), cmd or PowerShell.
- **A neutral request model** (`CurlRequest`) between the two: method, URL, headers as sent, body (none; data pieces
  joined with `&` as curl joins several `-d`, each text or a file; multipart fields with a value, a file upload or a
  file's text), HTTP version, follow redirects, timeouts, insecure, Basic/Digest credentials and a name.
- **Reading commands is shell-aware**: POSIX quotes, `$'…'`, `\` escapes and continuations; cmd's `^` escapes and
  continuations followed by the C runtime's argument splitting (`"`, `\"`, `""`); PowerShell's quotes and backtick
  escapes and continuations, `curl.exe`. Each line is a command; comment lines before a command name the request;
  what follows a shell operator (`| jq .`) is left out with a note. An unclosed quote, an option without its value and
  a command without URL fail at their offset.
- **The cURL mapping**: `-X`, `-H` (`Name;` is an empty header), `-d`/`--data-ascii`/`--data-binary`/`--data-raw`
  (`@file` becomes `< ./file`), `--data-urlencode`, `--json` (POST with JSON content type and accept),
  `-F`/`--form-string` (multipart in the client's layout with the boundary `WebAppBoundary`), `-u` (+ `--digest`) as
  `Authorization: Basic user password` (`Digest`), `--oauth2-bearer`, `-b` as a `Cookie` header, `-A`, `-e`, `-G`,
  `-I`, `-T` (PUT with a body file), `-L`, `-k`, `--url`, `--http1.1`/`--http2`, `-m` and `--connect-timeout` (decimal
  seconds, at most 10⁹). The method defaults as curl's does. Headers curl implies are written out
  (`Content-Type: application/x-www-form-urlencoded` for data), and left out again when writing cURL. Output options
  (`-s -S -o -O -v -i -f -w -#`, `--compressed`) are dropped silently, others are named in a note.
- **Its losses**: curl without `-L` becomes `# @no-redirect`, as the client follows redirects; `-k` has no `.http`
  equivalent (TLS is configured per environment) and is noted; `-d @file` drops the file's line breaks, `.http` sends
  it as it is (noted); in-file `@variables`, scripts and response references have no place in a command and are noted;
  a multipart body that is no list of fields is sent with `-d` as its raw text; a non-form body of several parts is
  joined with `&` by curl, with line breaks by the client (noted); `--data-urlencode name@file` sends the file without
  `name=` (noted).
- **Body lines that are `.http` syntax fail**: a body line (also of a multipart field) that a `.http` file would read
  as syntax, `< file`, `<>`, `###`, `> {% … %}` or `>> file`, makes writing the `.http` request fail with the line, so
  a pasted command cannot turn data into a file include (`--data-raw '< ~/.ssh/id_rsa'`) or a script.
- **Writing cURL** quotes minimally (also words starting with `=`, `~` or `#` in POSIX shells, and PowerShell's
  typographic single quotes ‘ ’ ‚ ‛, which end a quote there too), one option per line, `-L` unless `@no-redirect`,
  explicit headers instead of `--json`, `--data-binary @file` for files, `-F` for multipart fields and `--form-string`
  for values `-F` would read as a file or attributes (`@`, `<`, `;`; a field type is then lost, noted). Windows
  PowerShell 5.1 drops double quotes in arguments of native programs; that is noted. The shell of a command is told
  by its line continuations, else by `^"` outside single quotes (cmd) or `curl.exe` (PowerShell), else POSIX.
- **Variables**: the tool keeps `{{variables}}` as written in both directions. **Copy as cURL** in the `.http`
  editor's context menu resolves the request at the clicked line against the environment chosen for the file, the
  in-file variables and the dynamic variables (`{{$uuid}}`), reading the environment files in the background as runs
  do; `{{$auth.token(…)}}` and `{{$auth.idToken(…)}}` stay as written (a token is the window's), and so do variables
  without a value (named in the message, with what the command leaves out, e.g. a response handler). Body files get
  absolute paths, so the command runs from any folder; `-k` is added when the environment's `SSLConfiguration` turns
  off `verifyHostCertificate`, as the request does when run. The command is always POSIX. Failures are shown as
  messages in the toolbar, never thrown.
- **Private values**: a variable whose path exists in the chosen environment of `http-client.private.env.json` is
  private, also when reached through another variable. If the request uses any, Copy as cURL asks "The request uses
  private values: … Include them in the copied command?" with Include, Keep as {{…}} and Cancel, Cancel being the
  default.
- **Paste cURL as Request**, enabled when the clipboard holds a command, inserts the converted requests after the
  request of the clicked line (or at the line, in a part without a request), separated by `###` lines, as one undoable
  edit.
- **Hook**: `HttpFileView` owns its `CodeEditor`, so `CodeEditor` got `addContextMenuItems`, the call that window-wide
  `CodeEditorExtension`s already have on `ExtensibleCodeEditor`. The items stay in the `.http` view, with no wiring in
  `ide` and no window service. No key bindings.

## Consequences
- curl options without a counterpart (proxies, client certificates, cookie jars, retries) do not survive; TLS settings
  belong to the environment's `SSLConfiguration` (ADR 0028).
- Round trips `.http` → cURL → `.http` keep requests, names, tags, multipart fields and body files; `./` is added to
  relative body file paths on the way to `.http`.
- The Converting tool shows `.http` text plain until it can highlight the `http` language.
- Listed in [../index.md](../index.md).

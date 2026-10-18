# HTTP Client

**Module:** `modules/http-client` (package `dev.sluide.httpclient`). Decisions: [ADR 0025](../adr/0025-http-client.md)
(format, variables), [ADR 0026](../adr/0026-http-client-window-session.md) (window session, view),
[ADR 0027](../adr/0027-http-client-sending.md) (sending), [ADR 0028](../adr/0028-http-client-secrets.md) (secrets),
[ADR 0056](../adr/0056-http-client-setup-from-the-explorer.md) (setup).

Requests are written in `.http` (or `.rest`) files, in the format of IntelliJ's HTTP client, and run from the editor.

## Setting up a folder

**New HTTP Client Setup** in the file explorer's context menu (on a folder, on the empty area for the opened folder, or
on a file for its folder) creates `http-client.env.json` with an environment `local` with `host` =
`https://example.com`, an empty `local` in `http-client.private.env.json`, a `.gitignore` line for the private file and
`example.http` with `GET {{host}}`, and opens `example.http`. Files that exist are left as they are and a note names
them; an existing `.gitignore` gets the line unless it lists the private file already.

## Writing requests
```http
@base = {{host}}/v1

### Get users
# @name getUsers
# @timeout 10 s
GET {{base}}/users?limit=10
Accept: application/json

### Create a user
POST {{base}}/users
Content-Type: application/json
Authorization: Bearer {{$auth.token("api")}}

{
  "name": "Ada",
  "id": "{{$uuid}}"
}

### Upload
PUT {{base}}/avatar
Content-Type: image/png

< ./avatar.png
```
- `###` separates requests; the text after it names the request (so does `# @name`). `#` and `//` start comments.
- The request line is `METHOD URL` with an optional `HTTP/1.1` or `HTTP/2`; without a method it is a GET. Long URLs
  continue on indented lines (`    &page=2`). Headers follow, then a blank line and the body.
- Bodies: text, `< path` to send a file (relative to the `.http` file; `<@ path` works too), url-encoded forms (line
  breaks before `&` are removed) and `multipart/form-data` split on the boundary of the `Content-Type`.
- Tags before the request line: `# @no-redirect`, `# @no-cookie-jar`, `# @timeout 30 s` (whole exchange, default 60 s),
  `# @connection-timeout 5 s` (`ms`, `s`, `m`; a plain number is seconds).
- `Authorization: Basic user password` (or `user:password`) is encoded for you; `Authorization: Digest user password`
  answers the server's Digest challenge.
- Shortcut+/ (or Shortcut+Shift+7) comments lines out with `#` (and back in).
- Highlighting: methods and versions as keywords, URLs as strings, header names as properties, `{{variables}}`, tags and
  `@variables` as annotations, bodies by their `Content-Type` (JSON, XML, HTML), scripts as JavaScript.

## Variables and environments
- `{{name}}` is looked up in this order: dynamic variables, the chosen environment, then `@name = value` lines of the
  file. `{{obj.key}}` reads into objects of the environment.
- Dynamic variables: `{{$uuid}}`, `{{$timestamp}}`, `{{$isoTimestamp}}`, `{{$randomInt}}`,
  `{{$random.integer(1, 10)}}`, `{{$random.float()}}`, `{{$random.alphabetic(8)}}`, `{{$random.alphanumeric(8)}}`,
  `{{$random.hexadecimal(8)}}`, `{{$random.email}}`, `{{$env.HOME}}` (operating system environment).
- Environments come from `http-client.env.json` and, for secrets, `http-client.private.env.json`, each the nearest one
  from the file's folder up to the project folder. Private values win over public ones, key by key:
  ```json
  // http-client.env.json
  {
    "dev": {
      "host": "https://dev.example.com",
      "Security": {"Auth": {"api": {
        "Type": "OAuth2", "Grant Type": "Client Credentials",
        "Token URL": "{{host}}/oauth/token", "Client ID": "sluide", "Scope": "users"
      }}}
    },
    "local": {"host": "http://localhost:8080"}
  }
  // http-client.private.env.json
  {
    "dev": {
      "Security": {"Auth": {"api": {"Client Secret": "s3cret"}}},
      "SSLConfiguration": {"clientCertificate": "certs/client.pem", "clientCertificateKey": "certs/client.key",
                           "hasCertificatePassphrase": true, "verifyHostCertificate": true}
    }
  }
  ```
- Choose the environment in the view's toolbar ("No environment" first); the choice is remembered per file in the
  window, and follows the file when it is renamed. The files are read again (in the background) on each run and when
  the list opens. Problems in them, and a private file that git would commit (not ignored), are shown in a warning row
  above the editor; the git check needs the window's folder to be the repository root and is repeated whenever the
  git status changes.
- The environment files open beside a form of both files merged, one column per environment, with a lock per value
  for the file it goes in: [http-client-env-editor.md](http-client-env-editor.md).
- An unresolved variable stops the run before anything is sent, naming all missing variables. In body files, missing
  variables are sent as written, with a note.

## Auth
- OAuth2 Client Credentials and Password grants from `Security.Auth.<id>`, used with `{{$auth.token("id")}}` (or
  `{{$auth.idToken("id")}}`). Tokens are acquired when first needed (unless `"Acquire Automatically": false`), kept in
  memory for the window, refreshed shortly before they expire. The Auth menu acquires or clears the token of each
  configuration of the chosen environment, or clears all; the result shows next to the toolbar buttons.
- Client certificates (`SSLConfiguration`, private file only): PEM, PKCS#12 or DER; a passphrase is asked for once per
  window. `"verifyHostCertificate": false` trusts any server certificate for that environment.

## Running and the response
- Run the request at the caret with **Run** or Shortcut+Enter in the text (Ctrl+Enter, Cmd+Enter on macOS), or any
  request with the run icon beside its request line. Unsaved changes are sent as they are. **Cancel** stops a running
  request; running again replaces the running one. A caret in a `###` part without a request (only `@variables` or
  comments) runs nothing and says so.
- The response pane opens below the editor on the first run: the status (green 2xx, blue 3xx, orange 4xx, red 5xx and
  failures), time, size, HTTP version and final URL; notes (dropped headers, skipped scripts, token requests, Digest
  answers, truncation); and the tabs **Body** (Pretty or Raw; JSON and XML pretty-printed up to 1 MiB, highlighted by
  `Content-Type`; binary bodies as a summary), **Headers** and **Request** (as sent; credentials of `Authorization`,
  cookie values, headers like `X-Api-Key` or `X-Auth-Token`, and OAuth2 tokens anywhere in it are masked). Long bodies
  are shown in part, with a note.
  Failures (unresolved variables, refused connections, timeouts) take the place of the body.
- Cookies are kept per window (Clear Cookies forgets them); redirects are followed unless `@no-redirect`.

## Not supported
JavaScript handlers and pre-request scripts (`> {% … %}`, `< {% … %}`; skipped with a note), `>>` saving responses,
`import`/`run`, response history, running all requests, WebSocket, GraphQL and gRPC requests, cURL conversion,
`$shared` environments, `.env` files, Authorization Code/Device/Implicit grants, Kerberos/NTLM and AWS auth, proxy
settings of our own (the JDK's system properties apply).

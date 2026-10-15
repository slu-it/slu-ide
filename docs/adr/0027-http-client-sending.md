# 0027. HTTP client: sending requests with the JDK client

- Status: accepted
- Date: 2026-10-15

## Context
Requests from `.http` files need redirects, timeouts, cookies, compression, Basic and Digest auth, HTTP/2, client
certificates and cancelling, without blocking the JavaFX thread. A library such as OkHttp or Apache HttpClient would
add dependencies; the JDK's `java.net.http.HttpClient` covers most of it but has rules of its own (restricted headers).

## Decision
- **`java.net.http.HttpClient`**, one client per combination of redirect policy, cookie jar on/off, connect timeout and
  SSL context (`HttpClients`), cached for the window and stopped with it.
- **Threads:** `RequestRunner.run` returns at once. Resolving variables, token requests, reading body files, building
  and sending run on virtual threads (`RequestExecutor`); exactly one result per run comes back through `dispatch`
  (`Platform.runLater`). Cancel interrupts the preparation or cancels the exchange. A view shows only its latest run;
  a new run, also one that finds no request at the caret, replaces the running one. The environment files are read on
  the worker too, and the response is decoded and formatted off the FX thread before it is shown.
- **Headers:** written headers are sent as written, except those the JDK client sets itself and refuses
  (`Content-Length`, `Connection`, `Expect`, `Upgrade`, `Transfer-Encoding`, `TE`, `Trailer`, `Keep-Alive`,
  `Proxy-Connection`), which are dropped with a note. `Host` is allowed: the launcher sets
  `jdk.httpclient.allowRestrictedHeaders=host` first thing, since the JDK reads it once when its HTTP classes load
  (`HttpClients` sets it too, for tests). Defaults unless written: `User-Agent: SluIde`, `Accept: */*`,
  `Accept-Encoding: gzip, deflate`.
- **Bodies of responses** are decoded per `Content-Encoding` (`gzip`, `deflate`) and read up to 50 MB, then truncated
  with a note. The response pane decodes the charset, pretty-prints JSON and XML up to 1 MiB (`ResponseFormatter`;
  XML with DOCTYPEs is refused and shown as received) and shows binary bodies as a summary. Bodies, and the body of
  the request as sent, are shown up to 5 M characters (bytes for the request), with a note when cut; the request is
  shown as written, never pretty-printed.
- **Versions:** HTTP/1.1 unless the request line says `HTTP/2`, which is negotiated (no prior knowledge).
- **Redirects** are followed (`Redirect.NORMAL`: never from HTTPS to HTTP) unless `# @no-redirect`.
- **Timeouts:** `# @timeout` is a deadline for the whole exchange, body included; `# @connection-timeout` limits
  connecting; both default to 60 s. Preparation (e.g. a token request) is not covered.
- **Cookies:** one in-memory `CookieManager` per window (`ACCEPT_ORIGINAL_SERVER`), skipped with `# @no-cookie-jar`,
  cleared with Clear Cookies.
- **Auth:** `Authorization: Basic user password` (or `user:password`) is Base64-encoded. `Authorization: Digest user
  password` is not sent; a `401` with a Digest challenge is answered once (RFC 7616: MD5, SHA-256, their `-sess`
  variants, qop `auth` or none), with the same body. OAuth2 tokens: [ADR 0028](0028-http-client-secrets.md).
- **Proxies** come from the JDK's default `ProxySelector` (`http.proxyHost`, `https.proxyHost`, `http.nonProxyHosts`);
  there is no proxy setting of our own.

## Consequences
- No HTTP library; the JDK's behaviour (HTTP/2 negotiation, redirect rules, restricted headers) is what users get.
- A `Content-Length` or `Connection` written in a file has no effect, which the notes of the response say.
- The system property must be set before anything touches `java.net.http`; code that runs earlier must not use it.
- Added to [../index.md](../index.md).

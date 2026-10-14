# 0018. Serve HTML previews from a local server

- Status: accepted
- Date: 2026-10-14

## Context
The HTML preview ([ADR 0017](0017-webview-previews.md)) loaded pages from disk as `file:` URLs. With the page's
scripts allowed, a `file:` page can read every local file (XHR, `fetch`, iframes) and send it to the web, and JavaFX's
`WebEngine` has no setting to forbid it. Probes in JavaFX's WebKit showed:
- a page loaded over `http://127.0.0.1` cannot read `file:` URLs at all (XHR, `fetch` and iframes are refused);
- pages loaded from a string (`loadContent`: Markdown, notebooks, CSV, SVG, images) cannot read them either. These
  pages only run our scripts, on sanitised or escaped content.

So only HTML needs confining, and an HTTP origin confines it to what a server hands out.

## Decision
- HTML previews are loaded from `PreviewServer`, the JDK's `com.sun.net.httpserver` (no new dependency) bound to
  `127.0.0.1` (not the JDK's loopback address, which is `::1` when IPv6 is preferred), at
  `http://127.0.0.1:<port>/<token>/_/…/_/<path in the root>`. The `_` stand for the root's
  folders (one each, from the file system's root down), so a link climbing out of the root (`../other.html`) keeps a
  URL that maps to its file, without the URL naming the user's folders.
- **Root:** the window's project folder (`FileViewHost.projectFolder`), so pages that use `../` resources work
  (Javadoc, test and coverage reports). A file the project folder would not serve (outside it, with no project open,
  in a hidden folder, behind a symbolic link leading out) gets the folder it really is in.
- **What is served:** regular files inside the root, read at the real path that was checked, without following links.
  A folder's URL ending in `/` serves its `index.html` (if served itself), and one without `/` is redirected there, so
  that relative links on the index page resolve as on web servers; folders are never listed. Refused:
  - files outside the root, also through `..` or symbolic links (checked on the real path);
  - hidden files and everything in hidden folders (`.git`, `.env`, `.idea`), except the previewed page itself;
  - other methods than GET and HEAD;
  - requests with a `Host` other than `127.0.0.1:<port>`, against DNS rebinding.
- **Isolation between roots:** one server, so one origin, per root. A random 128-bit token in every path keeps out other
  local users and pages in a browser that find the port.
- **Response headers:** `Cache-Control: no-store`, so a reload shows edited styles; `X-Content-Type-Options: nosniff`;
  `Referrer-Policy: no-referrer`, so remote requests do not carry the token. Text types say `charset=utf-8`, the
  encoding SluIde reads files in, so a page without `<meta charset>` is not decoded with WebKit's default.
- **Links:** server URLs map back to their files (`PreviewServer.fileFor`), so a clicked link to another file opens it
  in a tab, as before, also outside the root.
- **Own page:** a URL of the page with a query is not the page itself, and a load the view did not start (a meta
  refresh in place, `location.reload()`) without the user's input stops the preview, so a page cannot reload in a
  loop.
- **Network:** remote requests stay allowed, with or without scripts. Blocking them (a Content Security Policy) would
  break pages that load libraries or styles from CDNs.

## Consequences
- With scripts on, a page reads only the project's non-hidden files instead of every local file. It can still send
  those files to the web; the warning before running scripts says so, with the root's full path. Secrets in non-hidden
  project files (e.g. a local `application.yml`) are within its reach, and for a page outside a project the root can
  be wide: a page in `~/Downloads` reaches the other downloads.
- Opening an HTML file still loads remote resources it references, as in ADR 0017.
- A server runs, on virtual threads, for every root that had an HTML preview, until the application exits: one per
  project, plus one per folder of previewed files outside a project. Closing a window does not stop its server.
- The `WebView`s do stop: a preview whose tab closes, or which a diff view replaces, loads a blank page
  (`FileView.dispose`), so the page's scripts, timers and requests end with it rather than when it is collected.
- WebKit honours `Referrer-Policy` (tested), so remote requests do not reveal a page's URL.
- Generated previews keep `loadContent`; they need no server.

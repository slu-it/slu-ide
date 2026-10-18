# 0056. HTTP client setup from the explorer

- Status: accepted
- Date: 2026-10-18

## Context
Starting with the HTTP client in a folder took four files written by hand: `http-client.env.json`,
`http-client.private.env.json`, a `.gitignore` that keeps the private one out of git (otherwise the view warns,
[ADR 0028](0028-http-client-secrets.md)) and a first `.http` file. Users want one action in the explorer's context menu
of a folder. The explorer (`file-explorer`) and the HTTP client (`http-client`) are unrelated feature modules, which do
not depend on each other ([ADR 0047](0047-module-dependencies-and-shared-modules.md)). The folder may already hold
some of the files, e.g. a `.gitignore` with other entries.

## Decision
- **The explorer offers folder actions the window contributes:** `FolderAction(label, perform)` in `file-explorer`'s
  API, set by `MainWindow` (like `showHistory`). Their menu items follow New Folder…, run on the same folder New File
  would use (the clicked folder, a clicked file's folder, the opened folder for the empty area) on the JavaFX thread,
  and return a `FolderActionResult`: the file to select and open, and a note to show. An `IOException` (or
  `UncheckedIOException`) is shown as an error. Rejected: a dependency of `file-explorer` on `http-client`, and a "New
  HTTP Client Setup" item in the explorer itself, which would put HTTP knowledge there.
- **The files come from `HttpClientSetup.create(folder)` in `http-client`,** plain `java.nio`, unit-tested. Contents:
  an environment `local` with `host` = `https://example.com` in the public file, an empty `local` in the private one,
  the private file's name in `.gitignore`, and `example.http` with `GET {{host}}`, so the first run already uses the
  environment.
- **Missing files are filled in, nothing is overwritten.** Each file is created with `CREATE_NEW`, so one that appears
  in between is kept too. An existing `.gitignore` gets the line appended unless one of its lines, trimmed, is the name
  or `/name`; there is no `.gitignore` parser ([ADR 0013](0013-git-status.md)), and a `.gitignore` further up that
  ignores the file already is not looked at, since a redundant line does no harm. A note names the files left as they
  were. Rejected: refusing when any file exists (a folder with a `.gitignore` could never be set up) and greying out
  the item (the reason would be invisible).

## Consequences
- Other modules can add folder actions (e.g. templates) without the explorer knowing them.
- A `.gitignore` whose ignore rule is written differently (`*.private.env.json`, a parent folder's file) gets a
  redundant line; the view's git check still tells whether the private file would be committed.
- Added to [../index.md](../index.md).

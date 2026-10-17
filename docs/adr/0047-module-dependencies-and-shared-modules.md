# 0047. Module dependencies and shared modules

- Status: accepted
- Date: 2026-10-17

## Context
The rule so far was that a module may depend on another only when it is a deliberate, documented API. That never
forbade dependencies between modules, and there are already several (`git-log` -> `git`, `terminal` -> `editor`,
`editor` -> `file-viewers`, `file-explorer` -> `settings`). ADR 0038 left the git log's changed files without file-type
icons because the icons were `internal` to `file-explorer`, and depending on that whole module (with `file-watcher`,
`settings` and its Spring view code) for one enum would have been the wrong coupling.

## Decision
- Modules may depend on other modules, without cycles. Modules never depend on `ide`.
- A module depends only on a module of its own capability family (`git-log` -> `git`) or on a small foundation module
  meant for sharing (`settings`, `file-watcher`, `text-editing`, `file-icons`). The editor family (`editor`,
  `file-viewers`, `terminal`, `docker-sandbox`, `http-client`: what is shown as editor tabs or file views) is one
  capability family, so its existing edges (`editor` -> `file-viewers`, `terminal` -> `editor`, `docker-sandbox` ->
  `terminal`, `http-client` -> `file-viewers`) follow the rule.
- Unrelated feature modules do not depend on each other. `ide` wires them through callbacks, as `git-log` with
  `editor` and `file-viewers`, and `git` with the explorer's markers.
- When two modules need the same piece of a third, that piece is extracted into a small module instead of depending
  on the whole feature module.
- Every module dependency is listed in `docs/architecture.md` with its reason and commented in the module's
  `build.gradle.kts`.
- A foundation module may be a plain library without Spring beans; "expose UI as a `@Component`" is for feature
  modules.
- `modules/file-icons` is the first extracted module: `FileIcon`, `fileIconFor`, their colours and a helper to show an
  icon in a `FontIcon` moved out of `file-explorer`; it and `git-log` now both use it ([ADR 0012](0012-file-icons.md)).

## Consequences
- Dependencies stay a small, acyclic, documented graph; a feature module's internals are not a shared surface.
- Sharing something means a new module with a build file and a registration, which is worth it only for pieces used
  by more than one module.
- Add the ADR to [../index.md](../index.md).

# 0070. Window services for file views

- Status: accepted
- Date: 2026-10-21

## Context
Viewers are singletons shared by all windows, so their views cannot get window-scoped beans injected.
[ADR 0026](0026-http-client-window-session.md) gave `FileViewHost.windowService(type)` a list,
`EditorArea.windowServices: List<Any>`, and answered with the first element that is an instance of `type`. The list grew
to two services, the HTTP client session and the background tasks for the exports of the previews. A list with
first-instance matching is an implicit service locator: order and subtypes decide which service a lookup finds, and
nothing says what belongs in it.

## Decision
`WindowServices` (module `file-viewers`, root package) is an exact-type registry: `register(type, service)` and
`get(type)`. `EditorArea` owns one as `windowServices`, `MainWindow` fills it, and
`FileViewHost.windowService(type)` answers from it.

It is the one channel through which views of singleton viewers reach window-scoped beans. It holds only window services
that views of another module need; everything else goes through module APIs
([ADR 0047](0047-module-dependencies-and-shared-modules.md)).

The entries today:
- `HttpClientSession` (module `http-client`, [ADR 0026](0026-http-client-window-session.md)), for the views of `.http`
  and environment files.
- `BackgroundTasks` (module `background-tasks`, [ADR 0061](0061-background-tasks.md)), for the exports of the previews.

## Consequences
- A type registered twice fails at once instead of one service hiding the other.
- Looking up an interface or a supertype finds nothing unless the service is registered under it; views ask for the
  class `MainWindow` registers.
- Another service is one `register` call in `MainWindow`, and a deliberate decision, as this ADR limits what belongs
  in the registry.
- Listed in [../index.md](../index.md).

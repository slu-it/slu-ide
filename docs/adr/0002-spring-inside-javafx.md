# 0002. Spring context inside the JavaFX Application

- Status: accepted; partly superseded by [0014](0014-project-windows.md) (window beans created per window)
- Date: 2026-10-12

## Context
Spring Boot and JavaFX both want to own the application lifecycle.

## Decision
A plain `javafx.application.Application` (`SluIdeApplication`) owns the lifecycle. It starts a **non-web** Spring
context in `init()` (no Tomcat), builds the UI in `start()` (originally from the `MainWindow` bean; since
[ADR 0014](0014-project-windows.md) `ProjectWindows` shows the first window), and closes the context in `stop()`.
The `main` function lives in a separate `SluIdeLauncher` file because the JVM requires JavaFX on the module path when
the main class extends `Application`. No third-party Spring/JavaFX integration library is used.

## Consequences
- UI components are Spring beans and receive dependencies through constructors.
- Singleton beans are created on the JavaFX launcher thread, not the FX application thread. Since
  [ADR 0014](0014-project-windows.md) the beans of a window (`MainWindow`, explorer, editor area, …) are created on
  the FX thread when the window opens. Creating controls needs a running toolkit, so tests that instantiate them need
  a display; context-wiring tests use lazy initialization instead.
- Manipulating live UI from other threads must go through `Platform.runLater`.

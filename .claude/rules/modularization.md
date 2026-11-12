---
paths:
  - "modules/**"
  - "ide/**"
  - "*.gradle.kts"
---

# Modularization

The module table, the capability families and the foundation modules are in `docs/architecture.md` ("Modules",
"Module rules"); that page is the one place that lists them.

- One Gradle module per capability under `modules/`; a new one is registered in `settings.gradle.kts` and applies
  `kotlin-jvm` (plus `javafx` if it uses JavaFX).
- Dependency direction: `ide` -> `modules/*`. Modules never depend on `ide`, and module dependencies never form a
  cycle (ADR 0047).
- A module may depend on a module of its own capability family (`git-log` -> `git`) or on a foundation module.
  Unrelated feature modules do not depend on each other; `ide` wires them through callbacks.
- When two modules need the same piece of a third, extract it into a small module instead of depending on the whole
  feature module.
- A module whose main code imports another module's types declares that module in its `build.gradle.kts` with a
  comment, even when the classes already arrive through some other module's `api`, and the dependency gets its row
  with the reason in the "Module dependencies" table of `docs/architecture.md`.
- Feature modules expose UI as a Spring `@Component` in package `dev.sluide.<module>`; keep implementation details
  `internal`/private. Foundation modules, and a family member without UI of its own, may be plain libraries without
  Spring beans.
- The root package holds the module's API (what other modules' main code imports); everything else goes into the
  sub-package of what it belongs to, by feature or layer. Small modules stay flat (ADR 0055).
- Pass state in through the module's public API instead of letting modules reach into each other.
- Shared build logic belongs in the root `build.gradle.kts`, not copied between modules.
- A new dependency of `MainWindow` also goes into the explicit bean lists of `ProjectWindowsUiTest` and
  `SpringWiringTest` and into the constructor call in `MainWindowUiFixture`; run `ProjectWindowsUiTest`.
- A new Spring bean in `dev.sluide.fileviewers` is also found by the previews' viewer scan (`AllViewersTest`), which
  has no beans from other modules: run the `:modules:previews` tests too, and exclude the bean there if it needs one.

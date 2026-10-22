---
paths:
  - "modules/**"
  - "ide/**"
  - "*.gradle.kts"
---

# Modularization

- One Gradle module per capability under `modules/`; register it in `settings.gradle.kts`.
- Dependency direction: `ide` -> `modules/*`. Modules never depend on `ide`, and module dependencies never form a
  cycle (ADR 0047).
- A module may depend on a module of its own capability family (`git-log` -> `git`) or on a small foundation module
  meant for sharing (`settings`, `file-watcher`, `text-editing`, `file-icons`, `project-trust`, `background-tasks`).
  Unrelated feature modules do not depend on each other; `ide` wires them through callbacks. The editor family
  (`editor-area`, `file-viewers`, `previews`, `terminal`, `docker-sandbox`, `http-client`, `github-actions`: what is
  shown as editor tabs or file views) counts as one capability family.
- When two modules need the same piece of a third, extract it into a small module instead of depending on the whole
  feature module.
- Every module dependency is listed in the "Module dependencies" table of `docs/architecture.md` (modules and
  families: its "Modules" section) with its reason and commented in the module's `build.gradle.kts`.
- Feature modules expose UI as a Spring `@Component` in package `dev.sluide.<module>`; keep implementation details
  `internal`/private. Foundation modules, and a family member without UI of its own (`github-actions`, shown by
  `previews`), may be plain libraries without Spring beans.
- The root package holds the module's API (what other modules' main code imports); everything else goes into the
  sub-package of what it belongs to, by feature or layer. Small modules stay flat (ADR 0055).
- Pass state in through the module's public API instead of letting modules reach into each other.
- Shared build logic belongs in the root `build.gradle.kts`, not copied between modules.

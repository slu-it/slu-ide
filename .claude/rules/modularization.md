---
paths:
  - "modules/**"
  - "ide/**"
  - "*.gradle.kts"
---

# Modularization

- One Gradle module per capability under `modules/`; register it in `settings.gradle.kts`.
- Dependency direction: `ide` -> `modules/*`. Modules never depend on `ide`; depend on another module only
  when it is a deliberate, documented API (record it in `docs/architecture.md`).
- Expose UI as a Spring `@Component` in package `dev.sluide.<module>`; keep implementation details `internal`/private.
- Pass state in through the module's public API instead of letting modules reach into each other.
- Shared build logic belongs in the root `build.gradle.kts`, not copied between modules.

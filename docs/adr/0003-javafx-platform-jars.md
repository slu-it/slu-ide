# 0003. JavaFX via the OpenJFX Gradle plugin; platform-specific fat jar

- Status: accepted
- Date: 2026-10-12

## Context
Maven Central publishes JavaFX 27 without Gradle module metadata. A plain `org.openjfx:javafx-controls`
dependency therefore resolves to an empty stub jar, and JavaFX classes are missing at compile time.

## Decision
Modules that use JavaFX apply the `org.openjfx.javafxplugin` plugin (see `javafx { ... }` in the module build files),
which selects the jar for the build machine's OS and architecture.

## Consequences
- The fat jar contains JavaFX natives for the platform it was built on only. Producing jars for other platforms
  requires building on that platform. The release workflow therefore builds on one runner per platform
  (linux-x64, linux-aarch64, mac-aarch64) and publishes each fat jar under its own artifactId
  `ide-<os>-<arch>` to the GitHub Packages Maven registry. Windows and Intel Macs are intentionally not supported.
- Every new module with JavaFX code must apply the plugin and list the JavaFX modules it needs.
- The sandbox used for development has no display, so the UI cannot be started there.

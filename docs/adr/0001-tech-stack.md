# 0001. Tech stack

- Status: accepted
- Date: 2026-10-12

## Context
Thoreau should be a lightweight IDE that can grow into a larger project. Established IDEs (IntelliJ, Eclipse) run
on the JVM, and the maintainer knows Spring Boot well.

## Decision
- **Kotlin only** (no Java sources), targeting **JDK 25** via the Gradle toolchain.
- **Spring Boot** as application framework (dependency injection, configuration).
- **JavaFX** for the UI.
- **Gradle** (Kotlin DSL, version catalog, wrapper) with one module per capability.

## Consequences
- Modules stay small and independently testable; the `ide` module assembles the fat jar.
- Versions are managed centrally in `gradle/libs.versions.toml`; Spring versions come from the Spring Boot BOM.
- See [0002](0002-spring-inside-javafx.md) and [0003](0003-javafx-platform-jars.md) for the JavaFX specifics.

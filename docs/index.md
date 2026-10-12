# SluIde documentation index

Entry point for agents: read this file first, then open only the documents relevant to your task.
Keep it up to date: every new ADR or feature description must be added here with a one-line summary.

## Overview
- [architecture.md](architecture.md) — Module layout, dependency rules, how Spring and JavaFX are combined, where to put new code.

## Decisions (ADRs)
- [adr/0001-tech-stack.md](adr/0001-tech-stack.md) — Kotlin on JDK 25, Spring Boot, JavaFX, Gradle multi-module build.
- [adr/0002-spring-inside-javafx.md](adr/0002-spring-inside-javafx.md) — A non-web Spring context is started and stopped by the JavaFX `Application`; UI parts are Spring beans.
- [adr/0003-javafx-platform-jars.md](adr/0003-javafx-platform-jars.md) — JavaFX comes from the OpenJFX Gradle plugin because Maven Central has no Gradle metadata; fat jars are platform-specific.
- [adr/template.md](adr/template.md) — Template for new ADRs.

## Features
- [features/open-folder.md](features/open-folder.md) — File → Open Folder… sets the current project folder (`ProjectService`).
- [features/file-explorer.md](features/file-explorer.md) — Tree view of the project folder incl. hidden files, lazy loading, sort order.

# SluIde documentation index

Entry point for agents: read this file first, then open only the documents relevant to your task.
Keep it up to date: every new ADR or feature description must be added here with a one-line summary.

## Overview
- [architecture.md](architecture.md) — Module layout, dependency rules, runtime startup, CI and release process.

- [testing.md](testing.md) — Unit vs. TestFX UI tests, `uiTest` task, display handling locally and in CI, how to write UI tests, running and screenshotting the app in a headless sandbox.

## Decisions (ADRs)
- [adr/0001-tech-stack.md](adr/0001-tech-stack.md) — Kotlin on JDK 25, Spring Boot, JavaFX, Gradle multi-module build.
- [adr/0002-spring-inside-javafx.md](adr/0002-spring-inside-javafx.md) — A non-web Spring context is started and stopped by the JavaFX `Application`; UI parts are Spring beans.
- [adr/0003-javafx-platform-jars.md](adr/0003-javafx-platform-jars.md) — JavaFX comes from the OpenJFX Gradle plugin; fat jars are platform-specific, so releases are built and published per OS/arch (no Windows).
- [adr/0004-file-viewer-selection.md](adr/0004-file-viewer-selection.md) — Viewers are asked in turn whether they can open a file, `DefaultTextFileViewer` last; no viewer means the file is not opened.
- [adr/template.md](adr/template.md) — Template for new ADRs.

## Features
- [features/open-folder.md](features/open-folder.md) — File → Open Folder… sets the current project folder (`ProjectService`).
- [features/file-explorer.md](features/file-explorer.md) — Tree view of the project folder incl. hidden files, lazy loading, sort order, opening files (double-click/Enter).
- [features/editor-tabs.md](features/editor-tabs.md) — Central area with one closable tab per open file, scroll arrows and a dropdown for tabs that do not fit.
- [features/file-viewers.md](features/file-viewers.md) — `FileViewer` extension point, viewer selection and the read-only `DefaultTextFileViewer` (text detection rules).

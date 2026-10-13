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
- [adr/0005-syntax-highlighting.md](adr/0005-syntax-highlighting.md) — Code views render with RichTextFX and tokenize with the RSyntaxTextArea lexers behind our own `SyntaxHighlighter`; alternatives considered.
- [adr/0006-file-watching.md](adr/0006-file-watching.md) — JDK `WatchService` behind `FileWatcher`, watching loaded folders and open files only; combined events, macOS caveat.
- [adr/0007-terminal.md](adr/0007-terminal.md) — Terminals use pty4j and the JediTerm core (from JetBrains' repository, restricted to its group) with our own JavaFX canvas view; alternatives considered.
- [adr/0008-settings.md](adr/0008-settings.md) — Settings live in `modules/settings` as Spring beans with JavaFX properties, stored in `~/.sluide/settings.properties`; dialog built from `SettingsPage` beans.
- [adr/0009-themes.md](adr/0009-themes.md) — Light/dark/system theme: scene colour-scheme preferences plus `@media (prefers-color-scheme: dark)` rules in each stylesheet; single-class selectors only in dark blocks.
- [adr/0010-docker-sandbox.md](adr/0010-docker-sandbox.md) — Docker sandboxes run as the process of a terminal tab, only in folders strictly below the home folder (real paths), checked in the menu and again on start.
- [adr/0011-native-app-packaging.md](adr/0011-native-app-packaging.md) — jpackage builds SluIde.app / a disk image with a jlink runtime (`:ide:jpackageImage`, `:ide:jpackageDmg`), attached to GitHub releases; not notarized, allowed once via Open Anyway.
- [adr/0012-file-icons.md](adr/0012-file-icons.md) — File explorer icons from Ikonli's Material Design Icons pack (one style), chosen by folder state, extension and readability, coloured per type in CSS; pack comparison.
- [adr/template.md](adr/template.md) — Template for new ADRs.

## Features
- [features/open-folder.md](features/open-folder.md) — File → Open Folder… sets the current project folder (`ProjectService`).
- [features/file-explorer.md](features/file-explorer.md) — Tree view of the project folder incl. hidden files, lazy loading, sort order, opening files (double-click/Enter), live updates and Refresh (context menu/F5).
- [features/editor-tabs.md](features/editor-tabs.md) — Central area with one closable tab per open file or other `EditorTab` (e.g. terminals), tab context menu (close others/left/right/all), scroll arrows and a dropdown for tabs that do not fit.
- [features/settings.md](features/settings.md) — File → Settings… (Ctrl+,): page tree, OK/Apply/Cancel, Display → Appearance (Light/Dark/System theme), Display → Fonts (font, size, style for explorer, editor, terminal) with preview, stored per user.
- [features/terminal.md](features/terminal.md) — Terminal → New Terminal (Ctrl+Shift+T) opens the user's shell in the project folder as an editor tab; colours, full-screen programs, scrollback, copy/paste.
- [features/docker-sandbox.md](features/docker-sandbox.md) — Docker Sandbox → Claude Code (`sbx run claude .`) / Environment (`sbx env run`, needs `sbxenv.yaml`) in a terminal tab, only below the home folder.
- [features/file-watching.md](features/file-watching.md) — `FileWatcher`: per-directory watches, combined events on the FX thread, used by explorer and editor.
- [features/file-viewers.md](features/file-viewers.md) — `FileViewer` extension point, viewer selection, the read-only `DefaultTextFileViewer` (text detection rules) and the syntax-highlighted `CodeFileViewer`s (Python, Kotlin, Java, JavaScript, TypeScript, JSON, XML, HTML, YAML).

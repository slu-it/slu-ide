# 0007. Terminals with pty4j and the JediTerm core, drawn by our own JavaFX view

- Status: accepted
- Date: 2026-10-13

## Context
Users want a shell in the editor area, started in the project folder. A usable terminal needs a pseudo terminal
(so `vim`, `top`, Ctrl+C and colours work) and a terminal emulator that interprets the escape sequences into a
screen buffer. The UI is JavaFX only; there is no Swing in the application.

Options considered:
- **JediTerm's Swing widget in a `SwingNode`**: complete, but mixing Swing and JavaFX brings focus, keyboard,
  HiDPI and theming problems and adds `javafx.swing`.
- **A process console without a pseudo terminal** (output in a text area): simple, but no colours, no cursor
  control, no full-screen programs and no proper Ctrl+C.
- **jeditermfx** (a third-party JavaFX port of JediTerm on Maven Central): avoids a second repository, but is a
  fork by a small project, last released in Feb 2025.
- **pty4j plus the original JediTerm core with our own JavaFX view**.

## Decision
- A module `modules/terminal` uses **pty4j** (`org.jetbrains.pty4j:pty4j`, Maven Central) to run the user's shell
  in a pseudo terminal and the **JediTerm core** (`org.jetbrains.jediterm:jediterm-core`) to emulate xterm: escape
  sequences, screen and scrollback buffer, key encoding. The core has no UI dependencies.
- We draw the buffer ourselves on a JavaFX `Canvas` (`TerminalView`, which implements JediTerm's `TerminalDisplay`)
  and translate JavaFX key events (`TerminalKeys`).
- JediTerm is only published to JetBrains' repository
  `https://packages.jetbrains.team/maven/p/ij/intellij-dependencies`. `settings.gradle.kts` adds it as an
  `exclusiveContent` repository restricted to the group `org.jetbrains.jediterm`, so no other dependency can come
  from there.

## Consequences
- Terminals behave like real ones: colours (16, 256, true colour), full-screen programs with the alternate screen,
  Ctrl+C, resizing, scrollback.
- Builds need access to `packages.jetbrains.team` and the CDN it redirects downloads to
  (`db2uklzeiyqq6.cloudfront.net`), in addition to Maven Central; restricted networks must allow both.
- pty4j ships natives for Linux (x64, aarch64), macOS and Windows inside its jar and extracts them at runtime, so
  the per-platform fat jars ([ADR 0003](0003-javafx-platform-jars.md)) work without extra build steps.
- Rendering, input, selection and clipboard are our code: features like mouse reporting to programs, hyperlinks or
  search have to be built by us when wanted.

# SluIde

A lightweight IDE written in Kotlin, using Spring Boot and JavaFX.

Currently it can open a folder as project (File → Open Folder…) and shows its complete content, including hidden
files, in a file explorer. Text files open read-only in tabs (double-click or Enter in the explorer), source files of common languages (Kotlin, Java, Python, JavaScript, TypeScript, JSON, XML, HTML, YAML) with syntax
highlighting. Terminal → New Terminal (Ctrl+Shift+T) opens a shell in the project folder as a tab.

## Requirements
- JDK 25
- A display (to run the UI)

## Build and run
```bash
./gradlew build                # compile and test all modules
./gradlew :ide:bootJar         # build the fat jar
java -jar ide/build/libs/ide-0.1.0-SNAPSHOT.jar
```
The fat jar contains JavaFX natives for the platform it was built on only (Linux or macOS).

## Repository layout
| Path | Content |
|------|---------|
| `ide/` | Application: launcher, window, fat jar |
| `modules/` | Feature modules (`file-explorer`, `editor`, `file-viewers`, `file-watcher`, `terminal`) |
| `docs/` | Architecture, ADRs and feature descriptions, starting at [`docs/index.md`](docs/index.md) |
| `.claude/` | Claude Code settings, agents, rules and scripts |
| `.github/` | CI and release workflows |

## Releases
Pushing a tag like `v0.1.0` publishes the fat jar for each platform to the GitHub Packages Maven registry
(`dev.sluide:ide-<os>-<arch>`). See [`docs/architecture.md`](docs/architecture.md).

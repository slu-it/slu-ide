# SluIde

A lightweight IDE written in Kotlin, using Spring Boot and JavaFX.

Currently it can open a folder as project (File → Open Folder…) and shows its complete content, including hidden
files, in a file explorer. Text files open read-only in tabs (double-click or Enter in the explorer), source files of common languages (Kotlin, Java, Python, JavaScript, TypeScript, JSON, XML, HTML, YAML) with syntax
highlighting. Terminal → New Terminal (Ctrl+Shift+T) opens a shell in the project folder as a tab. File → Settings… (Ctrl+,) sets
the fonts of the explorer, the editor and the terminal.

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

### macOS app
```bash
./gradlew :ide:jpackageImage   # ide/build/jpackage/app-image/SluIde.app, includes its own Java runtime
./gradlew :ide:jpackageDmg     # disk image with the app
```
Copy `SluIde.app` to `/Applications` and start it with a double-click. Releases attach
`SluIde-<version>-mac-aarch64.dmg` to the GitHub release. The app is not notarized by Apple: when downloaded, open
it once, then allow it under System Settings → Privacy & Security → Open Anyway (or run
`xattr -dr com.apple.quarantine /Applications/SluIde.app`). See
[ADR 0011](docs/adr/0011-native-app-packaging.md).

## Repository layout
| Path | Content |
|------|---------|
| `ide/` | Application: launcher, window, fat jar |
| `modules/` | Feature modules (`file-explorer`, `editor`, `file-viewers`, `file-watcher`, `terminal`, `settings`) |
| `docs/` | Architecture, ADRs and feature descriptions, starting at [`docs/index.md`](docs/index.md) |
| `.claude/` | Claude Code settings, agents, rules and scripts |
| `.github/` | CI and release workflows |

## Releases
Pushing a tag like `v0.1.0` publishes the fat jar for each platform to the GitHub Packages Maven registry
(`dev.sluide:ide-<os>-<arch>`) and attaches the macOS disk image to the GitHub release. See [`docs/architecture.md`](docs/architecture.md).

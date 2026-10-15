# Thoreau

A lightweight IDE written in Kotlin, using Spring Boot and JavaFX.

It is built for one person, to work ideally for them rather than for a wider market. Henry David Thoreau built his
own small cabin at Walden Pond, sized for exactly one person and furnished only with what he needed; hence the name.
The technical name is still `sluide` (repository, packages, artifacts, `~/.sluide`), see
[ADR 0032](docs/adr/0032-product-name-thoreau.md).

- **Projects:** File → Open Folder… (or `Thoreau [folder…]` on the command line) opens a folder; one project per
  window, several windows at once.
- **File explorer:** the whole folder including hidden files, with file icons, git status colours and live updates.
- **Files** open in tabs to edit and save (File → Save, Ctrl/Cmd+S; Save All), keeping their line endings, with
  a prompt before unsaved changes are closed: syntax highlighting for common languages (Kotlin, Java, Python,
  JavaScript, TypeScript, JSON, XML, YAML, CSS, SQL, KQL, shell, PowerShell and more) and config files (Dockerfiles,
  Makefiles, INI, TOML and lock files, Bicep, ignore files, Caddyfiles, `.env` with masked values), also by file name
  or shebang, with a note on generated lock files; rendered previews beside the source for Markdown, HTML, SVG, CSV,
  Jupyter notebooks, Mermaid diagrams, Bicep resource graphs, cookie jars and PEM certificates; images on their own
  page. Lines changed against `HEAD` in git are marked
  beside the line numbers, and View → Show Changes (Ctrl/Cmd+Shift+D) shows the side-by-side diff.
- **HTTP client:** `.http` files as in IntelliJ: run the request at the caret (Ctrl/Cmd+Enter or the run icon beside
  it) with environments from `http-client.env.json` and a private file for secrets, variables, OAuth2 tokens and
  client certificates; the response opens below the editor.
- **Terminals** (Terminal → New Terminal, Ctrl+Shift+T) run your shell in the project folder as a tab; **Docker
  sandboxes** (Claude Code, Environment) run `sbx` there.
- **Settings** (File → Settings…, Ctrl+,): Light, Dark or System theme and the fonts of explorer, editor and
  terminal.

Details: [`docs/index.md`](docs/index.md).

## Requirements
- JDK 25
- A display (to run the UI)
- Optional: `git` on the `PATH` for git status and diffs, `sbx` for Docker sandboxes

## Build and run
```bash
./gradlew build                # compile and test all modules
./gradlew :ide:bootJar         # build the fat jar
java -jar ide/build/libs/ide-0.1.0-SNAPSHOT.jar [folder…]
```
Folders given on the command line open at startup, each in its own window.
The fat jar contains JavaFX natives for the platform it was built on only (Linux or macOS).

### macOS app
```bash
./gradlew :ide:jpackageImage   # ide/build/jpackage/app-image/Thoreau.app, includes its own Java runtime
./gradlew :ide:jpackageDmg     # disk image with the app
```
Copy `Thoreau.app` to `/Applications` and start it with a double-click. Releases attach
`Thoreau-<version>-mac-aarch64.dmg` to the GitHub release. The app is not notarized by Apple: when downloaded, open
it once, then allow it under System Settings → Privacy & Security → Open Anyway (or run
`xattr -dr com.apple.quarantine /Applications/Thoreau.app`). See
[ADR 0011](docs/adr/0011-native-app-packaging.md).

## Repository layout
| Path | Content |
|------|---------|
| `ide/` | Application: launcher, window, fat jar |
| `modules/` | Feature modules (`file-explorer`, `editor`, `file-viewers`, `file-watcher`, `terminal`, `settings`, `docker-sandbox`, `git`, `text-editing`, `http-client`) |
| `docs/` | Architecture, ADRs and feature descriptions, starting at [`docs/index.md`](docs/index.md) |
| `.claude/` | Claude Code settings, agents, rules and scripts |
| `.github/` | CI and release workflows |

## Releases
Pushing a tag like `v0.1.0` publishes the fat jar for each platform to the GitHub Packages Maven registry
(`dev.sluide:ide-<os>-<arch>`) and attaches the macOS disk image to the GitHub release. See
[`docs/architecture.md`](docs/architecture.md).

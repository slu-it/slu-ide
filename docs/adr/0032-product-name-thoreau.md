# 0032. Product name Thoreau

- Status: accepted
- Date: 2026-10-15

## Context
The IDE started as SluIde, after its author. It is built for exactly one person, to work ideally for them, without
considering wider market appeal. Henry David Thoreau built his own small cabin at Walden Pond, sized for exactly one
person and furnished only with what he needed. That is the idea of this IDE, so it should carry his name.

Renaming everything (repository, Gradle project, packages, classes, published artifacts, settings folder) would touch
nearly every file, break existing settings and release coordinates, and gain nothing a user sees.

## Decision
- The name shown to the user is **Thoreau**: window titles (`Thoreau`, `<folder> – Thoreau`, since followed by the
  version, see `features/open-folder.md`), messages on stderr, the font preview text, the header of the settings
  file, the default `User-Agent` of the HTTP client, the native app (`Thoreau.app`, `bin/Thoreau` on Linux), the
  macOS bundle and menu name, the disk image (`Thoreau-<version>-mac-aarch64.dmg`), and the prose of README, docs and
  agent instructions.
- The technical name stays **sluide**: repository `slu-ide`, Gradle project, packages `dev.sluide.*`, class names
  (`SluIdeApplication`, `SluIdeLauncher`, `SluIdeConfiguration`), Maven group `dev.sluide` and artifacts
  `ide-<os>-<arch>`, the settings folder `~/.sluide`, the macOS bundle identifier `dev.sluide.ide`, thread names,
  the preview scripts' `window.sluide`/`sluideBridge`, the bean `sluideFileWatcher` and test fixtures (e.g. the
  certificates' `SluIde Test CA`).

## Consequences
- Settings in `~/.sluide` carry over; macOS sees `Thoreau.app` as the same app as `SluIde.app` (same bundle
  identifier). An installed `SluIde.app` is not replaced and has to be deleted by hand.
- Code and docs use two names: "Thoreau" for the product, `sluide` in identifiers. New identifiers keep `sluide`.
- Add the ADR to [../index.md](../index.md).

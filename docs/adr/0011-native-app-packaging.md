# 0011. Native app via jpackage, not notarized

- Status: accepted
- Date: 2026-10-13

## Context
Thoreau was started with `java -jar ide-<os>-<arch>-<version>.jar`, which needs an installed JDK 25 and a terminal.
On macOS it should start with a double-click like any other app. A downloaded jar opened with Jar Launcher is
blocked by Gatekeeper ("Apple could not verify … is free of malware"): the download is quarantined and not signed
with an Apple Developer ID. Any unsigned download (jar, app or disk image) gets that dialog.

Alternatives considered:
- A hand-made `.app` (Info.plist plus a script running the installed `java -jar`): small, but needs a JDK 25 and
  has the same Gatekeeper dialog.
- GraalVM native image: not practical with Spring Boot, JavaFX and pty4j (reflection, JNA natives).
- Signing with a Developer ID and notarizing in CI: removes the dialog, but needs a paid Apple Developer account
  and certificate secrets. Not worth it for now; it can be added to the same jpackage setup later
  (`--mac-sign`, `xcrun notarytool`, `xcrun stapler`).

## Decision
`jpackage` (part of the JDK) builds the app from the fat jar, with a Java runtime made by jlink:
- `./gradlew :ide:jpackageImage` builds `ide/build/jpackage/app-image/Thoreau.app` on macOS (a `Thoreau/` folder with
  `bin/Thoreau` on Linux), `:ide:jpackageDmg` a disk image with it (macOS only). Tasks in `ide/build.gradle.kts`.
- The runtime contains only the JDK modules `jdeps` finds (`appModules` task) plus a few loaded at runtime, and is
  compressed: about 60 MB instead of about 95 MB with jpackage's default jlink options (measured on linux-aarch64
  with JDK 25).
- The release workflow attaches `Thoreau-<version>-mac-aarch64.dmg` to the GitHub release of the tag.
- The app is only ad-hoc signed (as jpackage does by default), not notarized.

## Consequences
- No JDK is needed to run the app; the fat jars are still published for `java -jar`.
- A downloaded app must be allowed once: open it, then System Settings → Privacy & Security → Open Anyway, or run
  `xattr -dr com.apple.quarantine /Applications/Thoreau.app`. Apps built locally are not quarantined.
- macOS requires an app version of at least 1; before 1.0 the app reports version 1.0 (the disk image name in the
  release still has the real version).
- jpackage cannot cross-build: like the fat jar, the app is built on each platform for that platform.
- Started from Finder, the app gets launchd's minimal environment (e.g. `PATH=/usr/bin:/bin:/usr/sbin:/sbin`, no
  `LANG`). Terminals and sandboxes run a login shell (`-l`), which loads the user's profile and with it the `PATH`.
- A JDK module that jdeps does not see (used only via reflection) is missing from the runtime and fails at runtime;
  add it to the extra modules of the jpackage tasks.
- On Ubuntu's OpenJDK, jlink needs `objcopy` (package `binutils`); Temurin, used in CI, does not.

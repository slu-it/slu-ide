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
- The app icon is the Thoreau logo, a cabin at the pond: `ide/packaging/thoreau.svg` is its source;
  `ide/packaging/render-icon.sh` renders it to the committed `Thoreau.icns` (macOS), `thoreau-512.png` (Linux) and
  the window icons in the `ide` resources (set on every stage by `WindowIcons`). Run it after changing the SVG.

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
- jdeps stops on a jar whose module descriptor requires a module it cannot resolve (Lingua's requires `okio` and
  `fastutil`, which are only automatic modules): `appModules` leaves such a jar out of the analysis but keeps it on
  the class path, prints jdeps' error when it fails, and runs as part of `check`, so CI notices before packaging.
- On Ubuntu's OpenJDK, jlink needs `objcopy` (package `binutils`); Temurin, used in CI, does not.

## Amendment: the runtime also serves the JShell engine (ADR 0191)
The bundled runtime is the last JDK fallback of the JShell view, and the engine's agent runs as
`<runtime>/bin/java -cp agent.jar:kotlin-stdlib.jar …`. So:
- `jdk.jshell`, `jdk.compiler`, `jdk.jdi` and `jdk.jdwp.agent` are added to the extra modules (jdeps cannot see the
  agent's classes, which are not in the module jar). Its `release` file then lists `jdk.jshell` in MODULES, which
  `Jdks.hasJShell` checks.
- The jlink option `--strip-native-commands` is dropped, so `bin/java` stays a regular executable file (`Jdks.read`
  needs it). Alternative: keep stripping and
  drop all launchers but `java` with an `--exclude-files` pattern in the same jlink call (e.g.
  `regex:/[^/]+/bin/(?!java$)[^/]+`; Windows has `java.exe` and `javaw.exe`); that saves about 0.6 MB (javac, jshell,
  jdb, jfr, keytool, ...), not worth a pattern per platform. Building the image with jlink ourselves, copying
  `bin/java` back and passing `--runtime-image` is the heavier variant. Nothing is patched into the runtime after jpackage, because
  macOS signs the runtime and a later change would break the signature.
- `lib/ct.sym` (about 11 MB; only `javac --release` reads it, which the fallback never uses) and `**.debuginfo` are
  excluded with `--exclude-files`. Debian's JDKs carry `lib/server/libjvm.debuginfo` (617 MB) and more, which put
  the runtime at about 710 MB on linux-aarch64; Temurin is expected to have none (not checked).
- Measured on linux-aarch64 with this Debian JDK 25: runtime 710 MB before (`du -sb`: 710,487,215 bytes), 69.5 MB
  after (69,533,006 bytes). Without the debug info the old runtime was 63.3 MB, so JShell costs about 6 MB.
- Side effect: jdtls runs on Thoreau's own `java` (`JdtlsLaunch`) and the JDK chain ends with Thoreau's own JDK, which
  the packaged app used to skip because it had no `bin/java`. Now it counts as a JDK: with no project setting and no
  `JAVA_HOME` it is the project JDK and is offered in Project Settings → Java. It is a partial JDK (about 36 modules,
  no `java.rmi`, `java.smartcardio`, `src.zip` or `ct.sym`), so a project falling back to it can lack JDK classes or
  sources. jdtls starting on it is not verified (jdtls is not installed in the sandbox).
- macOS (app image, signing, `JShellRealTest` on the runtime inside `Thoreau.app`) is not checked yet.

# SluIde

Lightweight IDE in Kotlin (JDK 25) with Spring Boot and JavaFX, built with Gradle modules.

## Orient yourself
- Read `docs/index.md` first; open only the documents relevant to your task. Do not read all of `docs/`.
- Layout: `ide/` (app, fat jar), `modules/<name>/` (one Gradle module per capability), `docs/`, `.claude/`, `.github/`.

## Commands
- Build and test everything: `./gradlew build`
- Format Kotlin: `./gradlew spotlessApply` (ktlint via Spotless, rules in `.editorconfig`); `build` fails on violations
- Fat jar: `./gradlew :ide:bootJar` (JavaFX natives are included for the build platform only)
- Check the docs index: `.claude/scripts/check-docs-index.sh`

## Rules for everyone
- Kotlin only. All versions live in `gradle/libs.versions.toml`; never hard-code versions in module builds.
- Code lives under the package `dev.sluide.<module>` so Spring component scanning finds it.
- Feature modules never depend on `ide`. Details: `docs/architecture.md`.
- Any module using JavaFX applies the `javafx` plugin (see ADR 0003).
- Significant decisions get an ADR (`docs/adr/template.md`); new features get a page in `docs/features/`.
  Always add new docs to `docs/index.md` with a one-line summary.
- JavaFX is not published with Gradle metadata: never declare `org.openjfx:*` dependencies directly (they resolve
  to empty stub jars); use the `javafx` plugin block as in `ide/build.gradle.kts`.
- JavaFX controls cannot be instantiated without a running toolkit, so tests must not create them.
- Downloads with `curl` need `-L` (redirects) plus checksum verification; a 169-byte "zip" is a redirect page.
- Headless Linux sandbox: run `.claude/scripts/setup-display.sh` (installs Xvfb + GTK libs, starts display `:99`), then
  `DISPLAY=:99 java -Dprism.order=sw -jar ide/build/libs/ide-*.jar` and use `import -window root x.png` / `xdotool`.
  Dialogs need `xdotool windowfocus <id>` (no window manager). Never claim UI was verified without actually running it.
- Commits: small, one logical step each, imperative subject line. No Claude attribution lines or session links
  in commits or PRs (also enforced in `.claude/settings.json`).

Role-specific guidance lives in `.claude/agents/`, topic rules in `.claude/rules/`.

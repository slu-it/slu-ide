# SluIde

Lightweight IDE in Kotlin (JDK 25) with Spring Boot and JavaFX, built with Gradle modules.

## Orient yourself
- Read `docs/index.md` first; open only the documents relevant to your task. Do not read all of `docs/`.
- Layout: `ide/` (app, fat jar), `modules/<name>/` (one Gradle module per capability), `docs/`, `.claude/`, `.github/`.
  Module and package rules: `docs/architecture.md`.

## Commands
- Build and test everything: `./gradlew build` (unit tests in `test`, TestFX UI tests in `uiTest`, which needs a display)
- Format Kotlin: `./gradlew spotlessApply` (ktlint via Spotless, rules in `.editorconfig`); `build` fails on violations
- Fat jar: `./gradlew :ide:bootJar` (JavaFX natives are included for the build platform only)
- Check the docs index: `.claude/scripts/check-docs-index.sh`

## Rules for everyone
- Kotlin only. All versions live in `gradle/libs.versions.toml`; never hard-code versions in module builds.
- JavaFX: never declare `org.openjfx:*` dependencies directly (no Gradle metadata, they resolve to empty stub jars);
  apply the `javafx` plugin as in `ide/build.gradle.kts` (ADR 0003).
- Tests that create JavaFX controls are TestFX tests tagged `@Tag("ui")`; running or screenshotting the UI in a
  headless sandbox: `docs/testing.md`. Never claim UI was verified without actually running it.
- Significant decisions get an ADR (`docs/adr/template.md`), new features a page in `docs/features/`;
  add every new doc to `docs/index.md` with a one-line summary.
- `curl` downloads need `-L` (redirects) and checksum verification; a 169-byte "zip" is a redirect page.
- Commits: small, one logical step each, imperative subject line. No Claude attribution lines or session links
  in commits or PRs (also enforced in `.claude/settings.json`).

Role-specific guidance lives in `.claude/agents/`, topic rules in `.claude/rules/`.

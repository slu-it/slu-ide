# Thoreau

Lightweight IDE in Kotlin (JDK 25) with Spring Boot and JavaFX, built with Gradle modules.
Thoreau is the name users see; identifiers keep the technical name `sluide` (repository, packages, classes, `~/.sluide`),
new ones too (ADR 0032).

## Orient yourself
- Read `docs/index.md` first, unless your brief already names the docs to read; open only the documents relevant to
  your task. Do not read all of `docs/`.
- Layout: `ide/` (app, fat jar), `modules/<name>/` (one Gradle module per capability), `docs/`, `.claude/`, `.github/`.
  Module and package rules: `docs/architecture.md`.

## Commands
- Build and test everything: `./gradlew build -q` (unit tests in `test`, TestFX UI tests in `uiTest`, which needs a
  display); `-q` prints only errors and failed tests with their exception, so read the output in full instead of tailing
- Subset of tests: `docs/testing.md`, "Running a subset"; subagents never run the full build, the main session does
  after the last review
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
- Files over ~400 lines (big UI test classes; `.claude/scripts/check-file-sizes.sh` lists production files over 600):
  find the place with Grep, then Read only that range (`offset`/`limit`). Don't read whole test classes to add one
  test; read a neighbouring test as a pattern. Don't grow a production file past 600 lines; split it by
  responsibility (ADR 0054).
- `curl` downloads need `-L` (redirects) and checksum verification; a 169-byte "zip" is a redirect page.
- Commits: small, one logical step each, imperative subject line. No Claude attribution lines or session links
  in commits or PRs (also enforced in `.claude/settings.json`).
- Git: commit finished steps without asking; never push. The user pushes.

## Delegation
- `explorer` (Haiku) searches, `implementer` and `tester` (Sonnet) work from detailed briefs, `reviewer` (Opus)
  checks every change before it is merged or reported done. Subagents do not spawn further agents.
- Load the `delegate` skill before handing work to subagents (brief template, escalation to Opus, worktrees).

Role-specific guidance lives in `.claude/agents/`, topic rules in `.claude/rules/`, workflows in `.claude/skills/`.

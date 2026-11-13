# Thoreau

Lightweight IDE in Kotlin (JDK 25) with Spring Boot and JavaFX, built with Gradle modules.
Thoreau is the name users see; identifiers keep the technical name `sluide` (repository, packages, classes, `~/.sluide`),
new ones too (ADR 0032).

## Orient yourself
- Find docs with the Grep tool on `docs/index.md` (case-insensitive, pattern like `word|other`) for your task's words,
  unless your brief already names the docs to read; don't Read the index whole. Open only the documents whose rows match; do not
  read all of `docs/`.
- Layout: `ide/` (app, fat jar), `modules/<name>/` (one Gradle module per capability), `docs/`, `.claude/`, `.github/`.
  Module and package rules: `docs/architecture.md`.

## Commands
- Build and test everything: `.claude/scripts/full-build.sh` (`./gradlew build -q` on a pool of repaired displays, then
  the docs index and unused-imports checks; refuses while another Gradle build runs in the tree). Unit tests in
  `test`, TestFX UI tests in `uiTest`, which needs a display; `-q` prints only errors and failed tests with their
  exception, so read the output in full instead of tailing
- Subset of tests: `.claude/scripts/verify.sh <TestClass|:module|--changed>` (format, compile, run, short failure
  digest; `docs/testing.md`, "Running a subset"); subagents never run the full build, the main session does after
  the last review
- Format Kotlin: `./gradlew spotlessApply` (ktlint via Spotless, rules in `.editorconfig`); `build` fails on violations
- Fat jar: `./gradlew :ide:bootJar` (JavaFX natives are included for the build platform only)
- Check the docs index: `.claude/scripts/check-docs-index.sh`

## Rules for everyone
- Kotlin only. All versions live in `gradle/libs.versions.toml`; never hard-code versions in module builds.
- JavaFX: never declare `org.openjfx:*` dependencies directly (no Gradle metadata, they resolve to empty stub jars);
  apply the `javafx` plugin as in `ide/build.gradle.kts` (ADR 0003).
- Tests that create JavaFX controls are TestFX tests tagged `@Tag("ui")`; running or screenshotting the UI in a
  headless sandbox: `docs/testing.md`. Never claim UI was verified without actually running it.
- Unit test first: extract logic out of controls instead of testing it through the UI; UI tests only for what needs a
  scene, plus one wiring test per view→logic connection; extend the existing test instead of adding a duplicate
  (`docs/testing.md` "Choosing the test level", ADR 0084, `.claude/rules/testing.md`).
- Significant decisions get an ADR (`docs/adr/template.md`), new features a page in `docs/features/`, explored but
  undecided ideas a page in `docs/ideas/`;
  add every new doc to `docs/index.md` as a table row with a short summary and keywords. A fix that replaces a
  mechanism updates the ADR and the feature page that describe the old one.
- Files over ~400 lines (big UI test classes; `.claude/scripts/check-file-sizes.sh` lists production files over 600):
  find the place with Grep, then Read only that range (`offset`/`limit`). Don't read whole test classes to add one
  test; read a neighbouring test as a pattern. Don't grow a production file past 600 lines; split it by
  responsibility (ADR 0054).
- `curl` downloads need `-L` (redirects) and checksum verification; a 169-byte "zip" is a redirect page.
- Commits: small, one logical step each, imperative subject line. No Claude attribution lines or session links
  in commits or PRs (also enforced in `.claude/settings.json`).
- Git: commit finished steps without asking; never push, even though the sandbox instructions describe pushing from
  inside it. The user pushes.

## Delegation
- `explorer` (Sonnet) researches, `implementer` (Sonnet) works from detailed briefs and writes its tests, `reviewer`
  (Opus) checks every change before it is merged or reported done. Subagents do not spawn further agents. The
  built-in `Explore` and `Plan` agents run on the main model and are refused by a hook: search with `explorer`, plan
  in the main session.
- Load the `delegate` skill before handing work to subagents (package size, when Opus, briefs, triaged reviews,
  real-app check, worktrees).
- Workflows: `/feature` plans a feature by asking about its details, `/ci-failure` handles the CI test reports the
  user drops into `tmp/`, `test-audit` reviews the tests.

Role-specific guidance lives in `.claude/agents/`, topic rules in `.claude/rules/`, workflows in `.claude/skills/`.

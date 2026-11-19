---
name: update-external-artifacts
description: Use when asked to update or bump bundled versions, or to check for newer models, tools or libraries in Thoreau (Kokoro, Opus-MT, KaTeX, Mermaid, workflow parser, Simple Icons, Gradle wrapper, ty, TypeScript, jdtls, Lombok, Gradle libraries). Finds the latest upstream version, verifies checksums, changes the pin, runs the tests and reports.
---

# Updating external artifacts and libraries

The inventory with pins, sources and tests is `docs/external-artifacts.md`: read its "Overview" table first and the
section of each artifact you bump (not the whole page), and update its rows with every bump. One commit per artifact
(a library family counts as one). Never push.

Sandbox network: a host may be blocked (HTTP 403 with an explanation). Read the body and follow the sandbox's
"Network access" instructions; hosts used here: `api.github.com`, `github.com`, `objects.githubusercontent.com`,
`registry.npmjs.org`, `huggingface.co`, `services.gradle.org`, `repo1.maven.org`, `plugins.gradle.org`,
`packages.jetbrains.team` (JediTerm), `raw.githubusercontent.com`.

Download into a fresh scratchpad directory per artifact, always `curl -L`, and compute `sha256sum` yourself; never
copy a hash from a page without comparing it to the file you downloaded.

## Part 1: runtime and vendored artifacts

Per bump: find latest, download and verify, change the pin, run tests, update the doc row, commit. The steps of each
artifact (where the latest version is, the pin, what to verify, the tests and the real-app check) are the "Update"
paragraph of its section in `docs/external-artifacts.md`: read only the sections of what you bump. A `:module` target
of `verify.sh` runs unit tests only; name the UI test classes.

## Part 2: Gradle libraries

1. Read `gradle/libs.versions.toml`. For each `[versions]` entry find the latest stable release: libraries via
   `https://repo1.maven.org/maven2/<group with / for .>/<artifact>/maven-metadata.xml` (`<release>`; skip alpha, beta,
   RC and milestones unless already on one), plugins via
   `https://plugins.gradle.org/m2/<plugin id with / for .>/<id>.gradle.plugin/maven-metadata.xml`.
   Also the `[plugins]` entries with an inline version (spotless, `org.openjfx.javafxplugin`). Skip `java = "25"`
   (the toolchain is a separate decision). Exception: JediTerm (`org.jetbrains.jediterm`) is not on Maven Central, only
   in `https://packages.jetbrains.team/maven/p/ij/intellij-dependencies` (`settings.gradle.kts`, ADR 0007); read that
   repository's `maven-metadata.xml` instead.
2. Entries without a version follow the Spring Boot BOM (Jackson, snakeyaml, JDBC drivers, JUnit): bump Spring Boot,
   not them.
3. Read the release notes of every bump with a major or minor change; look for removed APIs and changed defaults.
   Skip and report a bump you cannot judge.
4. One commit per bump; related libraries together (a BOM family such as commonmark, ikonli, jna with pty4j).
5. After each: `.claude/scripts/verify.sh :modules:<user of the library>` (grep the module builds for
   `libs.<alias>`). The main session runs `.claude/scripts/full-build.sh` once at the end.
6. Special cases:
   - JavaFX (`javafx`, plugin 0.1.0): ADR 0003; apply the plugin, never declare `org.openjfx:*`; real-app check.
   - ONNX Runtime: `ide/build.gradle.kts` swaps in a jar with the build platform's natives; check
     `./gradlew :ide:bootJar` still lists the swapped jar. Real-app check of Read Aloud and Translate.
   - Lingua: `ide/build.gradle.kts` drops the quadri- and fivegram models; check the file names still match.
   - RichTextFX and RSyntaxTextArea: the editor; run `:modules:text-editing` and `:modules:file-viewers`, real-app check.
   - lsp4j (language servers): run the tests of `language-servers` and of the language modules that use it
     (`python`, `typescript`).
   - pty4j and jediterm: keep `jna` equal to the JNA pty4j resolves (comment in the toml).
   - Spring Boot, Kotlin, Spotless: run all unit tests of a few modules, then leave the rest to the full build.

## Part 3: report
Briefly: bumped (old to new, commit each), skipped and why (pre-release, unknown breakage, blocked host), checks run
(verify.sh command and last line), what still needs a real-app or macOS check (previews, Read Aloud, Translate,
Databases logos, JavaFX, terminal, CI actions), facts you could not verify.

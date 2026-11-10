---
name: update-external-artifacts
description: Use when asked to update or bump bundled versions, or to check for newer models, tools or libraries in Thoreau (Kokoro, Opus-MT, KaTeX, Mermaid, workflow parser, Simple Icons, Gradle wrapper, ty, Gradle libraries). Finds the latest upstream version, verifies checksums, changes the pin, runs the tests and reports.
---

# Updating external artifacts and libraries

The inventory with pins, sources and tests is `docs/external-artifacts.md`: read it first, and update its rows with
every bump. One commit per artifact (a library family counts as one). Never push.

Sandbox network: a host may be blocked (HTTP 403 with an explanation). Read the body and follow the "Network access"
section of the project CLAUDE.md; hosts used here: `api.github.com`, `github.com`, `objects.githubusercontent.com`,
`registry.npmjs.org`, `huggingface.co`, `services.gradle.org`, `repo1.maven.org`, `plugins.gradle.org`,
`packages.jetbrains.team` (JediTerm), `raw.githubusercontent.com`.

Download into a fresh scratchpad directory per artifact, always `curl -L` (a 169-byte "zip" is a redirect page), and
compute `sha256sum` yourself; never copy a hash from a page without comparing it to the file you downloaded.

## Part 1: runtime and vendored artifacts

Per bump: find latest, download and verify, change the pin, run tests, update the doc row, commit.

- **Kokoro** (`KokoroVariant.kt`): `curl -sL https://api.github.com/repos/thewh1teagle/kokoro-onnx/releases` lists
  releases (the model files live in a `model-files-*` release, not `releases/latest`). Download the four files,
  `sha256sum` and `stat -c %s` them, change `RELEASE`, bytes and sha256. Vocab: fetch `src/kokoro_onnx/config.json` at
  the latest commit
  (`https://api.github.com/repos/thewh1teagle/kokoro-onnx/commits?path=src/kokoro_onnx/config.json&per_page=1`), compare
  its `vocab` map with `kokoro-vocab.json`, update the file and `NOTICE-kokoro-onnx.txt`. Tests:
  `.claude/scripts/verify.sh :modules:speech SpeechSettingsPageUiTest` (a `:module` target runs unit tests only; name UI
  test classes). Real-app check: Read Aloud after clicking the download (`run-app.sh`,
  `docs/testing.md`, "link `~/.sluide/models/kokoro-v1.0`").
- **Opus-MT**: run `modules/translation/scripts/generate-opus-mt-catalog.sh` (Hugging Face API, needs curl, jq,
  sha256sum), read the diff (new, removed or re-pinned pairs; a changed commit means changed files), spot-check one
  ONNX file against its catalog sha256. Tests: `verify.sh :modules:translation TranslationSettingsPageUiTest`. Real-app check:
  Translate with a downloaded pair.
- **KaTeX and Mermaid** (vendored): `curl -s https://registry.npmjs.org/<pkg>/latest` gives `version` and
  `dist.integrity`; download `dist.tarball`, compare its sha512 (`openssl dgst -sha512 -binary f.tgz | base64`) with
  `dist.integrity`, copy exactly the files listed in `vendor/README.md`, copy the tarball's `LICENSE` into
  `vendor/katex` or `vendor/mermaid`, confirm it is still permissive (MIT, ISC, Apache-2.0, BSD), update the README table
  including the licence column. Tests: `verify.sh :modules:previews MermaidPreviewUiTest PreviewFileViewUiTest
  ExportRendererUiTest` (the last two typeset with KaTeX). Real-app check: a Markdown
  file with a formula and a Mermaid diagram in the preview.
- **Workflow parser bundle**: follow `modules/previews/src/main/js/workflow-parser/README.md`, "Updating versions"
  (`npm view`, exact versions, `./build.sh`, `node check.mjs --compare`, licence check, vendor README versions and
  bundle sha256). `npm ci` verifies the lockfile hashes. Tests: `verify.sh WebViewWorkflowParserUiTest
  WorkflowPreviewUiTest WorkflowPreviewTest`. Real-app check: a workflow file in the preview.
- **Simple Icons**: latest tag via `https://api.github.com/repos/simple-icons/simple-icons/releases/latest`; download
  `icons/postgresql.svg` and `icons/mariadb.svg` from the raw URL in `DbIcons.kt` with the new tag, hash both, update
  `postgresql.path`, `mariadb.path` and the comment. Tests: `verify.sh :modules:databases ConnectionCardListUiTest`. Real-app check: the Databases tab logos.
- **Word lists**: skip unless asked; they are hand-curated (see the NOTICE file for the steps and sources).
- **Gradle wrapper**: latest via `https://services.gradle.org/versions/current` (`version`); sha256 from
  `https://services.gradle.org/distributions/gradle-<v>-bin.zip.sha256`, confirmed against a downloaded zip. Then
  `./gradlew wrapper --gradle-version <v> --gradle-distribution-sha256-sum <sha>`, run the same task a second time
  (the first run still uses the old wrapper jar and scripts; the second refreshes them), check that
  `gradle-wrapper.properties` keeps `distributionSha256Sum`, and verify `gradle/wrapper/gradle-wrapper.jar` against
  `https://services.gradle.org/distributions/gradle-<v>-wrapper.jar.sha256`. The main session runs the full build.
- **ty**: latest via `https://api.github.com/repos/astral-sh/ty/releases/latest` (`tag_name`; skip a
  `prerelease`). The pin is `modules/python/src/main/resources/dev/sluide/python/ty-release.properties`: `version`
  and per Thoreau platform (`linux-x64`, `linux-aarch64`, `mac-aarch64`) `<platform>.asset`, `.bytes`, `.sha256`.
  For each of the three assets: download it into a fresh folder (`curl -L`), compare its `sha256sum` with the
  asset's `.sha256` file and the API asset's `digest`, take `.bytes` from the API `size` (and `stat`), and check
  that the archive still holds `ty-<target>/ty` (`tar -tzf`). Update the properties and the ty section of
  `docs/external-artifacts.md` (version, sizes). Tests: `verify.sh :modules:python` (`TyToolTest`; run
  `TySmokeTestTest` with `SLUIDE_TEST_TY=<unpacked ty>` for the real smoke test).
  Real-app check: Settings → Languages → Python → Install, then open a Python file.
- **CI actions**: check the latest major of each action in `.github/workflows/*.yml` (GitHub `releases/latest` of the
  action's repo); edit all workflow files together. Cannot be tested here; say so in the report.
- **espeak-ng** is user-installed; nothing to bump.

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
   - lsp4j (language servers, added in the current round): bump with the python and other language-server modules'
     tests; check the version exists in `libs.versions.toml` first.
   - pty4j and jediterm: keep `jna` equal to the JNA pty4j resolves (comment in the toml).
   - Spring Boot, Kotlin, Spotless: run all unit tests of a few modules, then leave the rest to the full build.

## Part 3: report
Briefly: bumped (old to new, commit each), skipped and why (pre-release, unknown breakage, blocked host), checks run
(verify.sh command and last line), what still needs a real-app or macOS check (previews, Read Aloud, Translate,
Databases logos, JavaFX, terminal, CI actions), facts you could not verify.

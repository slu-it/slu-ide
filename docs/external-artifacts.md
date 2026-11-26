# External artifacts

Everything Thoreau bundles in the repository or fat jar, or downloads at runtime, besides Gradle libraries (those are
in `gradle/libs.versions.toml`, see the last section). Update procedure: skill `update-external-artifacts`
(`.claude/skills/update-external-artifacts/SKILL.md`); the steps of each artifact are the "Update" paragraph of its
section here, the libraries are part 2 of the skill.
Facts were checked against the repository on 2026-11-07; items marked "unverified" were not.

## Overview

| Artifact | Version | Kind | Pin location | Integrity |
|---|---|---|---|---|
| Kokoro models fp32/fp16/int8, voices file | release `model-files-v1.1` (model `v1.0`) | download on click | `modules/speech/src/main/kotlin/dev/sluide/speech/install/KokoroVariant.kt` | bytes + sha256 per file, same file |
| Kokoro vocab | kokoro-onnx commit `46417b4` | bundled resource | `modules/speech/src/main/resources/dev/sluide/speech/kokoro/NOTICE-kokoro-onnx.txt` | commit id only |
| Opus-MT models (Xenova) | per pair, commit per repo | download on click | `modules/translation/src/main/resources/dev/sluide/translation/opus-mt-catalog.tsv` | bytes + sha256 per file, same file |
| KaTeX (+ woff fonts) | 0.18.9 | vendored | `modules/previews/src/main/resources/dev/sluide/previews/vendor/README.md` | npm sha512 in README |
| Mermaid | 11.17.2 | vendored | same README | npm sha512 in README |
| Workflow parser bundle | @actions/workflow-parser 0.3.61, esbuild 0.28.2 | built, vendored | `modules/previews/src/main/js/workflow-parser/package.json` + `package-lock.json` | lockfile `integrity`; bundle sha256 in vendor README |
| Simple Icons path data (postgresql, mariadb) | 14.0.0 | vendored | comment of `modules/databases/src/main/kotlin/dev/sluide/databases/ui/cards/DbIcons.kt` | sha256 of both SVGs in that comment |
| Word Guess word lists | ENABLE2K, SCOWL commit `1e5b7d3`, german-wordlist commit `1071d87`, Leipzig news 2023 | bundled text | `modules/games/src/main/resources/dev/sluide/games/wordguess/NOTICE-word-lists.txt` | sha256 per source in that file |
| Gradle wrapper | 9.8.1 | build tool | `gradle/wrapper/gradle-wrapper.properties`, vendored `gradle/wrapper/gradle-wrapper.jar` | `distributionSha256Sum`; jar: `gradle-<v>-wrapper.jar.sha256` |
| espeak-ng | user's installation | external, never bundled | ADR 0124; `modules/speech/src/main/kotlin/dev/sluide/speech/kokoro/EspeakLocator.kt` | none |
| ty (Python language server) | 0.0.99 | download on click (Settings → Languages → Python) | `modules/python/src/main/resources/dev/sluide/python/ty-release.properties` | bytes + sha256 per platform asset, same file (checked against the `.sha256` files and the GitHub API `digest`); updates: GitHub's `digest` and `size` at runtime |
| TypeScript language server | 7.0.2 | download on click (Settings → Languages → TypeScript) | `modules/typescript/src/main/resources/dev/sluide/typescript/typescript-release.properties` | bytes + npm SRI `sha512` integrity per platform package tarball, same file (compared with a recomputed sha512 of the download); updates: the registry's `dist.integrity` and the tarball size at runtime |
| jdtls (Java language server) | 1.63.0 | download on click (Settings → Languages → Java) | `modules/java/src/main/resources/dev/sluide/java/jdtls-release.properties` | bytes + sha256 of the one archive for every platform, same file (checked against the `.sha256` file beside it and a recomputed hash); updates: the `.sha256` file and the size at runtime |
| Lombok (agent jar for jdtls) | 1.18.48 | download on click, with jdtls | constants of `modules/java/src/main/kotlin/dev/sluide/java/LombokTool.kt` | bytes + sha256 in `LombokTool.PIN` (computed; the published sha1 was compared when the pin was made); no update check |
| CI tools | see CI section | CI | `.github/workflows/*.yml` | none |

## Kokoro text-to-speech (Read Aloud)
- Source: `https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.1/` (files `kokoro-v1.0.onnx`,
  `kokoro-v1.0.fp16.onnx`, `kokoro-v1.0.int8.onnx`, `voices-v1.0.bin`). Hosted by GitHub; no Hugging Face mirror used.
- Integrity: bytes and sha256 constants in `KokoroVariant.kt` (the enum and `KokoroVoicesFile`); the downloader checks
  them (`modules/local-models`).
- Vocab: `kokoro-vocab.json` is the `vocab` map of `src/kokoro_onnx/config.json` in kokoro-onnx, MIT; commit and fetch
  date in `NOTICE-kokoro-onnx.txt`. No hash recorded there.
- Licence: kokoro-onnx MIT (in the NOTICE file). Licence of the model weights: not stated in repo (unverified).

- Tests: `ModelStoreTest`, `KokoroVocabTest`, `KokoroVoicesTest`, `NpzVoicesTest`, `KokoroEngineTest`;
  `KokoroEngineRealTest` needs the real model (see `docs/testing.md`). Feature: `docs/features/read-aloud.md`.

**Update.** `curl -sL https://api.github.com/repos/thewh1teagle/kokoro-onnx/releases` lists releases (the model files
live in a `model-files-*` release, not `releases/latest`). Download the four files, `sha256sum` and `stat -c %s` them,
change `RELEASE`, bytes and sha256. Vocab: fetch `src/kokoro_onnx/config.json` at the latest commit
(`https://api.github.com/repos/thewh1teagle/kokoro-onnx/commits?path=src/kokoro_onnx/config.json&per_page=1`), compare
its `vocab` map with `kokoro-vocab.json`, update the file and `NOTICE-kokoro-onnx.txt`. Tests:
`.claude/scripts/verify.sh :modules:speech SpeechSettingsPageUiTest` (a `:module` target runs unit tests only; name UI
test classes). Real-app check: Read Aloud after clicking the download (`run-app.sh`, `docs/testing.md`, "link
`~/.sluide/models/kokoro-v1.0`").

## Opus-MT translation models
- Source: Hugging Face `Xenova/opus-mt-<src>-<tgt>` (ONNX conversions of Helsinki-NLP/opus-mt), pinned per repo to a
  commit in the `url` column; Medium is int8 (`*_quantized`), High fp32.
- Integrity: bytes and sha256 per file in `opus-mt-catalog.tsv` (columns pair, quality, path, url, bytes, sha256).
  ONNX sizes and hashes come from the Hugging Face API (Git LFS ids), small files are downloaded and hashed.
- Generated by `modules/translation/scripts/generate-opus-mt-catalog.sh` (needs curl, jq, sha256sum, huggingface.co);
  never edit the TSV by hand. A changed commit means changed model files: review the diff.
- Licence: CC-BY 4.0 (header of the TSV).

- Tests: `OpusMtCatalogTest`, `OpusMtEngineTest`, `PairSessionsTest`; `OpusMtEngineRealTest` with real models.
  Feature: `docs/features/translate.md`.

**Update.** Run `modules/translation/scripts/generate-opus-mt-catalog.sh` (Hugging Face API, needs curl, jq, sha256sum),
read the diff (new, removed or re-pinned pairs; a changed commit means changed files), spot-check one ONNX file against
its catalog sha256. Tests: `verify.sh :modules:translation TranslationSettingsPageUiTest`. Real-app check: Translate
with a downloaded pair.

## Preview libraries (vendored JavaScript)
Folder `modules/previews/src/main/resources/dev/sluide/previews/vendor/`, loaded by `Vendor.kt`; its `README.md` is the
record.
- Mermaid 11.17.2 (`dist/mermaid.min.js`), KaTeX 0.18.9 (`katex.min.js`, `katex.min.css`, `dist/fonts/*.woff`; woff2
  is not loaded from `data:` URIs by JavaFX WebKit). Source: npm registry tarballs; sha512 in the README table.
  Licence: MIT.
- Workflow parser bundle `workflow-parser/workflow-parser.min.js`: built with esbuild 0.28.2 from
  @actions/workflow-parser and @actions/expressions 0.3.61 (MIT), yaml 2.9.1 (ISC), cronstrue 2.59.0 (MIT). Bundle
  sha256 `856267e8...` in the README. `npm ci` checks each tarball against `package-lock.json`.
  Procedure: `modules/previews/src/main/js/workflow-parser/README.md`, "Updating versions".

- Tests: `MermaidPreviewTest`, `MermaidPreviewUiTest` (Mermaid); `PreviewFileViewUiTest`, `ExportRendererUiTest` (KaTeX,
  fonts; `MarkdownMathTest` is unit-only); `WorkflowPreviewTest`,
  `WebViewWorkflowParserUiTest`, `WorkflowPreviewUiTest`.

**Update (KaTeX and Mermaid).** `curl -s https://registry.npmjs.org/<pkg>/latest` gives `version` and `dist.integrity`;
download `dist.tarball`, compare its sha512 (`openssl dgst -sha512 -binary f.tgz | base64`) with `dist.integrity`, copy
exactly the files listed in `vendor/README.md`, copy the tarball's `LICENSE` into `vendor/katex` or `vendor/mermaid`,
confirm it is still permissive (MIT, ISC, Apache-2.0, BSD), update the README table including the licence column. Tests:
`verify.sh :modules:previews MermaidPreviewUiTest PreviewFileViewUiTest ExportRendererUiTest` (the last two typeset with
KaTeX). Real-app check: a Markdown file with a formula and a Mermaid diagram in the preview.

**Update (Workflow parser bundle).** Follow `modules/previews/src/main/js/workflow-parser/README.md`, "Updating
versions" (`npm view`, exact versions, `./build.sh`, `node check.mjs --compare`, licence check, vendor README versions
and bundle sha256). `npm ci` verifies the lockfile hashes. Tests: `verify.sh WebViewWorkflowParserUiTest
WorkflowPreviewUiTest WorkflowPreviewTest`. Real-app check: a workflow file in the preview.

## Simple Icons (database logos)
- Version 14.0.0, CC0 1.0; `icons/postgresql.svg` and `icons/mariadb.svg` from
  `https://raw.githubusercontent.com/simple-icons/simple-icons/14.0.0/icons/<name>.svg`; the sha256 of both is in the
  comment of `DbIcons.kt`. The path data lives in `postgresql.path` and `mariadb.path` in
  `modules/databases/src/main/resources/dev/sluide/databases/ui/cards/`.

- Tests: `ConnectionCardListUiTest` (cards call `DbIcons.logo`, which fails on missing path data).

**Update.** Latest tag via `https://api.github.com/repos/simple-icons/simple-icons/releases/latest`; download
`icons/postgresql.svg` and `icons/mariadb.svg` from the raw URL in `DbIcons.kt` with the new tag, hash both, update
`postgresql.path`, `mariadb.path` and the comment. Tests: `verify.sh :modules:databases ConnectionCardListUiTest`.
Real-app check: the Databases tab logos.

## Word Guess word lists
- Four files (`en-allowed`, `en-answers`, `de-allowed`, `de-answers`), documented source by source with URL, commit,
  sha256, derivation steps and licence in `NOTICE-word-lists.txt`: ENABLE2K (public domain), SCOWL/ESDB commit
  `1e5b7d3a...` (permissive notice), enz/german-wordlist commit `1071d87d...` (CC0), Leipzig Corpora Collection
  German news 2023 (CC BY, used only to rank words).
- The lists are hand-curated; an update means redoing the filtering steps, so it is rarely worth it: skip them
  unless asked.
- Tests: `WordListTest`.

**Update.** Skip unless asked; they are hand-curated (see the NOTICE file for the steps and sources).

## Gradle wrapper
- 9.8.1, `distributionSha256Sum` in `gradle/wrapper/gradle-wrapper.properties`, URL `services.gradle.org`. The
  vendored `gradle/wrapper/gradle-wrapper.jar` matches `https://services.gradle.org/distributions/gradle-9.8.1-wrapper.jar.sha256`.


**Update.** Latest via `https://services.gradle.org/versions/current` (`version`); sha256 from
`https://services.gradle.org/distributions/gradle-<v>-bin.zip.sha256`, confirmed against a downloaded zip. Then
`./gradlew wrapper --gradle-version <v> --gradle-distribution-sha256-sum <sha>`, run the same task a second time (the
first run still uses the old wrapper jar and scripts; the second refreshes them), check that `gradle-wrapper.properties`
keeps `distributionSha256Sum`, and verify `gradle/wrapper/gradle-wrapper.jar` against
`https://services.gradle.org/distributions/gradle-<v>-wrapper.jar.sha256`. The main session runs the full build.

## espeak-ng
Not bundled and not pinned: GPL, so it runs as a separate process the user installs (ADR 0124, `EspeakLocator.kt`,
tests `EspeakLocatorTest`, `EspeakTest`). Phoneme flags were verified with espeak-ng 1.52. Nothing to update, but a
new major version is worth a check of `docs/adr/0124-read-aloud-speech.md` ("Phonemes").

**Update.** Nothing to bump, it is user-installed.

## ty (Python language server)
- Source: GitHub releases of `astral-sh/ty`,
  `https://github.com/astral-sh/ty/releases/download/<version>/<asset>` (redirects to
  `release-assets.githubusercontent.com`). Version 0.0.99, pinned on 2026-11-07.
- Platforms and assets (the static musl builds on Linux): linux-x64 `ty-x86_64-unknown-linux-musl.tar.gz`
  (14,839,660 bytes), linux-aarch64 `ty-aarch64-unknown-linux-musl.tar.gz` (13,659,057 bytes), mac-aarch64
  `ty-aarch64-apple-darwin.tar.gz` (13,342,913 bytes). No pin for macOS on Intel or Windows: the page says "No ty
  build for this platform" and a custom path still works.
- Integrity: `ty-release.properties` holds `version` and per platform `<platform>.asset`, `.bytes`, `.sha256`; each
  sha256 was compared with the asset's `.sha256` file, the GitHub API's asset `digest` and a downloaded copy. The
  installer (`ToolInstaller`, ADR 0143) checks size and sha256, then runs a smoke test (`ty version`, an LSP
  `initialize`) before it switches. An update the user installs from Settings uses the `size` and `digest` of the
  GitHub API's `releases/latest` answer instead (ADR 0144).
- Installed under `~/.sluide/tools/ty/<version>/`, the previous version kept for rollback.
- Licence: MIT (GitHub repository licence of `astral-sh/ty`).
- Update at runtime: Settings → Languages → Python → Check for Updates (installs for this user only); the pin: the
  "Update" paragraph below.
- Tests: `TyToolTest` (reads the pin), `TyInstallerTest`, `TyUpdatesTest` (recorded GitHub answers),
  `TySmokeTestTest` (its real-ty case runs when ty is installed or `SLUIDE_TEST_TY` names one). Feature:
  `docs/features/python.md`; decision: ADR 0144.

**Update.** Latest via `https://api.github.com/repos/astral-sh/ty/releases/latest` (`tag_name`; skip a `prerelease`).
The pin is `modules/python/src/main/resources/dev/sluide/python/ty-release.properties`: `version` and per Thoreau
platform (`linux-x64`, `linux-aarch64`, `mac-aarch64`) `<platform>.asset`, `.bytes`, `.sha256`. For each of the three
assets: download it into a fresh folder (`curl -L`), compare its `sha256sum` with the asset's `.sha256` file and the API
asset's `digest`, take `.bytes` from the API `size` (and `stat`), and check that the archive still holds
`ty-<target>/ty` (`tar -tzf`). Update the properties and this section (version,
sizes). Tests: `verify.sh :modules:python` (`TyToolTest`; run `TySmokeTestTest` with `SLUIDE_TEST_TY=<unpacked ty>` for
the real smoke test). Real-app check: Settings → Languages → Python → Install, then open a Python file.

## TypeScript language server
- Source: the npm registry, one package per platform (the native server is not in the `typescript` package):
  `https://registry.npmjs.org/@typescript/typescript-<os>-<arch>/-/typescript-<os>-<arch>-<version>.tgz`. Version
  7.0.2, pinned on 2026-11-10.
- Platforms and packages: linux-x64 `@typescript/typescript-linux-x64` (9,669,334 bytes), linux-aarch64
  `@typescript/typescript-linux-arm64` (8,900,516 bytes), mac-aarch64 `@typescript/typescript-darwin-arm64`
  (9,270,373 bytes). The asset name is the tarball's file name and carries the version
  (`typescript-linux-arm64-7.0.2.tgz`). No pin for macOS on Intel or Windows: the page says "No TypeScript build for
  this platform" and a custom path still works.
- Integrity: `typescript-release.properties` holds `version` and per platform `<platform>.asset`, `.bytes`,
  `.integrity` (npm's `dist.integrity`, `sha512-<base64>`); each was compared with a sha512 recomputed from a
  downloaded copy (`openssl dgst -sha512 -binary | base64`). The installer checks size and sha512, then runs a smoke
  test (`tsc --version` printing `Version …`, an LSP `initialize` with `tsc --lsp --stdio`) before it switches. An
  update from Settings looks up `dist.tarball` and `dist.integrity` of the platform package's version (the tarball must
  be exactly the registry URL of that package and version) and takes the size from the `Content-Range` total of a
  ranged `GET` (`Range: bytes=0-0`), because npm publishes no size.
- The archive is a gzip tar with entries under `package/`; the executable is `package/lib/tsc`, and the `lib.*.d.ts`
  files beside it must stay. It runs without Node.js and with an empty environment.
- Installed under `~/.sluide/tools/typescript/<version>/`, the previous version kept for rollback.
- Licence: Apache-2.0 (the packages' `license` field).
- Update at runtime: Settings → Languages → TypeScript → Check for Updates (latest from the `latest` dist-tag at
  `https://registry.npmjs.org/-/package/typescript/dist-tags`; installs for this user only); the pin: the "Update" paragraph below.
- Tests: `TypeScriptToolTest` (reads the pin), `NpmUpdatesTest` (fake registry answers), `TypeScriptSmokeTestTest` (its
  real-tsc case runs when tsc is installed or `SLUIDE_TEST_TSC` names one), `TypeScriptSettingsPageUiTest`.

**Update.** Latest via `https://registry.npmjs.org/-/package/typescript/dist-tags` (`latest`; a few hundred bytes, where
the package document is megabytes). The pin is
`modules/typescript/src/main/resources/dev/sluide/typescript/typescript-release.properties`: `version` and per Thoreau
platform `<platform>.asset` (`typescript-<os>-<arch>-<version>.tgz`), `.bytes`, `.integrity`. Per platform package
(`@typescript/typescript-linux-x64`, `-linux-arm64`, `-darwin-arm64`): fetch
`https://registry.npmjs.org/@typescript/typescript-<os>-<arch>/<version>` for `dist.tarball` and `dist.integrity`,
download the tarball into a fresh folder (`curl -L`), recompute `openssl dgst -sha512 -binary f.tgz | base64` and
compare it with `dist.integrity`, take `.bytes` from `stat`, and check that the archive still holds `package/lib/tsc`
(`tar -tzf`). Update the properties and this section (version, sizes, date).
Tests: `verify.sh :modules:typescript` with `SLUIDE_TEST_TSC=<unpacked package/lib/tsc>` for the real smoke test.
Real-app check: Settings → Languages → TypeScript → Install, then open a TypeScript file.

## jdtls (Java language server)
- Source: the Eclipse milestones, `https://download.eclipse.org/jdtls/milestones/<version>/<asset>` (no redirect). Version
  1.63.0, pinned on 2026-11-15.
- Asset: one archive for every platform, `jdt-language-server-1.63.0-202610191322.tar.gz` (51,143,565 bytes), listed under
  linux-x64, linux-aarch64 and mac-aarch64 in `jdtls-release.properties`. No pin for macOS on Intel or Windows: the page says
  "No jdtls build for this platform" and a custom path (the `bin/jdtls` of an unpacked jdtls) still works.
- Integrity: the pin holds `.bytes` and `.sha256`; the sha256 is the `<asset>.sha256` file beside the archive (a bare
  64-hex hash), compared with a recomputed `sha256sum` of a downloaded copy. The installer checks size and sha256, then
  runs a smoke test (one LSP `initialize` against jdtls started as Thoreau's own `java`, on a throwaway configuration
  folder below `~/.sluide/jdtls/smoke` (deleted before and after the run, never through links, so that nothing planted
  there is loaded and the unpacked install stays unchanged; there is no version command) before it switches. Install
  of a jdtls that is there installs only a missing or outdated Lombok.
- The archive holds `bin/jdtls` (a Python launcher Thoreau never runs; it is the install's marker "executable"),
  `plugins/org.eclipse.equinox.launcher_<version>.jar`, `config_linux`, `config_linux_arm`, `config_mac`,
  `config_mac_arm` and others, `features/`.
- Installed under `~/.sluide/tools/jdtls/<version>/`, the previous version kept for rollback.
- Licence: EPL-2.0.
- Update at runtime: Settings → Languages → Java → Check for Updates: the index `https://download.eclipse.org/jdtls/milestones/`
  (an HTML table; versions compared as numbers; `repository` skipped), `<version>/latest.txt` (names the tarball),
  `<tarball>.sha256` and the `content-length` of a `HEAD` for the size; the pin: the "Update" paragraph below.
- Tests: `JdtlsToolTest` (reads the pin), `EclipseMilestonesTest` (fixtures from the real answers), `JdtlsLaunchTest`,
  `JdtlsInstallerTest`, `JdtlsSmokeTestTest` (runs with `SLUIDE_TEST_JDTLS=<unpacked jdtls folder>`),
  `JavaSettingsPageUiTest`.

**Update.** The index `https://download.eclipse.org/jdtls/milestones/` is an HTML table with one row per version folder
(`<a href='/jdtls/milestones/1.63.0'>`); versions are not in numeric order (compare as numbers), skip `repository`.
`<version>/latest.txt` names the tarball (`jdt-language-server-<version>-<timestamp>.tar.gz`), the `<tarball>.sha256`
file is the bare 64-hex hash; the size is the `content-length` of `curl -sIL` on the tarball. The pin is
`modules/java/src/main/resources/dev/sluide/java/jdtls-release.properties`: `version` and for each of the three
platforms the same `<platform>.asset`, `.bytes`, `.sha256`. Download the tarball into a fresh folder (`curl -L`),
compare `sha256sum` with the `.sha256` file, take `.bytes` from `stat`, and check that the archive still holds
`bin/jdtls`, `plugins/org.eclipse.equinox.launcher_*.jar` (exactly one) and `config_linux`, `config_linux_arm`,
`config_mac`, `config_mac_arm` (`tar -tzf`). Do not use the `www.eclipse.org/downloads/download.php` mirror links of the
version page. Update the properties and this section (version, sizes, date). Tests:
`verify.sh :modules:java` with `SLUIDE_TEST_JDTLS=<the unpacked folder>` for the real smoke test. Real-app check:
Settings → Languages → Java → Install, then open a Java file.

## Lombok (agent jar for jdtls)
- Source: Maven Central,
  `https://repo1.maven.org/maven2/org/projectlombok/lombok/<version>/lombok-<version>.jar`. Version 1.18.48, pinned on
  2026-11-15: 2,055,065 bytes, sha256 `85477a4655ebb2c074a9099cfb749be454449fee564d4282610df1b85f7c508b`.
- Integrity: Maven Central publishes no sha256. The pin's was computed from a download and the published `.jar.sha1`
  (`6858f13541bab505384f07053c5a7b539bbfd3e3`) was compared with it when the pin was made. The pin is `LombokTool.PIN`; the
  installer checks size and sha256, its smoke test checks that the jar is a regular file of the pinned size.
- Installed under `~/.sluide/tools/lombok/<version>/lombok-<version>.jar` with jdtls (after jdtls worked; a failure
  leaves jdtls installed and shows in the page's status line), deleted with it. jdtls gets it as `-javaagent` only when
  an imported build uses Lombok (ADR 0179). No update check of its own.
- Licence: MIT.

- Tests: `LombokToolTest`, `JdtlsInstallerTest`.

**Update.** Latest via `https://repo1.maven.org/maven2/org/projectlombok/lombok/maven-metadata.xml` (`<release>`). The
pin is `LombokTool.PIN` in `modules/java/src/main/kotlin/dev/sluide/java/LombokTool.kt` (version, URL, bytes, sha256).
Maven Central publishes no sha256: download `lombok-<version>.jar` into a fresh folder (`curl -L`), compute `sha256sum`,
and compare the `.jar.sha1` file Maven Central publishes with `sha1sum` of the download; take the bytes from `stat`.
Update the constants and this section (and the Lombok version in ADR 0179's text
only if it names one). Tests: `verify.sh LombokToolTest JdtlsInstallerTest`.

## CI
Versions are major tags, not hashes: `actions/checkout@v6`, `actions/setup-java@v6` (Temurin, Java 25),
`gradle/actions/setup-gradle@v6`, `actions/upload-artifact@v7` in `.github/workflows/ci.yml`, `release.yml`,
`macos-ui-tests.yml` (some in only one or two of them). 

**Update.** Check the latest major of each action in `.github/workflows/*.yml` (GitHub `releases/latest` of the action's
repo); edit all workflow files together. Cannot be tested here; say so in the report.

## Gradle libraries
Versions in `gradle/libs.versions.toml`, resolved from Maven Central or the plugin portal; no hash pins
(`gradle/verification-metadata.xml` does not exist). JediTerm is the exception to the host: it comes only from
`https://packages.jetbrains.team/maven/p/ij/intellij-dependencies` (`settings.gradle.kts`, ADR 0007), not Maven Central. Entries without a version follow the Spring Boot BOM. Update:
part 2 of the skill. Special cases:
- ONNX Runtime 1.31.1: the jar holds the natives of all platforms; `ide/build.gradle.kts` swaps in a jar with the build
  platform's natives (ADR 0124, ADR 0126). Check the swap when the native folder layout changes.
- JavaFX 27 and the `org.openjfx.javafxplugin` 0.1.0: ADR 0003; never declare `org.openjfx:*` directly.
- pty4j 0.14.0 with the JNA it resolves (5.19.1) and jediterm 3.76: terminal (ADR 0007); keep one JNA copy.
- lsp4j 1.0.0: language servers (ADR 0142); its Gson (range `[2.9.1,3.0)`) comes from the Spring Boot BOM
  (2.13.2). Check the range when either side moves.
- PlantUML (`plantuml-mit`) 1.2026.10: the MIT build, without ELK layout, `<latex>`, JCCKit and QR codes; its stdlib
  (C4, icons, themes) is inside the jar, nothing is downloaded (ADR 0193). After a bump run the `plantuml` module's tests:
  they pin PlantUML's error texts, the SANDBOX refusals and the stdlib that must keep working.
- Lingua 1.2.2: `ide/build.gradle.kts` drops the quadri- and fivegram models from the fat jar (ADR 0126).
- Ikonli 12.4.0, RichTextFX 0.11.7, RSyntaxTextArea 4.0.1, commonmark 0.30.0, jsoup, ec4j, Kotlin, Spring Boot,
  Spotless/ktlint, TestFX: plain version bumps.

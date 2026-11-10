# 0159. Shared server-tool layer and checksum algorithms

- Status: accepted; amends [0143](0143-tool-installs.md) (checksums by algorithm) and
  [0144](0144-python-via-ty.md) (ty's install, update check, smoke test and page move to `server-tools`); see
  [0142](0142-language-server-foundation.md), [0047](0047-module-dependencies-and-shared-modules.md),
  [0055](0055-sub-packages-within-modules.md), [0084](0084-testability-logic-out-of-controls.md)
- Date: 2026-11-10

## Context
TypeScript code intelligence runs the TypeScript 7 native server, a binary in npm's per-platform package
`@typescript/typescript-<os>-<arch>`. Thoreau installs, updates, rolls back and checks it exactly as it does ty
(ADR 0144): a pinned version on Install, newer ones on Check for Updates and Update, a smoke test before the switch,
a custom path, and a Settings → Languages page. All of that lived in `python`, written for ty, and a second language
module must not depend on `python` or copy it (ADR 0047). npm also pins its tarballs differently: `dist.integrity` is
a Subresource Integrity value, `sha512-<base64>`, while `local-models` only knew a SHA-256 in hex.

## Decision
- **Checksums by algorithm** (`local-models`): `Checksum` is `Sha256(hex)` (compared in any case) or
  `Sri(DigestAlgorithm, base64)`, read by `Checksum.sri("sha512-…")` (sha256, sha384, sha512; one hash, no options).
  `ModelFile` and `ToolRelease` carry a `Checksum` instead of a `sha256` string, with a constructor taking the hex for
  the existing callers. `ModelDownloader` digests with the checksum's algorithm; `ToolInstaller` refuses a checksum
  that is not `wellFormed` before the download ("The checksum of version 1.0 is not a SHA-512 value").
- **Module `server-tools`** (package `dev.sluide.servertools`, a foundation library without Spring beans, flat),
  depending on `language-servers`, `local-models` and `settings` (API) and `background-tasks`. A language module
  configures it and extends its classes as its own beans:
  - `ServerTool`: the tool's name (in every text: `ty`, `TypeScript`), root folder, `ToolPin` and platform, archive
    format, the executable's path in an asset, the server arguments, the version arguments, the expected start of the
    version line, and the command name the smoke test's messages show (`` `tsc --version` ``; the name by default);
    it builds `ToolRelease`s and the task row names.
  - `ToolPin` and `ToolAsset`: the pin resource (`version`; per platform of `ToolPin.PLATFORMS`: `.asset`, `.bytes`
    and either `.sha256` or `.integrity`; both, or a value that is no digest of its algorithm, is a build error), with
    the download URL from a function of version, platform and asset name that the tool supplies;
    `ToolPin.platform(os.name, os.arch)`.
  - `ServerPathSetting(store, key)`: the custom executable in `settings.properties`.
  - `ServerToolInstaller` (`ServerInstaller` over a `ToolInstaller`): availability, the command with the tool's
    server arguments (a volatile copy for any thread, updated by an invalidation listener before the availability
    binding), the custom path, install, update, rollback, delete, `onCommandChanged`.
  - `UpdateSource` and `ToolUpdates`: a source answers the latest release (`LatestRelease.Release` with version,
    release notes, prerelease flag and a function that finds a platform's `ToolAsset`, called only when the version is
    newer) or `Failed`; `ToolUpdates` runs the check off the FX thread, refuses a version that cannot name a folder,
    compares with the installed version or the pin, and words network failures with the source's name. Shared
    helpers: `fetchText` (one `GET` as `User-Agent: Thoreau`, 15 s), `HttpAnswer`, `webPage`, `compareVersions`.
  - `LspSmokeTest`: the version command must print the expected prefix and exit 0, then the server must answer one
    `initialize`, with ty's timeouts and rules (ADR 0144).
  - `ServerToolSettingsPage` with its model (`ServerToolPageModel`, `ServerToolDialogs`) and `ServerToolPageTexts`
    (the language in the delete question, the heading, the closing note); the page's path is the subclass's.
  - Test fixtures `ServerToolFakes`: a real `ToolInstaller` over a fake download and a queued executor.
- **ty on it** (`python` keeps only what is ty's): `TyTool` (pin resource, GitHub download URL, `ty-<target>/ty`,
  `ty server`, `ty version` → `ty `), `TyInstaller`, `TyUpdates` with ty's GitHub source (the `digest`, the host and
  path check of the download URL, the rate-limit texts), `PythonSettings` (`python.ty.path`) and `PythonSettingsPage`
  (Languages → Python) as thin subclasses. Every text, file, key and request stays as it was.

## Consequences
- A second server tool is configuration plus an `UpdateSource` and a pin resource; its tests use `ServerToolFakes`.
- The shared behaviour is tested once, in `server-tools`, against a made-up tool `acme-ls`, so that no ty text is
  hard-coded there; `python` tests ty's pin, GitHub answers, names and command, and one wiring test of its page.
- `python` no longer depends on `background-tasks`; it depends on `server-tools` with `api`, since its beans extend
  that module's classes.
- Added to [../index.md](../index.md).

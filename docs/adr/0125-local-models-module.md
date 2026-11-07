# 0125. Local models: a foundation module for model downloads and sessions

- Status: accepted; amends [0124](0124-read-aloud-speech.md); amended by [0143](0143-tool-installs.md) (tool installs); see [0047](0047-module-dependencies-and-shared-modules.md), [0043](0043-git-log-fetch.md), [0060](0060-logging-conventions.md), [0061](0061-background-tasks.md)
- Date: 2026-11-05

## Context
Read Aloud ([ADR 0124](0124-read-aloud-speech.md)) downloads its Kokoro model on request (part files, sha256,
`ATOMIC_MOVE`, a stall watchdog, a cancellable task row) and keeps one ONNX session that loads on first use and
unloads when idle. All of it lived in the `speech` module. The Translate tool will run Opus-MT models locally and needs
the same: pinned files downloaded on an explicit click, a state per model for its setup notice and settings page, and
sessions that do not stay in memory forever. Depending on `speech` for that would couple two unrelated feature
modules; ADR 0047 says to extract the shared piece into a small module instead.

## Decision
- **New foundation module** `local-models` (package `dev.sluide.localmodels`), a plain library without Spring beans.
  It depends only on `background-tasks` (task rows, `NamedThreads`) and on JavaFX for the state properties
  (`javafx.base`) and `Platform.runLater` (`javafx.graphics`).
- **What it holds**, generic over a key type `K` (a Kokoro variant, a language pair):
  - `ModelFile` (path in the key's folder, URL, bytes, sha256), `ModelDownloadException`, `InstallState`.
  - `ModelSpecs<K>`: what a feature knows about its models (files, folder, installed, task row text); called on the
    FX thread and on worker threads, so thread-safe. A file path must stay inside the key's folder and name a file in
    it; the downloader and the installer refuse others.
  - `ModelDownloader`: the blocking download of the missing files with a `.part` file next to the target, size and
    sha256 check, `ATOMIC_MOVE`, progress over the missing bytes, a watchdog that ends a download after 30 s without
    data or on cancel. Only file paths are logged, never URLs (redirect URLs carry signatures). Its own HTTP client is
    created with the first download and shut down on close.
  - `ModelInstaller<K>`: one state per key on the FX thread, created lazily from disk; a download per key at a time on
    a virtual thread with a cancellable `BackgroundTasks` row renamed in 5 % steps; delete of a key's files and of stray
    part files (kept while any download runs), with a `keep` hook for files that keys share; after a download (also a
    cancelled or failed one) or a delete every state that is not downloading is read from disk again, so a shared file
    that one key's download brought completes another key; a failed key keeps its Failed state.
  - `IdleSession<T>`: serialized blocking use of one loaded value, loaded on first use, closed after an idle time on a
    named scheduled thread and at once on close. Callers use platform threads, since native calls pin virtual threads.
- **What stays in `speech`:** the Kokoro variants, the release URLs and checksums, the store folder
  `~/.sluide/models/kokoro-v1.0` (`ModelStore` is the `ModelSpecs<KokoroVariant>`), the shared voices file and its rule
  (a delete keeps it while another variant is installed or a download runs, `ModelStore.keptOnDelete`), the Spring bean
  `ModelInstaller` that wraps the generic one, and the variant switch of `KokoroEngine` (a holder inside its
  `IdleSession` that replaces the loaded model when the setting names another variant).
- The network rule is unchanged: downloads start only on an explicit action of the feature (ADR 0043).

## Consequences
- A second local-model feature gets download, install states and idle unloading without depending on `speech`, and
  speech's behaviour is unchanged (same files, task row texts, watchdog, idle unload).
- The installer's executor and dispatch are public constructor parameters with the real defaults, and
  `ModelDownloader` is open, so that tests in other modules can run the work in step and fake the download.
- A stray part file is now also removed when one Kokoro variant is deleted while another stays, not only with the last.
- Known race, accepted: a delete reads "a download runs" once, right before it deletes. A download that starts in the
  milliseconds between that check and the deletes can find a shared file present, skip it, and then lose it to the
  delete (it ends Not installed), or lose its new part file to the sweep (it fails with a readable message). Either
  way nothing wrong is installed, and a second Download fetches the file again.

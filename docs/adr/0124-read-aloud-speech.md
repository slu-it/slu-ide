# 0124. Read Aloud: local text-to-speech with Kokoro

- Status: accepted
- Date: 2026-11-04

## Context
The user wants to select text (part of a file or all of it) and have it read aloud in a tool tab. The speech must be
local: no cloud service, no account. It should start soon and stream: playback begins as soon as enough audio is
buffered to reach the end without a gap, not after the whole text is synthesized. While it speaks, the spoken
sentence and word are highlighted in the shown text, so every spoken char must map back to its offset there, also
for Markdown files whose markup is not read.

English comes first, but other languages and engines will follow (Piper for German, an OpenAI-compatible server), so
the reading must not depend on one engine.

Options compared for the first engine, all running the Kokoro-82M model:
- **ONNX Runtime Java with Kokoro, phonemes from the `espeak-ng` CLI:** one Maven dependency
  (`com.microsoft.onnxruntime:onnxruntime`) with natives for all desktop platforms in the jar. Kokoro needs phonemes
  as input; espeak-ng is GPL, so it runs as a separate process the user installs, never linked.
- **sherpa-onnx:** wraps Kokoro and espeak-ng in one library, but its JNI natives are published only as GitHub
  release files, not on Maven Central, so the build would download and pin them per platform itself.
- **A Python sidecar** (the user's prototype: torch, kokoro, managed with uv): gigabytes of dependencies, a Python
  toolchain on the user's machine, and a process lifecycle to manage.
- **Kokoro-FastAPI in Docker:** needs a running Docker, a large image, a container lifecycle and a local HTTP
  endpoint for a desktop feature.

## Decision
- **Engine:** ONNX Runtime Java (1.31.1) running Kokoro-82M, with phonemes from the `espeak-ng` CLI.
- **Module** `speech` (package `dev.sluide.speech`), in the editor family: the tool is an editor tab
  ([ADR 0110](0110-tools-menu.md)) and adds "Read Aloud" to the code editors' context menu
  ([ADR 0108](0108-code-editor-extensions-and-context-menu.md)).
- **Engine-neutral API** (`dev.sluide.speech.engine`): a `SpeechEngine` per language or model has static `voices`
  (known without the model), `problems()` (what keeps it from speaking: a missing model, a missing tool, something
  broken) and a blocking `synthesize(chunk, voice, speed)` that returns mono float samples with per-word timings.
  `SpeechEngines` collects all engine beans; a voice names its engine. Engines split chunks longer than their model's
  limit themselves.
- **Text preparation** (`dev.sluide.speech.text`, FX-free): `SpeechText.prepare` turns the shown text into a
  `SpokenText`, the spoken string plus the source offset of every spoken char. Whitespace collapses, a blank line is
  a hard break. For `.md` files the markup goes: front matter, fenced and indented code, HTML tags and comments,
  link targets and URLs are skipped; heading, emphasis, list, quote and table markers are removed; link text and
  image alt text are kept. A small line-based scanner does this rather than a Markdown parser, because it gives an
  exact offset for every char by construction, including escapes and table cell separators.
- **Sentence chunks:** `Sentences.chunks` splits the spoken text into sentences (with abbreviations, initials,
  decimals and ellipses before lowercase words that end none), the chunks a reading synthesizes one by one. A long
  first sentence is split at its first clause, so the first audio comes fast.
- **The model** is the kokoro-onnx GitHub release `model-files-v1.1`, the only source with published checksums and a
  `duration` output, pinned by URL and sha256 and stored in `~/.sluide/models/kokoro-v1.0/`. It is downloaded only on
  an explicit action (a Download button), so the rule "no background fetch" ([ADR 0043](0043-git-log-fetch.md)) holds
  and no exception is needed.
- **espeak-ng** runs as a separate process found on the login shell's `PATH` or at a set path; it is never linked or
  shipped.
- **Playback** goes through `javax.sound.sampled` (`SourceDataLine`): JavaFX Media plays files and URLs only and
  cannot play a growing PCM stream. [ADR 0123](0123-media-playback.md) rejected `javax.sound` only for decoding media
  files, which does not arise here. There is one playback app-wide, shared with the media player: starting a
  reading pauses a playing medium, and the other way round.
- **ONNX Runtime natives:** the fat jar and jpackage keep only the build platform's natives, as with JavaFX
  ([ADR 0003](0003-javafx-platform-jars.md), [ADR 0011](0011-native-app-packaging.md)).

## Consequences
- Reading aloud works offline once the model (325 MB in fp32) is downloaded and espeak-ng is installed; without them
  the tab shows what is missing and how to get it.
- New engines (other languages, a server) are added as `SpeechEngine` beans without touching the reading, the text
  preparation or the UI.
- Highlights map exactly back to the shown text, Markdown included. The Markdown cleanup covers the common syntax,
  not every CommonMark corner case (emphasis is paired per block, tables need a delimiter row).
- The app grows by ONNX Runtime's natives for one platform; the model stays outside the app.
- espeak-ng is a dependency the user installs (Homebrew, apt); its GPL does not reach Thoreau.
- The speech module's tests play audio: on Linux without a sound card its test tasks point ALSA at a null device,
  like the media module's ([testing](../testing.md), "Media playback").

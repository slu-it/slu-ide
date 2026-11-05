# 0126. Translate: local translation with Opus-MT and language detection with Lingua

- Status: proposed (accepted when the round lands); see [0124](0124-read-aloud-speech.md), [0125](0125-local-models-module.md), [0110](0110-tools-menu.md), [0108](0108-code-editor-extensions-and-context-menu.md), [0043](0043-git-log-fetch.md), [0066](0066-scratch-files.md), [0101](0101-shared-data-file.md), [0003](0003-javafx-platform-jars.md)
- Date: 2026-11-05

## Context
After Read Aloud the user wants a second local-model tool: translate text in a tab, input on the left and a read-only
translation on the right, with the same entry points as Read Aloud (Tools menu, the code editors' context menu, the
explorer for `.txt` and `.md` files). It must run locally (no cloud, no account), download models only when the user
asks, and detect the input language. The user prefers models with a clear provenance (a European or US university).

Translation models compared:
- **Opus-MT** (Language Technology group, University of Helsinki; CC-BY 4.0): Marian models of about 75M parameters,
  one per direction, more than a thousand pairs, fast on a CPU. Hugging Face has ONNX conversions (Xenova, the
  transformers.js author) in fp32 and int8.
- **NLLB-200** (Meta): 200 languages in one model, but CC-BY-NC (non-commercial) and 0.6–3.3B parameters.
- **M2M-100** (Meta, MIT): 100 languages, weaker than NLLB, 1.6 GB.
- **MADLAD-400** (Google, Apache 2.0) and **TranslateGemma** (Google, Gemma terms): billions of parameters; better as
  an engine behind a local LLM server than inside the app.
- **Argos Translate** and **Firefox Translations (Bergamot)**: CTranslate2 or Marian C++ runtimes without JVM bindings.

Language detection compared: **Lingua** (Apache 2.0, pure Kotlin, Maven Central, accurate on short texts; the jar holds
models for 75 languages, 80 MB), fastText `lid.176` (Meta, CC-BY-SA, needs a reader and a download), and none.

## Decision
- **Engine:** Opus-MT on ONNX Runtime Java (as Kokoro, ADR 0124), behind an engine-neutral `TranslationEngine`.
  Offered: every language with single-target Opus-MT ONNX exports to or from English; a pair without a direct model
  is chained through English (two models).
- **Quality** per translation: Medium (int8, the default) and High (fp32), each its own download per pair model.
- **Models** are pinned in a catalog resource (Hugging Face URL at a fixed commit, size, sha256 per file), downloaded
  through `local-models` (ADR 0125) on an explicit Download in the tab's notice, stored in
  `~/.sluide/models/opus-mt/<src>-<tgt>/`. After the download the translation starts by itself.
- **Detection:** Lingua in low-accuracy mode over the whole text, limited to the offered languages it knows. The fat
  jar keeps only its unigram, bigram and trigram models (about 5 MB of the 80 MB; the larger n-gram models are what
  high-accuracy mode adds).
- **Text:** sentences via `SentenceRanges` (`text-editing`), translated one by one with a per-window cache, so an edit
  retranslates only the changed sentences. With the Markdown toggle on (set from `.md`/`.markdown` file names),
  markup is kept verbatim and only the text is translated.
- **Typing** is debounced (1 s); other changes translate at once; a new run cancels the running one.
- **Module** `translation` (package `dev.sluide.translation`), editor family, like `speech`.
- **Remembered user-wide:** source, target and quality in the field `translation` of `shared.json` (ADR 0101); the text
  is never stored.
- **Runs without a click:** nothing reaches the network; detection and translation of text the user put in run
  locally.

Rejected:
- NLLB-200: non-commercial license, size.
- An LLM in the app: gigabytes per model; it stays possible as a server engine (see the ideas page).
- fastText detection: a second download and an own reader for a gain Lingua's small models already give.

## Consequences
- Translation works offline once a pair's models (about 110 MB per direction in Medium) are downloaded.
- The app grows by Lingua and its small models (a few MB) and no new natives (ONNX Runtime is already in).
- Opus-MT translates sentence by sentence without context across sentences; quality is below commercial services.
- The model attribution (OPUS-MT, CC-BY 4.0) is shown in Settings → Translation.

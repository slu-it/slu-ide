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
  is chained through English (two models). The engine reads each model's SentencePiece Unigram tokenizer from its
  `tokenizer.json` (unknown characters take the id of the `<unk>` token: the exports' `model.unk_id` 2 is ","), runs
  Hugging Face's beam search over optimum's merged decoder with its key/value cache, splits sentences longer than
  400 tokens at a clause, keeps at most two pair models loaded app-wide and unloads one after 5 minutes idle. The
  multi-target en-zh, en-ar and en-vi get their target token first (`>>cmn_Hans<<`, `>>ara<<`, `>>vie<<`).
- **Quality** per translation: Medium (int8, the default) and High (fp32), each its own download per pair model.
- **Models** are pinned in a catalog resource (Hugging Face URL at a fixed commit, size, sha256 per file), downloaded
  through `local-models` (ADR 0125) on an explicit Download in the tab's notice, stored in
  `~/.sluide/models/opus-mt/<src>-<tgt>/`. After the download the translation starts by itself.
- **Detection:** Lingua in low-accuracy mode over the first 10 000 characters of the sentences only; code, link
  targets and markup are left out (with them a short German note was not detected). Limited to the offered languages it
  knows (by ISO 639-1 code; fewer than two known means no answer). Low-accuracy mode reads the unigram, bigram and
  trigram models, never the quadri- and fivegrams (a test jar without them detects as before; trigrams alone were not
  enough, Swedish came out empty). The fat jar therefore keeps those three per language: 5.6 MB instead of 80 MB
  (`linguaSmallModels` in `ide/build.gradle.kts`, which jpackage inherits through `bootJar`). The answer is null when
  Lingua finds nothing or when the runner-up's confidence is above 0.9 of the best one's (Lingua's values are relative,
  the best is 1.0): "Guten Tag" 0.50, a German sentence 0.67, an English one 0.80, "The server" 0.92, "Hello world"
  0.96. The rule is not a short-text guard: the ratio settles by language closeness, so close languages that are both
  offered (Croatian and Bosnian, Indonesian and Malay, Bokmål and Danish near the edge) are never told apart,
  however long the text. Building a detector is cheap; the models load on the first detection into Lingua's
  JVM-wide static maps, shared by all detectors, and stay in memory for the session (40 languages: about 0.1 s and
  27 MB of heap, later detections instant).
- **Text:** sentences via `SentenceRanges` (`text-editing`), translated one by one with a per-window cache, so an edit
  retranslates only the changed sentences. With the Markdown toggle on (set from `.md`/`.markdown` file names),
  markup is kept verbatim and only the text is translated.
  In Markdown mode a paragraph that wraps over lines (also in a list item or block quote) is one stretch, so a
  sentence that wraps goes to the model whole; its line breaks become one space and the translated sentence stands on
  one line, which renders the same. A hard line break (two spaces or a backslash) ends the paragraph and is kept.
  Plain mode stays line by line.
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
- Translation works offline once a pair's models are downloaded: about 115 MB per pair in Medium, about 440 MB in
  High (64 pairs: 25 sources and 20 targets besides English).
- The fat jar grows by 33 MB (102.7 to 135.7 MB): Lingua's small models 5.6 MB, its dependencies fastutil 23.4 MB,
  kotlin-reflect 3.6 MB, moshi and okio 0.4 MB; no new natives (ONNX Runtime is already in). Lingua uses only
  `Object2FloatMap` and `Object2FloatOpenHashMap` of fastutil, but `fastutil-core` (6 MB) lacks the latter; a filtered
  fastutil jar would save about 22 MB and is left undone.
- Opus-MT translates sentence by sentence without context across sentences; quality is below commercial services.
- The model attribution (OPUS-MT, CC-BY 4.0) is shown in Settings → Translation.

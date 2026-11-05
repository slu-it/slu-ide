# Translate: left for later

Explored, not decided or built. The tool itself: [translate.md](../features/translate.md), decision in
[ADR 0126](../adr/0126-translate-tool.md).

## Swap languages
A button that makes the translation the input and swaps source and target, like Converting's Swap. Open point: what
Detect becomes after a swap (the detected language, or Detect again).

## Linked sentence highlight
The caret in a sentence on one side softly highlights the matching sentence on the other side. The model knows the
segments and their translations, so the ranges on both sides are there; the right side would need the offsets of each
translated sentence in the assembled text.

## Read the translation aloud
Hand the translation to Read Aloud. Useful only for targets Read Aloud speaks (English today; German with a Piper
engine, see [speech ideas](speech.md)).

## Detection per paragraph
Detect each paragraph's language and translate it from there, for mixed texts. It needs a model per detected pair and
may ask for several downloads at once.

## An LLM engine
A `TranslationEngine` that sends the text to a local OpenAI-compatible server (Ollama, LM Studio, llama.cpp) running a
translation model such as TranslateGemma 4B. Better with idioms and context across sentences, but gigabytes per model
and slow on a CPU. Open points: where the URL and model are set, whole paragraphs instead of sentences, streaming.

## Batched sentences
Run several sentences through the encoder and the beam search at once. Faster on many cores for long texts; the
per-sentence cache and cancellation would work per batch.

## Translating code comments
Translate only the comments of a source file, keeping the code. It needs the file's language to tell comments from
code (like the code-aware reading in the speech ideas).

## Glossaries
Terms that must not be translated or must be translated one way (product names, API names). With Opus-MT this could
reuse the placeholders that protect inline code.

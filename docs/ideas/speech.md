# Read Aloud: left for later

Explored, not decided or built. The tool itself: [read-aloud.md](../features/read-aloud.md), decision in
[ADR 0124](../adr/0124-read-aloud-speech.md).

## Other languages and engines
Only English with Kokoro exists. Everything above `SpeechEngine` is engine-neutral, so more engines are beans:
- **Piper for German** (or other languages), through sherpa-onnx or plain ONNX Runtime with its own phonemizer.
- **An OpenAI-compatible speech server**, or Kokoro-FastAPI in Docker, as an engine that posts the chunk to a local
  endpoint. Open points: where the URL and key are set (Settings, secrets), and word timings, which such servers do not
  return (the highlight would follow sentences only).
- **CoreML on macOS** for Kokoro, if the CPU speed on Apple Silicon turns out too slow.

## Code-aware reading
Source code read as words: camelCase and snake_case split, comment markers dropped, symbols named or skipped. It needs
the file's language to tell comments from code.

## Read Aloud in more editors
Other views that embed a code editor (Compose, Dockerfile, Gradle, HTTP, the `.env` editor) would place
`CodeEditor.toolbarItems` and forward `activity` to show the controls. Source code needs code-aware reading first (see
above), so it is not offered there.

## Export to MP3 or WAV
Save the synthesized audio as a file. WAV is easy (the samples are there); MP3 needs an encoder.

## A volume control
The tool uses the system volume. A slider in the toolbar would need a gain in the sink; the media player's volume and
mute are stored separately.

## A global key binding
Reading the selection from any tab with one key, or a key for Pause/Resume while another tab has the focus. The
tab-local keys exist; a global one needs a free combination in
[keyboard-shortcuts.md](../features/keyboard-shortcuts.md).

## Batching the espeak-ng calls
Each word starts one espeak-ng process (cached, up to four at once). One process for a whole chunk would be faster on a
slow machine but loses the word boundaries the timings need; it would have to split the output at markers.

## An explorer action for more file types
Only `.txt` and `.md` files have "Read Aloud" in the explorer. Others (source files, `.html`, `.rst`) could follow with
the matching text preparation.

## Better stress than the function-word list
Words are sounded alone, so a short list of function words is unstressed and "a" is guessed. A pronunciation lexicon
with parts of speech, or phonemizing a whole sentence with its words mapped back, would stress words from context.

## Remembering the reading position after Stop
Stop clears the highlight and the next Play starts at the caret. Remembering where the reading stopped (per tab text)
would let Play continue there.

## All Kokoro voices in a picker
The tab offers four voices (accent and gender). Kokoro has 28 English voices with grades from A to F; a picker grouped by
accent, with name, gender and grade, could offer them all. The first version listed them that way and was replaced by
the two selectors for simplicity.

# Read Aloud tool

**Modules:** `modules/speech` (package `dev.sluide.speech`: the window's `ReadAloud` bean, the engine API in `engine`,
text preparation in `text`, the Kokoro engine in `kokoro`, the model download in `install`, playback in `audio`, the
reading in `session`, Settings → Speech in `settings`, the tab in `ui`); menu and wiring in `ide` (`WindowTools`,
`dev.sluide.ide.speech.WindowReadAloud`); decision in [ADR 0124](../adr/0124-read-aloud-speech.md)

The Read Aloud tool reads text aloud with a local voice: no cloud, no account. It is a tab of the editor area with a
toolbar at the top and the text below. The text is highlighted while it is read: the sentence softly, the word being
spoken strongly. English only for now.

## Opening
- **Tools → Read Aloud** opens the tab, with the window's last text or empty. There is one tab per window: choosing the
  item again selects the open tab. The tab has no key binding; Shortcut+W closes it, and it is not restored after a
  restart.
- **Read Selection Aloud** in the context menu of every code editor reads the selection; without a selection the item
  reads **Read Aloud** and takes the whole text, unsaved edits included. Text over 1 MiB disables the item ("larger
  than 1 MiB"). Code: `ReadAloudExtension`, registered by `ide` after Converting's item.
- **Read Aloud** in the explorer's context menu of a single `.txt` or `.md` file reads the saved file. If a tab of it has
  unsaved changes, a question says so ("Save and Read Aloud", "Read Saved Version" or Cancel). A file over 1 MiB or not
  UTF-8 is an error dialog. `ReadAloud.readFile` reads off the FX thread; `ide` (`WindowReadAloud`) asks and wires.
- When text arrives from the editor or the explorer, it replaces the tab's text, the tab is shown and reading starts at
  once; a running reading stops first. With setup problems (no model, no espeak-ng) the text is put in, nothing starts,
  and the notice shows what is missing.
- Text, voice and speed belong to the window and survive closing the tab; closing the tab stops a reading.

## The tab
The toolbar has, left to right: **Play/Pause** (one button), **Stop**, the **voice picker**, the **speed** (0.5 to 2×,
the media player's list) and, on the right, the status text: "Preparing…", "Sentence 3 of 40", "Paused", "Finished" or
the error.
- While idle the text is an editable plain text editor in the editor font: type or paste. **Play** starts at the
  sentence with the caret. While a reading is preparing, speaking or paused the text is locked (read-only; selecting and
  moving the caret still work) and **Stop** unlocks it. The next Play starts at the caret's sentence again.
- **Pause** keeps the position and the audio already made; **Resume** goes on.
- The highlight scrolls the spoken word into view.
- A double-click on a sentence while reading restarts the reading there.
- Changing the voice or the speed while reading restarts the reading at the current sentence; a paused reading stays
  paused.
- Keys, tab-local: Shortcut+Enter plays, Space pauses and resumes, Escape stops, see
  [keyboard-shortcuts.md](keyboard-shortcuts.md#read-aloud-tab).
- A text without anything to speak (blank, or only Markdown markup) shows "Nothing to read.".

## Starting and buffering
Synthesis runs ahead of playback, one sentence chunk at a time. Playback starts once enough audio is buffered to reach
the end of the text without a gap, judged from the measured synthesis rate: at once when synthesis is faster than
playing, later when it is slower (`StartRule`). At most 300 seconds are buffered before the start (and kept ahead
while playing), so on a very slow machine a long reading starts anyway and may pause at gaps while it waits for the
synthesis. The first chunk is kept short: a long first sentence is cut at its first clause, so the first audio comes
fast.

## What is read
- Plain text is read as it is, whitespace collapsed; a blank line is a pause.
- Text from `.md` files (by file name) loses its Markdown markup: front matter, fenced and indented code, HTML tags and
  comments, link targets and URLs are skipped; heading, emphasis, list, quote and table markers are removed; link text
  and image alt text are read.
- Digits with separators (1,000,000, 3:30) and known abbreviations (Mr., Dr., e.g.) do not end a sentence.
- The highlight always maps back to the text as shown, Markdown included.

## Voices
All English Kokoro voices, in two groups, "American English" and "British English", best grade first. A label looks
like "Heart · female · A": name, gender and the model's quality grade (A best, F worst). The default is Heart (`af_heart`)
at 1×. The last voice and speed are remembered user-wide in the field `speech` of `~/.sluide/shared.json`
([ADR 0101](../adr/0101-shared-data-file.md)).

## Setup
Reading needs the Kokoro model and the `espeak-ng` program. Without them the tab shows a notice above the toolbar, one
row per problem. Nothing is downloaded or run without a click.
- **Model:** the notice has **Download (326 MB)**, Settings → Speech has Download per variant. The variants: fp32
  (default, 326 MB, best quality), fp16 (164 MB) and int8 (114 MB), plus the voices file (28 MB). They are stored in
  `~/.sluide/models/kokoro-v1.0/`. The download goes to `.part` files, is checked against a pinned sha256 (a mismatch
  deletes the part file and shows an error) and is moved into place atomically. Progress shows in the notice and as a
  background task row ("Downloading Kokoro model 40 %") that can be cancelled. A download without data for 30 seconds
  fails. **The download is the only network access of the tool.** **Delete** (Settings → Speech) asks first, Cancel is
  the default.
- **espeak-ng** turns words into sounds. Install it with `brew install espeak-ng` (macOS) or `sudo apt install espeak-ng`
  (Debian/Ubuntu). Thoreau looks for it at the path set in Settings → Speech, then through the login shell
  (`command -v espeak-ng`), then in `/opt/homebrew/bin`, `/usr/local/bin`, `/opt/local/bin` and `/usr/bin`. **Check
  Again** in the notice looks again after an install.
- The model loads on the first reading, off the FX thread, and is unloaded after five minutes without a reading.

## Settings → Speech
The optional **espeak-ng** path (`speech.espeak`, empty finds it on the login shell's PATH), and per model variant its
status, Download (Cancel while downloading) and Delete, and the variant in use (`speech.kokoro.variant`). Lives in the
`speech` module (`SpeechSettings`, `SpeechSettingsPage`); see [settings.md](settings.md).

## One playback at a time
Reading and the media player share one playback app-wide (`PlaybackCoordinator`): starting a reading pauses a playing
medium in any window, and starting a medium pauses a running reading (it keeps its place). Two readings pause each
other the same way. See [media-player.md](media-player.md).

## Background reading
The tab shows the running marker (`ViewActivity.RUNNING`) while it speaks. Reading continues when another tab is
selected; closing the tab, closing the window or quitting stops it.

## Known limits
- English only. Other languages and engines: [speech ideas](../ideas/speech.md).
- Words are sounded one by one (espeak-ng gets each word alone), so stress comes from a list: function words (the, of,
  is, ...) are unstressed, "a" is the article, "A" only where it is not a letter ("Grade A students"). A leading
  elision apostrophe ('90s) is read as a quote.
- No volume control: it uses the system volume.
- The reading position is not remembered after Stop.
- The model has to be downloaded once; espeak-ng installed.
- Audible output and the macOS path are verified by the user only. Sandbox and CI tests use fakes (an engine, a sink);
  an opt-in test synthesizes with the real model: `SLUIDE_KOKORO_SMOKE=1` ([testing.md](../testing.md)).

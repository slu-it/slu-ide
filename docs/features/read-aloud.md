# Read Aloud tool

**Modules:** `modules/speech` (package `dev.sluide.speech`: the window's `ReadAloud` bean, the engine API in `engine`,
text preparation in `text`, the Kokoro engine in `kokoro`, the model download in `install`, playback in `audio`, the
reading in `session`, Settings → Speech in `settings`, the tab in `ui`); menu and wiring in `ide` (`WindowTools`,
`dev.sluide.ide.files.WindowTextFileAction`); decision in [ADR 0124](../adr/0124-read-aloud-speech.md)

The Read Aloud tool reads text aloud with a local voice: no cloud, no account. It is a tab of the editor area with a
toolbar at the top and the text below. The text is highlighted while it is read: the sentence softly, the word being
spoken strongly. English only for now.

## Opening
- **Tools → Read Aloud** opens the tab, with the window's last text or empty. There is one tab per window: choosing the
  item again selects the open tab. The tab has no key binding; Shortcut+W closes it, and it is not restored after a
  restart.
- **Read Selection Aloud** in the context menu of every code editor reads the selection; without a selection the item
  reads **Read Aloud** and takes the whole text, unsaved edits included. Text over 1 MiB disables the item ("larger
  than 1 MiB"; the limit measures the selection when there is one). In plain-text and Markdown editors the items read
  in place, see "In the editor": **Read Aloud** without a selection starts at the sentence at the caret (the
  right-click position), not at the top; in the other editors they put the text in the tab. Code: `ReadAloudExtension`, registered by `ide` after Converting's item.
- **Read Aloud** in the explorer's context menu of a single `.txt`, `.md` or `.markdown` file reads the saved file. If a tab of it has
  unsaved changes, a question says so ("Save and Read Aloud", "Read Saved Version" or Cancel). A file over 1 MiB or not
  UTF-8 is an error dialog. `ReadAloud.readFile` reads off the FX thread; `ide` (`WindowTextFileAction`) asks and wires.
- When text arrives from the editor or the explorer, it replaces the tab's text, the tab is shown and reading starts at
  once; a running reading stops first. With setup problems (no model, no espeak-ng) the text is put in, nothing starts,
  and the notice shows what is missing.
- Text, voice and speed belong to the window and survive closing the tab; closing the tab stops a reading.

## The tab
The toolbar has, left to right: **Play/Pause** (one button), **Stop**, the **accent** (American or British English)
and **gender** (Female, Male) selectors, the **speed** (0.5 to 2×, the media player's list) and, on the right, the
status text: "Preparing…", "Sentence 3 of 40", "Paused", "Finished" or the error.
- While idle the text is an editable plain text editor in the editor font: type or paste. **Play** starts at the
  sentence with the caret. While a reading is preparing, speaking or paused the text is locked (read-only; selecting and
  moving the caret still work) and **Stop** unlocks it. The next Play starts at the caret's sentence again. With a
  selection, Play (button or Shortcut+Enter) reads only the selected text and stops at its end.
- **Pause** keeps the position and the audio already made; **Resume** goes on.
- The highlight scrolls the spoken word into view.
- A double-click on a sentence while reading restarts the reading there.
- Changing the voice or the speed while reading restarts the reading at the current sentence; a paused reading stays
  paused.
- Keys, tab-local: Shortcut+Enter plays (the selection, if there is one), Space pauses and resumes, Escape stops, see
  [keyboard-shortcuts.md](keyboard-shortcuts.md#read-aloud-tab).
- A selection is prepared without its surroundings: inside a code fence it is read as prose, and partial emphasis
  markers may remain. A double-click outside the selection being read does nothing.
- A text without anything to speak (blank, or only Markdown markup) shows "Nothing to read.".

## Starting and buffering
Synthesis runs ahead of playback, one sentence chunk at a time. Playback starts once enough audio is buffered to reach
the end of the text without a gap, judged from the measured synthesis rate: at once when synthesis is faster than
playing, later when it is slower (`StartRule`). At most 300 seconds are buffered before the start (and kept ahead
while playing), so on a very slow machine a long reading starts anyway and may pause at gaps while it waits for the
synthesis. The first chunk is kept short: when the sentence a reading starts at is longer than 120 characters, it is cut at its
first clause mark (else at the last space before 120 characters), so the first audio comes fast.

## What is read
- Plain text is read as it is, whitespace collapsed; a blank line is a pause.
- Text from `.md` and `.markdown` files (by file name) loses its Markdown markup: front matter, fenced and indented code, HTML tags and
  comments, link targets and URLs are skipped; heading, emphasis, list, quote and table markers are removed; link text
  and image alt text are read.
- Known abbreviations (Mr., Dr., e.g.), initials and decimals (3.14) do not end a sentence. Numbers with separators
  (1,000,000, 3:30) are sounded as one word.
- The highlight always maps back to the text as shown, Markdown included.

## Voices
Two selectors choose the voice: **accent** (American English, British English) and **gender** (Female, Male). Each
combination is the best-graded Kokoro voice of that accent and gender (grade A best, F worst; ties as in the Python
prototype):

| Accent | Gender | Voice | Grade |
|---|---|---|---|
| American English | Female | Heart (`af_heart`, the default) | A |
| American English | Male | Michael (`am_michael`) | C+ |
| British English | Female | Emma (`bf_emma`) | B- |
| British English | Male | George (`bm_george`) | C |

The default speed is 1×. A remembered voice that is no longer offered (an older choice such as `af_bella`) falls back to
Heart. The last voice and speed are remembered user-wide in the field `speech` of `~/.sluide/shared.json`
([ADR 0101](../adr/0101-shared-data-file.md)).

## Setup
Reading needs the Kokoro model and the `espeak-ng` program. Without them the tab shows a notice above the toolbar, one
row per problem. Nothing is downloaded or run without a click.
- **Model:** the notice has a Download button that names what is still missing, the model and the voices file: "Download
  (354 MB)" on a first install. Settings → Speech has Download per variant, with the variant's own size. The variants: fp32
  (default, 326 MB, best quality), fp16 (164 MB) and int8 (114 MB), plus the voices file (28 MB). They are stored in
  `~/.sluide/models/kokoro-v1.0/`. The download goes to `.part` files, is checked against a pinned sha256 (a mismatch
  deletes the part file and shows an error) and is moved into place atomically. Progress shows in the notice and as a
  background task row ("Downloading Kokoro model 40 %") that can be cancelled. A download without data for 30 seconds
  fails. **The download is the only network access of the tool.** **Delete** (Settings → Speech) asks first, Cancel is
  the default.
- **espeak-ng** turns words into sounds. Install it with `brew install espeak-ng` (macOS) or `sudo apt install espeak-ng`
  (Debian/Ubuntu). Thoreau looks for it at the path set in Settings → Speech, then through the login shell
  (`command -v espeak-ng`), then in `/opt/homebrew/bin`, `/usr/local/bin`, `/opt/local/bin` and `/usr/bin`; a wrong or
  empty path falls back to the later steps. **Check
  Again** in the notice looks again after an install.
- The model loads on the first reading, off the FX thread, and is unloaded after five minutes without a reading.

## Settings → Speech
The optional **espeak-ng** path (`speech.espeak`, empty finds it on the login shell's PATH), and per model variant its
status, Download (Cancel while downloading) and Delete, and the variant in use (`speech.kokoro.variant`). Lives in the
`speech` module (`SpeechSettings`, `SpeechSettingsPage`); see [settings.md](settings.md).

## In the editor
Plain-text editors (files without a language viewer, e.g. `.txt`) and Markdown editors (`.md`, `.markdown`) have the
Read Aloud controls themselves, the tab's toolbar (`ReadAloudToolbar`, embedded): in a plain-text file's own toolbar row
above the text, in a Markdown file's preview toolbar after the table of contents. Other editors only have the context
menu items, which send the text to the tab. Code: `EditorReading` (one per extended editor, wired by
`ReadAloudExtension`) and `EditorSurface`.
- **Play** reads from the sentence of the caret, or only the selection, of the editor's current text, unsaved edits
  included; a Markdown file (by its current name) is read as Markdown. Text over 1 MiB is not read: the status says
  "The text is larger than 1 MiB."
- While a reading is preparing, speaking or paused the editor is read-only (a lock: caret, selection, copy, find and
  scrolling work). The sentence and word are highlighted in the source text and scrolled into view, and the editor's
  tab shows the running marker. In Split view only the source is highlighted; the preview follows by scroll sync.
- Keys: Shortcut+Enter plays while the text is editable; Space pauses or resumes and Escape stops while reading; a
  double-click on the text while reading jumps to that sentence (as in the tab,
  [keyboard-shortcuts.md](keyboard-shortcuts.md)).
- The context menu items **Read Aloud** (from the sentence at the caret) and **Read Selection Aloud** read in place in
  these editors, and are disabled while that editor is reading. The 1 MiB limit measures the selection when there is
  one, else the whole text.
- The setup notice (no model, no espeak-ng) shows as a banner above the text while something is missing. While idle
  with setup problems the status next to Play also says "Setup needed.", in the tab and in editors alike; in a
  Markdown preview, where the source and its banner are hidden, that is what shows.
- A reload (the file changed on disk) stops the reading, since the highlight would no longer fit.
- Each editor has its own reading. Voice and speed are the shared preferences: every Play (also the tab's) takes the
  ones last chosen anywhere, and the toolbar shows them. Starting one reading pauses the running one (see below).

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

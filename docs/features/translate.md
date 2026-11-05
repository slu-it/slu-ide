# Translate tool

**Modules:** `modules/translation` (package `dev.sluide.translation`: the window's `Translate` bean, the engine API in
`engine`, text segmentation in `text`, detection in `detect`, the tab's state in `session`, the tab in `ui`, the code
editors' menu item in `editor`); menu and wiring in `ide`; decision in [ADR 0126](../adr/0126-translate-tool.md)

The Translate tool translates text with local models: no cloud, no account. It is a tab of the editor area with a
toolbar at the top, the text to translate on the left (editable) and the translation on the right (read-only). Nothing
is translated until a target language is chosen.

## Opening
- **Tools → Translate** opens the tab, with the window's last text or empty. One tab per window: choosing the item again
  selects the open tab. The tab has no key binding; Shortcut+W closes it, and it is not restored after a restart. The
  focus goes to the left side.
- **Translate Selection** in the context menu of every code editor translates the selection; without a selection the
  item reads **Translate** and takes the whole text, unsaved edits included. Text over 1 MiB disables the item
  ("larger than 1 MiB"). Code: `TranslateExtension`.
- **Translate** in the explorer's context menu of a single `.txt`, `.md` or `.markdown` file translates the saved file.
  If a tab of it has unsaved changes, a question says so ("Save and Translate", "Translate Saved Version" or Cancel). A
  file over 1 MiB or not UTF-8 is an error dialog. `Translate.readFile` reads off the FX thread.
- Text that arrives from an editor or the explorer replaces the tab's text and is translated at once (when a target is
  chosen and the models are installed). The Markdown toggle is set from the file name: on for `.md` and `.markdown`,
  else off.
- Text, languages, quality and the Markdown toggle belong to the window and survive closing the tab; closing the window
  ends the work. The text itself is never stored.

## The tab
The toolbar has, left to right: **Source** ("Detect" first, shown as "Detect (German)" once detected, then the
languages by English name), **Target** ("Choose target…" until one is chosen), **Quality** (Medium, High), the
**Markdown** toggle, the status text; at the right **Copy** (the whole translation) and **Open as Scratch** (a scratch
file with the translation, `.md` when Markdown is on, else `.txt`, [ADR 0066](../adr/0066-scratch-files.md)).
- Source and Target are searchable: the button opens a list with a search field; typing filters the languages by name
  (any case), Enter picks the selected one (the first after typing, the current one when nothing was typed), Up and
  Down move, Escape closes; keys in [keyboard-shortcuts.md](keyboard-shortcuts.md#translate-tab).
- Both sides are plain text editors in the editor font, without syntax colouring. The right side is read-only: caret,
  selection and copy work. Its text is replaced only when the translation changes, so a selection survives status and
  download updates.
- Typing is translated 1 s after the last edit. Changing the source, target, quality or the Markdown toggle translates
  at once. A new translation cancels the running one. Unchanged sentences come from a per-window cache, so only edited
  sentences are translated again.
- Status: "Choose a target language", "Detecting…", "Translating 3 of 40…", "Translated", "Already German" (source and
  target are the same: the right side shows the input), "Language not detected. Choose the source.", "No translation
  from X to Y" (no model route) or the error.
- Text over 1 MiB, typed or pasted, is kept but not translated: the status says "Text larger than 1 MiB", the right
  side is empty and nothing runs, also after a change of target, quality or Markdown, until the text is short enough.
- The tab shows the running marker (`ViewActivity.RUNNING`) while a translation waits or runs.

## What is translated
- **Plain text** (Markdown off): whitespace and line structure are kept, only the sentences are translated, one by one.
- **Markdown** (toggle on): front matter, fenced and indented code, HTML blocks and tags, comments, link targets, URLs
  and inline code are kept verbatim; heading, list, quote and table markers stay in place; only the text between is
  translated. A paragraph that wraps over several lines (also in a list item or block quote) is translated as a whole:
  its line breaks become one space and the translation stands on one line, which renders the same. A hard line break
  (two spaces or a backslash) ends the paragraph and is kept.

## Languages and quality
Every language with an Opus-MT model (University of Helsinki, Hugging Face ONNX conversions) to or from English is
offered: as a source when a model translates from it into English, as a target when a model translates English into it.
That is English plus 25 sources (Afrikaans, Arabic, Chinese, Czech, Danish, Dutch, Estonian, Finnish, French, German,
Hindi, Hungarian, Indonesian, Italian, Japanese, Korean, Polish, Russian, Spanish, Swedish, Thai, Turkish, Ukrainian,
Vietnamese, Xhosa) and 20 targets (the same without Estonian, Japanese, Korean, Polish, Thai and Turkish, plus Romanian).
A pair with a direct model (such as German → French) uses it; any other pair is translated through English (two
models; the notice says so). Chinese output is Simplified Chinese.
- **Medium** (int8, the default) is smaller and faster; **High** (fp32) is more exact. Each quality of each model is its
  own download.
- The last source, target and quality are remembered user-wide in the field `translation` of `~/.sluide/shared.json`
  ([ADR 0101](../adr/0101-shared-data-file.md)).

## Detection
With the source on Detect, the language is detected in the text (first 10 000 characters) with Lingua, limited to the
offered languages it knows, and the source button shows it: "Detect (German)". Detection runs on the debounced text.
Languages Lingua does not know can only be picked by hand.

## Setup
Each translation needs the models of its route. When one is missing the tab shows a notice above the toolbar and
translates nothing: "German → English (Medium) is not installed", or through English "German → Swedish (Medium) needs
German → English and English → Swedish", with **Download (221 MB)** (a pair's model is about 115 MB in Medium and
about 440 MB in High). While downloading, the notice shows a progress bar, the percent and **Cancel**, and a background
task row shows the download; after a failure it shows the message and **Download again**. When the models are
installed the translation starts by itself.
- Models are stored in `~/.sluide/models/opus-mt/<src>-<tgt>/` (shared files) with `medium/` and `high/` folders. Each
  download goes to `.part` files and is checked against a pinned sha256. Settings → Translation lists the downloaded
  models with their size and Delete (a failed download shows as "incomplete"), the total and the attributions.
- **The download the user clicks is the only network access of the tool.** Detection and translation run locally.

## Known limits
- Sentences are translated one by one, without context across sentences; quality is below commercial services.
- Close languages (Croatian and Bosnian, Indonesian and Malay, ...) are not told apart by the detection; choose the
  source by hand.
- Only the languages Lingua knows are detected.
- Sentences longer than the model's limit are split at a clause, else at a space.
- Speed and macOS behaviour are verified by the user only. Tests use fakes.

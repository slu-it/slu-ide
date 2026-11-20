# 0190. Pages reach WebKit with astral characters escaped and emoji in their own span

- Status: accepted
- Date: 2026-11-20

## Context
Every character outside the BMP (emoji such as U+1F680, any surrogate pair) showed as six U+FFFD in every page loaded
with `WebEngine.loadContent(String)`; BMP characters were fine. Measured with JavaFX 27 and the code points of the
DOM text:
- `<p id="a">x🚀y</p>` (with or without `<meta charset>`): `78 fffd fffd fffd fffd fffd fffd 79`
- `<p id="a">x&#x1F680;y</p>`: `78 1f680 79`
- `executeScript("'🚀'.codePointAt(0).toString(16)")`: `1f680`; `executeScript` and `JSObject` calls are fine.

The cause is most likely a JNI modified-UTF-8 conversion that encodes each surrogate as three bytes while WebKit
decodes UTF-8 (JDK-8304041). The renderers' HTML is correct.

## Decision
- `WebEngine.loadPage(html)` (`previews`, `WebPages.kt`) replaces every non-empty `loadContent` in Thoreau (resets with
  `loadContent("")` stay); the ebook view uses it too, so its `data-o` UTF-16 offsets match the DOM again.
- `preparePage` returns a page without a surrogate char and without a char that can be part of an emoji (see the next
  bullet) unchanged (the same instance), so those pages load exactly as before. Otherwise it parses the page with jsoup
  and writes it with `prettyPrint(false)` and the US-ASCII charset: text and attribute values get numeric references.
  Content of `<script>` and `<style>` is a `DataNode` that jsoup does not escape, so each astral code point there is
  rewritten: in a script as the two surrogates `\uD83D\uDE80` (valid in JS strings, template literals, regexp literals
  and JSON; not in identifiers, `String.raw` or data blocks), in a style as the CSS escape `\1F680 ` (the space ends the
  escape).
- `<pre>` and `<listing>`: jsoup's parser drops the first newline after the start tag and its writer does not add it
  back, so `restorePreNewlines` writes it again (`textarea` keeps it without help).
- Emoji in their own span. With the characters right, JavaFX's WebKit on macOS still mis-lays-out a text node that
  holds a colour emoji together with other text (measured by the user): in a heading all text after the emoji
  vanishes, in a paragraph the emoji is a smeared stripe over the next word, an emoji at the end of a text breaks too,
  BMP emoji (U+2705) as well; U+2605 is fine. A plain `<span>` around each emoji fixes every case (an inline-block,
  an emoji font family, normal weight, `text-rendering` or no kerning do not help). Linux cannot reproduce it: the
  sandbox has no emoji font. In the same jsoup pass, before the escaping, `preparePage` splits each text node into
  extended grapheme clusters (`BreakIterator.getCharacterInstance(Locale.ROOT)`, UAX #29 with emoji sequences:
  ZWJ families, flags, skin tones, keycaps, FE0F) and wraps each cluster that holds an extended pictograph, a regional
  indicator, U+FE0F or U+20E3 in `<span data-emoji="">` (an attribute, so no class selector of a page matches it; no
  stylesheet of the previews or ebooks has a bare `span` selector). Text-presentation pictographs such as U+2605 are
  wrapped too, which is harmless. A text whose direct parent is a `span[data-emoji]` and that is exactly one emoji
  cluster is left alone, so preparing twice changes nothing (any other `data-emoji` element of a page still gets its
  emoji wrapped). `text-marks.js` walks all text nodes of a `data-o` run, so Read Aloud offsets survive the span.
- Not `load("data:...")`: it changes the document URL and origin that link handling and `#anchor` navigation rely on
  (ADR 0017, 0018). Not a regex over the markup: the rule is to work on the parsed document.

## Consequences
- Astral characters in text, attributes, scripts and styles display everywhere; a page with one is re-serialised by
  jsoup (BMP non-ASCII also becomes references, which is equivalent HTML).
- Known limits, not engineered around: astral characters in raw-text elements (`xmp`, `noembed`, `noframes`, `iframe`
  content) and in CDATA sections stay as they were. jsoup writes `<xmp>🚀</xmp>` unescaped, so the character stays
  broken there but does not turn into visible reference text (pinned by `WebPagesTest`).
  Also not repaired: `<plaintext>` (its content shows the reference and the span markup as literal text), a backslash
  right before an emoji in a script (`'\<emoji>'` changes meaning), astral identifiers, `String.raw` and data-block
  scripts (`type="text/plain"`), which see the escape text, and lone surrogates, which stay broken. None of the
  shipped resources has astral characters.
- A new page loaded with `loadContent` brings the defect back; `.claude/rules/javafx.md` says pages go through
  `loadPage`.
- Emoji are not wrapped in `textarea`, `title`, `option` (no child elements) and inside `svg` or `math` (a `span` is
  not valid there); text that scripts write after the load (Mermaid labels, KaTeX output) and the HTML preview
  (served by `PreviewServer`, not loaded through `loadPage`) are not prepared either. A text directly in `<body>`
  that starts with an emoji makes the span `body > :first-child`, which `preview.css` styles. The paint is checked
  by a macOS test (`WebPagesUiTest`) that has not yet been seen red or green; its first manual `macos-ui-tests.yml`
  run is the open check.
- Related: 0016, 0017, 0069, 0165.

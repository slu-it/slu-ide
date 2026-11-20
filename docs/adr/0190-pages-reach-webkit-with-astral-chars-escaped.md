# 0190. Pages reach WebKit with astral characters escaped

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
- `escapeSupplementary` returns a page without a surrogate char unchanged (the same instance), so those pages load
  exactly as before. Otherwise it parses the page with jsoup and writes it with `prettyPrint(false)` and the US-ASCII
  charset: text and attribute values get numeric references. Content of `<script>` and `<style>` is a `DataNode`
  that jsoup does not escape, so each astral code point there is rewritten: in a script as the two surrogates
  `\uD83D\uDE80` (valid in JS strings, template literals, regexp literals and JSON; not in identifiers, `String.raw`
  or data blocks), in a style as the CSS escape `\1F680 ` (the space ends the escape).
- `<pre>` and `<listing>`: jsoup's parser drops the first newline after the start tag and its writer does not add it
  back, so `restorePreNewlines` writes it again (`textarea` keeps it without help).
- Not `load("data:...")`: it changes the document URL and origin that link handling and `#anchor` navigation rely on
  (ADR 0017, 0018). Not a regex over the markup: the rule is to work on the parsed document.

## Consequences
- Astral characters in text, attributes, scripts and styles display everywhere; a page with one is re-serialised by
  jsoup (BMP non-ASCII also becomes references, which is equivalent HTML).
- Known limits, not engineered around: astral characters in raw-text elements (`xmp`, `noembed`, `noframes`, `iframe`
  content) and in CDATA sections stay as they were. jsoup writes `<xmp>🚀</xmp>` unescaped, so the character stays
  broken there but does not turn into visible reference text (pinned by `WebPagesTest`).
  Also not repaired: `<plaintext>` (its content shows the reference as literal text), a backslash right before an
  emoji in a script (`'\<emoji>'` changes meaning), astral identifiers, `String.raw` and data-block scripts
  (`type="text/plain"`), which see the escape text, and lone surrogates, which stay broken. None of the shipped
  resources has astral characters.
- A new page loaded with `loadContent` brings the defect back; `.claude/rules/javafx.md` says pages go through
  `loadPage`.
- Related: 0016, 0017, 0069, 0165.

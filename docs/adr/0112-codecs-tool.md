# 0112. Codecs tool: two editable sides, suggested detection, one codec at a time

- Status: accepted; amends [0047](0047-module-dependencies-and-shared-modules.md) (`codecs` joins the editor family) and [0108](0108-code-editor-extensions-and-context-menu.md) (`EditorMenuTarget.selectedText`); see [0110](0110-tools-menu.md)
- Date: 2026-10-30

## Context
Developers keep encoding and decoding small pieces of text: a Base64 value from a config, a JWT from a request, a
percent-encoded query, an escaped JSON string, a hash to compare. Online converters mean pasting tokens and secrets
into a web page. The Tools menu ([ADR 0110](0110-tools-menu.md)) has room for a second tool next to Coloring. The
questions are how the tool decides between encoding and decoding, how it picks the format, and what to do with
results that are not text.

## Decision
- **Module `codecs`** in the editor family: its tab is an `EditorTab` and it adds a context-menu item to code editors,
  so it depends on `editor-area` and `file-viewers`. `Codecs` (window scope) holds the window's state, so a reopened
  tab shows what the closed one showed. Nothing is stored across restarts, because tokens and secrets end up in it.
- **Two editable sides, no direction switch.** The text side (left) and the encoded side (right) are both editable;
  the side edited last is the source and the other side follows it on every change. An error marks the source side
  and leaves the other side as it was. Switching the codec or an option keeps the source and recomputes the other
  side. One-way codecs keep the side they read from: Hash the text side, JWT the encoded side.
- **One codec at a time, with options**, no chain of steps: the common combinations are options (compression for the
  byte codecs, URL-safe and data URI for Base64). Codecs: Base64, Base32, Base58, Ascii85/Z85, Hex (bytes, through a
  charset and optional gzip/zlib/raw deflate), URL, HTML/XML, JSON string, Java/Kotlin string, Unicode escapes
  (escapes), JWT (decode only, signature not verified) and Hash (MD5 to SHA3-256 and CRC32, HMAC with a key).
- **Detection only suggests**: text pasted on the encoded side is classified (JWT, data URI, Ascii85, hex, Base32,
  Base64, URL, HTML, escapes) and a hint offers to switch, when the current codec fails or the match is unambiguous.
  The only automatic switch is "Open Selection in Codecs" from a code editor, which is an explicit request to look
  at that text. Heuristics are wrong too often (many words are valid Base64) to switch silently.
- **Binary results are a hex dump**: decoded bytes that are not valid text in the charset are shown read-only with
  their size and type (from magic bytes), with Save as… and Edit as Text. Files up to 1 MiB can be opened or dropped
  on the text side; a JavaFX `TextArea` becomes slow beyond that.
- **Pure Kotlin and the JDK** for all codecs (`java.util.Base64`, `HexFormat`, `MessageDigest`, `Mac`, `Inflater`),
  hand-written where the JDK has nothing (Base32, Base58, Ascii85, component URL encoding, escapes). Only HTML entity
  decoding uses jsoup (already a dependency of `previews`), for the full HTML5 table of named references. Decoding is
  lenient about whitespace, padding, quotes and prefixes (`Bearer `, `data:`), and errors carry the position in the
  input. gzip is parsed by hand to reject trailing data and check CRC and size; decompression stops at 16 MiB, Base58
  at 1 024 bytes (its conversion is quadratic and runs on the JavaFX thread).
- **Editor integration**: `EditorMenuTarget` carries the selected text, and the code editors get "Open Selection in
  Codecs".

## Consequences
- Secrets pasted into the tool stay in the process; closing the window forgets them.
- Some texts do not round-trip by nature of their format: in Unicode escapes with "non-ASCII only", ASCII text that
  looks like an escape (`é`, `U+0041`) decodes as one; HTML decodes `&#x80;`..`&#x9F;` via windows-1252, so C1
  controls are written literally.
- Listed in [../index.md](../index.md).

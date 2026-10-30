# Codecs tool

**Modules:** `modules/codecs` (package `dev.sluide.codecs`: codecs in `codec`, the FX-free model in `conversion`,
detection in `detect`, the hex dump in `binary`, UI in `ui`, the editor item in `editor`); menu in `ide` (`ToolsMenu`,
`WindowTools`); decisions in [ADR 0112](../adr/0112-codecs-tool.md) and [ADR 0110](../adr/0110-tools-menu.md)

The Codecs tool encodes and decodes text in the formats that come up in development: Base64 and other byte encodings,
URL and HTML escapes, string literal escapes, JWTs and hashes. It is a tab of the editor area with the codec list at the
left, the options of the chosen codec at the top, and two sides: the plain text at the left, the encoded text at the
right.

## Opening
- **Tools → Codecs** opens the tab. There is one Codecs tab per window: choosing the item again selects the open tab.
- **Open Selection in Codecs** in the context menu of a code editor (disabled without a selection) opens the tab with
  the selected text. When the text is recognised (see Detection), the codec switches to it and the text goes to the
  encoded side, so it is decoded at once; one pair of quotes around it (`"`, `'`, `` ` ``) is dropped then. Otherwise
  the text goes to the text side.
- Shortcut+W closes the tab. Texts, codec and options belong to the window: when the tab is closed and opened again,
  they are as they were. Nothing is stored; after a restart the tool starts empty with Base64.

## Two sides
Both sides are editable. The side you edited last is the **source**; the other side follows it on every change, so
typing on the left encodes and typing (or pasting) on the right decodes. There is no Encode/Decode switch.
- When the source cannot be converted, its status line shows the error, with the character it refers to when known
  ("Invalid Base64 character '!' (character 13)"); a click on the error selects that character. The other side keeps
  what it showed.
- Choosing another codec or changing an option keeps the source and converts it again.
- Each side has **Copy** and **Clear**; the text side also **Open File…** and **Save as…**.
- The status lines count characters (code points) and, on the text side, the bytes in the charset.

## Codecs and options
The codec list groups the codecs; the options bar shows the options of the chosen one. Options keep their values when
you switch codecs.

### Bytes
Text is turned into bytes with the **Charset** (UTF-8, UTF-16, UTF-16LE, UTF-16BE, ISO-8859-1, windows-1252,
US-ASCII), optionally compressed (**Compression**: None, gzip, zlib, Raw deflate as in SAML), and written in the
encoding; decoding goes the other way. Decompression stops at 16 MiB.

| Codec | Options | Decoding accepts |
|---|---|---|
| Base64 | URL-safe, Padding, Wrap at 76, data: URI (MIME type from the content or the opened file) | both alphabets mixed, whitespace, missing padding, a `data:…;base64,` prefix |
| Base32 | base32hex, Padding | any case, whitespace, missing padding |
| Base58 | (Bitcoin alphabet) | whitespace; at most 1 024 bytes or 1 400 characters |
| Ascii85 | Adobe (`<~ ~>`, `z`) or Z85 | Adobe with or without delimiters, whitespace; Z85 needs multiples of 4 bytes / 5 characters |
| Hex | Uppercase, Separator (none, space, colon) | `0x` prefixes, separators, whitespace |

### Escapes
| Codec | Options | Decoding accepts |
|---|---|---|
| URL | Component (RFC 3986, space as `%20`) or Form (space as `+`); the charset applies | `+` as space only in Form mode |
| HTML/XML | Non-ASCII as references (`&#xE9;`) | all HTML5 named references, decimal and hex references |
| JSON String | Non-ASCII as `\u` escapes | surrounding quotes; raw control characters are tolerated |
| Java/Kotlin String | Non-ASCII as `\u` escapes, Kotlin (escape `$`) | quotes, octal escapes, `\s`, `\uuuu0041` |
| Unicode Escapes | Form `\uXXXX`, `\u{X}` or `U+X`; All characters | all three forms mixed |

Encoding writes the content without quotes. In `U+X` form a space follows an escape before a hex digit or a space, and
decoding drops one space after each `U+` escape. With "All characters" off, ASCII text that looks like an escape
(`é`, `U+0041`) is decoded as one; HTML writes C1 controls (U+0080..U+009F) literally, as `&#x80;` means `€` to
browsers.

### Tokens: JWT
JWT decodes only: paste the token on the encoded side (a `Bearer ` prefix is fine); the text side is read-only and shows
the header and payload as formatted JSON, then `exp`, `iat` and `nbf` as UTC time, local time and relative time
("expired 3 h ago"), then the algorithm with "not verified": the signature is never checked. An expired token is marked
in the status. Encrypted tokens (JWE, five parts) cannot be decoded.

### Hashes: Hash
Hash is one-way: the encoded side is read-only and lists MD5, SHA-1, SHA-256, SHA-384, SHA-512, SHA3-256 and CRC32 of
the text's bytes (or of an opened file), each with a copy button. A **Key** turns them into HMACs (CRC32 has none).
Output is hex or Base64. Empty text has no rows.

## Line by line
With **Line by line** (all codecs but JWT and Hash) every line is converted on its own and line breaks are kept; Wrap
at 76 is off then. A line that fails stays as it was, and the status names the lines ("Lines 3, 7: …").

## Detection
Text pasted on the encoded side is checked: JWT, Base64 data URI, Ascii85 with delimiters, hex, Base32, Base64, URL
encoding, HTML entities, Unicode or JSON escapes. When it matches another codec than the chosen one, and either the
chosen codec cannot decode it or the match is unambiguous (JWT, data URI, Ascii85 delimiters), a hint offers to switch
("Looks like a JWT" — **Switch**). Nothing switches on its own. Decoded bytes that start like gzip or zlib data while
Compression is None get the hint "Looks gzip-compressed" — **Gunzip** (or Inflate).

## Binary data and files
Decoded bytes that are not valid text in the charset, contain a NUL character or start like a known file type (PNG,
JPEG, GIF, WebP, PDF, ZIP, gzip, Java class) are shown as a read-only **hex dump** (the first 64 KiB) with their size and
type, **Save as…** and **Edit as Text** (shows them as text with replacement characters and makes the side editable
again).

**Open File…** or a file dropped on the text side reads a file of at most 1 MiB: a text file is shown as text, any
other as a hex dump; its bytes are then encoded or hashed. Save as… writes the text side's bytes (the decoded data).

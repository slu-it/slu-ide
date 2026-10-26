# 0098. Windows-1252 fallback for text that isn't UTF-8

- Status: accepted
- Date: 2026-10-26

## Context
Thoreau read every text file as UTF-8. A file whose first 8 KiB were not valid UTF-8 was no text, so no viewer opened
it; one that turned invalid later was shown read-only with replacement characters. Old `.properties` files, Windows
batch files, CSV exports and Latin-1 sources are still written in a single-byte Western encoding, and could neither be
read nor edited. Saving always wrote UTF-8 (`Files.writeString`). The http-client already decoded response bodies
without a declared charset as windows-1252 (`ResponseFormatter`), but nothing else did, and the git diffs and change
markers decoded file contents as UTF-8 on their own.

## Decision
- **UTF-8 first, then Windows-1252** (`text-editing`'s `TextEncoding.kt`, shared by file detection, code views, the
  side-by-side diff, the gitignore dots and the git diffs): `decodeText(bytes)` decodes strictly as UTF-8; if that
  fails, as Windows-1252. UTF-8 stays first because valid UTF-8 is almost never accidental for text that is anything
  else. Windows-1252 is the fallback because browsers use it (the WHATWG Encoding standard maps `ISO-8859-1` labels to
  it) and the http-client already does: it is a superset of Latin-1's printable characters with `€`, curly quotes and
  dashes in `0x80`–`0x9F`. No guessing between several single-byte encodings.
- **Round-trip check**: the bytes are Windows-1252 only if decoding and encoding them again gives the same bytes. The
  five bytes undefined in Windows-1252 (`0x81 0x8D 0x8F 0x90 0x9D`) decode to replacement characters and fail it;
  `decodeText` then gives `null`, and the code view shows today's read-only banner with replacement characters.
- **Control-byte heuristic for detection** (`isTextSample`): a sample with a NUL byte is binary, as before; valid UTF-8
  is text; otherwise it is text only if it has no C0 control byte other than tab, line feed, form feed, carriage return
  and escape. Latin-1 text never has the others, and NUL-free binaries almost always do. The sample does not need to
  round-trip: a file can be text by its first 8 KiB and fail later, which the code view handles at read time.
- **The view keeps the encoding** (`FileEncoding` in `CodeView`): a Windows-1252 file is edited and saved as
  Windows-1252, under a blue banner "*file* is not valid UTF-8: it is opened and saved as Windows-1252." The encoding
  follows the file on disk when it is read again (a reload; also the editor's own save coming back, e.g. a
  Windows-1252 file left with ASCII only is UTF-8 then) and stays when the file is moved.
- **Saving never replaces characters**: before writing, the text is encoded strictly; a character Windows-1252 cannot
  store fails the save with an `IOException` naming it, its code point and its line ("Line 3 contains '😀' (U+1F600),
  which Windows-1252 cannot store. Convert the file to UTF-8 first."), which the editor area shows as for any failed
  save, and the file stays as it was.
- **Convert to UTF-8 is the way out**: the banner's button switches the view to UTF-8 and marks it dirty without
  changing the text (a flag folded into `dirty`, cleared by a save); the next save writes UTF-8. There is no Dismiss:
  the encoding matters at every save. A reload that replaces the text drops a pending conversion with it; Keep mine
  keeps the view's text and its encoding, a pending conversion included; a file that stays the saved text on disk keeps
  a pending conversion until it is UTF-8 there.
- **Diffs decode both sides the same way** (`decodeTextLeniently`: `decodeText`, else UTF-8 with replacement
  characters): the git log's file diffs, the comparison with the working tree, the HEAD content of the change markers
  (`GitCliStatus.headContent` gives bytes now) and the side-by-side diff's working file. Hunks still come from git's
  byte-wise diff, so line numbers are unaffected.
- **Files read beside the shown one decode the same way** (file-viewers' public `readTextFile(file)`, `null` for a file
  that is neither): the HTTP client's environment files, for the requests' environments (`Environments`, which reads
  such a file with replacement characters, as its tab shows it) and for the other file of the environment form
  (`EnvironmentFileView`, which turns read-only with "*file* is neither UTF-8 nor Windows-1252"). The form writes the
  other file through its tab (`FileViewHost.editText`), so it is saved in that view's encoding; only a missing file is
  created, as `{}`.

## Consequences
- Windows-1252 files open, edit and save byte for byte; every viewer that uses `isTextFile`/`readTextSample` gets the
  fallback without changes. `git` depends on the foundation module `text-editing`.
- A text that is valid UTF-8 and was meant as Windows-1252 (e.g. `Ã¤`) reads as UTF-8; such files are rare.
- Known gaps: no UTF-16 (its NUL bytes make it binary) and no byte order marks; `.editorconfig`'s `charset` is not
  read; the HTML preview server still sends `charset=utf-8` (`html/PreviewServer.kt`), so a Windows-1252 page without
  `<meta charset>` previews garbled; the http-client keeps its own response decoder; a shebang is read as UTF-8
  (`firstLine`), which only matters for non-ASCII interpreter paths.
- Related: [text-editing.md](../features/text-editing.md) (the banner and save errors),
  [file-viewers.md](../features/file-viewers.md) (what is text).

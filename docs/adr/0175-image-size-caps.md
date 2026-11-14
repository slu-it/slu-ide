# 0175. Image size caps: a shared header reader refuses images too large to decode

- Status: accepted; amends [0174](0174-barcodes-tool.md) (its header reader and caps move into `image-limits`)
- Date: 2026-11-14

## Context
The Barcodes round ([ADR 0174](0174-barcodes-tool.md)) found that JavaFX allocates the whole image before it scales
it. That holds for PNG, BMP, every frame of a GIF, and progressive JPEG, which buffers all its coefficients; only
baseline JPEG decodes scaled, row by row. Barcodes therefore caps the pixel count from the file header before it
decodes. The image viewer and the TODOs panel decode images too, without such a cap. In the real app the viewer
showed the same problem:
- an 875 KB PNG claiming 30 000 × 30 000 pixels took the app from 0.5 to 2.4 GB resident, blocked the FX thread for
  1.2 s, made WebKit's render thread throw an NPE, and left a blank tab;
- a 777 KB animated GIF (60 frames of 4000 × 4000) filled the 3 GB heap and threw `OutOfMemoryError` on the JavaFX
  Application Thread, which was blocked for 4.3 s.

The viewer hands the file to a WebView as a `data:` URI, and JavaFX's WebKit decodes it with the same JavaFX loaders.
The TODOs panel decodes Markdown images, local and web, with `Image(source, true)` at full size. The header reader
lived in the `barcodes` feature module, which `previews` and `todos` must not depend on (ADR 0047). The viewer also
shows formats Barcodes does not read: WebP and ICO.

## Decision
- **A shared foundation module.** `image-limits` (package `dev.sluide.imagelimits`) is a plain Kotlin library
  without Spring and without JavaFX. It holds the header reader moved out of Barcodes' `ImageFiles`
  (`ImageHeaders.read`, giving an `ImageHeader`: width, height, frames, format, whether a baseline JPEG, and the
  pixels decoding may allocate) and the caps (`ImageLimits`). Barcodes keeps its messages and its behaviour.
- **The caps**, unchanged from ADR 0174: 40 megapixels (`MAX_PIXELS`) for PNG, BMP, GIF and WebP with all frames
  together, ICO with all entries together, and progressive JPEG; 100 megapixels (`MAX_JPEG_PIXELS`) for baseline
  JPEG. `ImageLimits.check` gives `Fits`, `TooLarge` (with the cap) or `Unreadable`; at the cap fits. Pixel counts
  that do not fit a `Long` count as `Long.MAX_VALUE`, never wrap around.
- **Fail closed.** A header the reader cannot parse is refused as `Unreadable` ("The image could not be read."), as
  its size is unknown. The reader never throws and only walks forward through the given bytes; a fuzz test of
  200 000 random mutations per format keeps that.
- **WebP:** the first chunk must be `VP8 `, `VP8L` or `VP8X`. `VP8 ` (lossy) gives the 14-bit size after the start code
  `9d 01 2a`, `VP8L` (lossless) 14 bits each of width − 1 and height − 1, `VP8X` the 24-bit canvas width − 1 and height
  − 1. All chunks are walked (little-endian sizes, padded to even, up to the data or the RIFF size); an animated `VP8X`
  counts its `ANMF` chunks as frames, and frames × canvas is capped. A still `VP8X` takes the larger of its canvas and
  its `VP8 `/`VP8L` image. A chunk cut short or past the end, an unknown first chunk, a malformed image chunk or an
  animation without frames is unreadable.
- **ICO:** the directory (a count, then 16-byte entries: width and height with 0 for 256, data size and offset). An
  entry holding a PNG has that PNG's IHDR size, a BMP entry the larger of its directory and DIB size (16-bit in a
  core header, 32-bit otherwise; the DIB's height halved, as it counts the mask rows). The header gives the largest
  entry as width × height and the entries as frames; the cap is checked against the sum of all entries' pixels
  (`ImageHeader.pixels`), since a decoder may decode any of them. No entry, a cut directory (so the count is capped
  by the data) or an entry past the end is unreadable.
- **Bounded reads.** `ImageLimits.readFile` reads regular files only, with `readNBytes(limit + 1)` and a default of
  20 MiB (`MAX_FILE_BYTES`): a larger file, a folder, a pipe or an I/O error gives null, and a huge or growing file is
  never read whole.
- **Users.** Barcodes moves onto the module with this ADR and behaves as before: it reads PNG, JPEG, GIF and BMP, and
  a dropped WebP or ICO stays unreadable whatever its size. The image viewer (`previews`) will check the bytes before
  it renders and on every reload, and show a message instead of the image when it is over the cap or unreadable,
  with no "show anyway". The TODOs panel (`todos`) will read local images with the bounded read and fetch web images
  with a bounded body, then check and decode them off the FX thread, keeping the alt text when an image is refused.
- **Out of scope:** images in the Markdown preview and in the ebook reader, which WebKit loads itself (covering them
  would need our own loader that rewrites sources), and the sizes SVGs are rasterised at.

## Consequences
- One place decides what is too large to decode; the viewer and the TODOs panel can use the Barcodes caps without a
  dependency on a feature module.
- The caps limit what is attempted; they are no guarantee about memory. An ICO is checked against the sum of its
  entries, so a large icon with many sizes may be refused although one entry alone would fit.
- Listed in [../index.md](../index.md).

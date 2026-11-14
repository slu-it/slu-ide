# Barcodes: ideas left for later

[ADR 0174](../adr/0174-barcodes-tool.md) and the [Barcodes tool](../features/barcodes.md) make and read the codes in
text and images. Left out:
- **Scan the screen:** read a code from a region of the screen or a window. Needs a screen capture (a permission on
  macOS) and a region picker; the read itself is `Reader.read` on the captured pixels.
- **Digits under EAN/UPC:** print the number below the bars in the canvas and in the PNG and SVG. Needs text
  drawing in the export (a font, or glyph paths for the SVG) and the layout of the guard bars.
- **A logo in the QR code:** an image in the middle, with error correction H. Needs an image choice, a size limit that
  keeps the code readable, and a check that decodes the result.
- **WebP and HEIC input:** JavaFX `Image` reads neither. Needs a decoder library (a native one for HEIC) and the same
  size limits before decoding.
- **A hex view of binary payloads:** show the bytes of a code that is not text. Needs the raw bytes from ZXing's result
  metadata and a hex pane next to the text side.
- **An explorer "Read Barcode" item:** read an image file from its context menu. Needs the item in `file-explorer`,
  wired through `ide` to `Barcodes.open`, plus a way to read a file without opening the tab first.
- **More formats to generate:** EAN-8, UPC-E, Code 39 and ITF (they are read already). Each needs its input rules and
  check digit in `Generator`, a picker entry and a suggested file name.
- **A transparent background:** a "none" choice for the background in PNG and SVG. Needs an alpha-aware colour picker
  and a canvas that draws a checkerboard behind the code.
- **The first frame of an animated GIF:** JavaFX gives no pixels for one, so it is refused now. Needs a GIF frame
  reader (ImageIO's GIF reader) behind the same size limits.

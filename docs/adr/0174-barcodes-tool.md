# 0174. Barcodes tool: generate and read barcodes with ZXing core, crisp module grids, ECI only beyond Latin-1

- Status: accepted; amends [0047](0047-module-dependencies-and-shared-modules.md) (`barcodes` joins the editor family); see [0110](0110-tools-menu.md), [0112](0112-codecs-tool.md)
- Date: 2026-11-14

## Context
Developers turn a URL, a Wi-Fi login or a test value into a QR code to scan it with a phone, and read the QR code or
barcode in a screenshot to see what it holds. Online generators and readers mean uploading tokens, URLs with
credentials and customer data to a web page. The Tools menu ([ADR 0110](0110-tools-menu.md)) has room for a tool
next to Codecs ([ADR 0112](0112-codecs-tool.md)). The questions are which library and which formats, how the image
stays sharp at any size, which character set the codes carry, and how reading copes with screenshots that hold
several codes, inverted codes or very large images.

## Decision
- **Module `barcodes`** in the editor family: its tab is an `EditorTab` and it will add "Show Selection as Barcode" to
  the code editors, so it depends on `editor-area` and `file-viewers`, as `codecs` does. The engine (package
  `dev.sluide.barcodes.engine`) is free of JavaFX: it works on module grids and ARGB pixel arrays.
- **ZXing core only** (`com.google.zxing:core`), not `javase`: that module adds AWT image helpers
  (`BufferedImageLuminanceSource`, `MatrixToImageWriter`) and a command-line parser, none of which the tool needs.
  The engine reads ARGB arrays, which JavaFX images give directly, and writes PNG through the JDK's `ImageIO`, as the
  clipboard images of the file explorer do. ZXing is the reference implementation in Java, Apache-licensed, with no
  dependencies of its own.
- **Formats**: QR Code, Data Matrix, Aztec, PDF417, Code 128, EAN-13 and UPC-A are generated and read; EAN-8, UPC-E,
  Code 39, Code 93, ITF and Codabar are only read (they carry little and are rarely made by hand). Formats ZXing reads
  beyond these (RSS, MaxiCode, UPC/EAN extensions) are skipped.
- **Two sides like Codecs**: a text side and an image side, and the side used last is the source. Typing generates
  the code from the text; pasting, dropping or opening an image reads it and shows what it holds. After a read,
  changing the format or an option makes the text the source again and regenerates the code from it.
- **Raw module grids, whole-pixel scale**: the engine takes ZXing's module matrix with one entry per module (QR's
  `Encoder`, Aztec's `Encoder`, the `PDF417` encoder, the linear writers' `encode`), never a bitmap ZXing scaled. The
  export scales every module by the same whole number of pixels, so edges stay crisp in PNG and SVG; the image is
  therefore up to one module step smaller than the size asked for. Linear codes get bars 0.4 times the width high and
  a quiet zone left and right only. Default quiet zones: QR 4 modules, Data Matrix and Aztec 1, PDF417 2, linear 10.
  Data Matrix symbols are always square, because phone apps expect that; ZXing would pick a rectangle where it is
  smaller.
- **Size limits**: Code 128 takes at most 80 characters (ZXing has no limit, and scanners give up long before). A
  code over 4 096 modules per side with its quiet zone is refused as too large to draw, and the export refuses images
  over 16 384 px per side, so that Copy and Save on the JavaFX thread cannot run out of memory.
- **Character set**: text that fits in ISO-8859-1, the default of the 2D formats, is written without a character set,
  so no ECI is written and old scanners read it. Other text goes as UTF-8 behind an ECI. ZXing's encoders that switch
  modes per character check every UTF-16 unit on its own and refuse characters outside the BMP (emoji), so Data
  Matrix writes such text as one Base256 field of UTF-8 bytes (codewords assembled from ZXing's parts) and PDF417 in
  byte compaction.
- **Errors say why and where**: too long names the format, the error correction and the length; for QR also the
  maximum of version 40 in the mode the text needs (bytes, characters or digits). Data Matrix, Aztec and PDF417 name
  only the byte count, because their maximum depends on how the encoder packs the text. EAN-13 and UPC-A check
  digits, length and check digit (appending it when missing); Code 128 takes ASCII only. A position is given as
  "(character N)" and as the index for the UI; counts have a space between the thousands ("1 273"), as in Codecs.
- **Reading**: ZXing's `GenericMultipleBarcodeReader` over `MultiFormatReader` plus `QRCodeMultiReader`, with
  `TRY_HARDER`, merged and deduplicated by format, text and overlapping position, ordered top to bottom by rows, then
  left to right. A second pass reads the inverted image; ZXing's `ALSO_INVERTED` hint is not used because it covers
  neither the multi-QR reader nor the rows the linear readers scan, and it flips the shared bit matrix in place.
  Transparent pixels are composited over white. Images with a side over 4096 px are scaled down by area averaging
  first; images under 8 px on a side hold no code.
- **Data Matrix and Aztec away from the centre**: ZXing's detectors for both grow a white-bordered rectangle from the
  image centre, so they only find a code that covers the centre. A tiled pass after the whole-image readers, normal
  and inverted, crops windows of the same black matrix around a grid of centres: a window of side T finds the codes
  that cover its centre and are smaller than T/2, so centres are T/4 apart (windows overlap by three quarters, not by
  half) and every level covers codes from T/4 to T/2. T starts at 4 times the smallest code looked for (24 px, or 1/64
  of the longer side) and doubles up to the image size. On a 4096 × 4096 image this adds about 0.3 s.
- **Bounds, not result points**: a found code carries an axis-aligned box in the original image's pixels that covers
  the whole symbol, because ZXing's result points are not its corners. QR: the finder centres extended by 3.5 modules,
  the module size from the finder distance and the version, which follows from the number of data codewords at the
  decoded error correction level. Data Matrix: the corner-module centres plus 1/18 of the longer side (half a module
  of the smallest symbol, so never too little). Aztec and PDF417: the hull of their points. ZXing's PDF417 reader
  also tries the matrix turned by 90°, 180° and 270° and reports the points of the turned matrix; a wrapper around
  `MultiFormatReader` turns them back in the frame of the bitmap it was handed, before `GenericMultipleBarcodeReader`
  adds its crop offset. Linear codes: the scan line extended to the ends of the symbol by the modules each format has
  outside its two points (exact for EAN, UPC and Code 128, estimated from the text length for Code 39, Code 93 and
  ITF). ZXing finds the first scan line near the top or bottom of the bars, so the rows above and below it, one module
  apart, are decoded with the same format's row reader within the code's x range and quiet zone, on the same
  binarizer, until one gives another text or none; the box spans the matching rows. When no other row decodes, it is
  0.4 times as high as long, centred on the scan line. Boxes are clamped to the image.
- **Settings**: the options (format, error correction, size, quiet zone, colours) are stored user-wide in
  `~/.sluide/shared.json` ([ADR 0101](0101-shared-data-file.md)), field `barcodes`; the text never is, because
  tokens and passwords end up in it.

## Consequences
- Nothing leaves the machine: generating and reading run in the process.
- EAN-8, UPC-E, Code 39, Code 93, ITF and Codabar cannot be generated; an EAN-13 starting with 0 reads back as UPC-A,
  because it is the same symbol.
- Data Matrix and PDF417 hold less mixed text with non-Latin-1 characters than ZXing's mode-switching encoders would,
  because such text goes as bytes.
- `GenericMultipleBarcodeReader` finds only a few linear codes per image: it splits the image around the first code
  it finds and gives up on a part after a few levels (9 of 20 EAN-13 codes in one test image). Sheets of labels are
  read better one at a time.
- Data Matrix and Aztec codes smaller than the smallest tile (24 px, more in large images) are found only at the image
  centre.
- Whether phone scanners read the codes cannot be verified in CI; the tests read every generated format back with
  ZXing.
- Reading from the UI: image files are refused above 20 MiB and decoded (JavaFX `Image`) no larger than 4096 px on the
  longer side, the size taken from the file header before decoding. JavaFX's scaled decode does not bound memory for
  PNG, GIF and BMP (it allocates the whole image first, every frame of a GIF at full size), so the header's size is
  capped: 40 megapixels for those (a GIF counts all its frames, from walking its blocks, and an animated GIF is refused outright, as JavaFX gives no pixels for it; 8K screenshots fit) and for
  progressive JPEG (it buffers all its coefficients), 100 for baseline JPEG, which JavaFX decodes scaled. Pasted and
  dropped images, already decoded, get the 40 megapixels too. This is a cap on what is attempted, not a guarantee
  about memory; a decode that still fails (an `OutOfMemoryError` too) ends as "The image could not be
  read.". At most 20 MiB of a regular file are read. A read runs on a `NamedThreads` thread and gets no
  `BackgroundTasks` row: the status line shows "Reading…".
- Listed in [../index.md](../index.md).

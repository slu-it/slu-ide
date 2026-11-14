# Barcodes tool

**Modules:** `modules/barcodes` (package `dev.sluide.barcodes`: `BarcodeOptionsStore` and the `Barcodes` window bean,
the ZXing-based FX-free engine in `engine`, the FX-free state in `model`, UI in `ui`); menu in `ide` (`WindowTools`);
decisions in [ADR 0174](../adr/0174-barcodes-tool.md) and [ADR 0110](../adr/0110-tools-menu.md)

The Barcodes tool makes QR codes and six other barcodes from text, so that a URL, a Wi-Fi login or a test value does not
have to go to an online generator. It is a tab of the editor area with the options of the format at the top and two
sides: the text at the left, the generated code at the right. Reading codes from images, templates and the structured
view are not part of this first version.

## Opening
- **Tools → Barcodes** (last in the Tools menu) opens the tab, titled "Barcodes". There is one tab per window:
  choosing the item again selects the open tab.
- Shortcut+W closes the tab without asking. The text, format and options belong to the window: when the tab is closed
  and opened again, they are as they were. After a restart the tool starts with an empty text.

## Two sides
- **Text** (left): a text area with **Copy** and **Clear**. Every change makes the code again. The status line shows the
  number of characters, the number of UTF-8 bytes and what was made ("QR version 1 · 21×21 · ECC M"), or the error.
- **Code** (right): the generated code on a themed background, centred. The code is drawn from its module grid with a
  whole number of pixels per module where the space allows, so its edges stay sharp; in less space it is drawn
  smaller (on a scaled display, with whole device pixels). Without text, the pane says "Type text, or paste an image (Shortcut+V)" (pasting is not yet available).
  The line under it shows the colour warning, if any.

## Formats and options
The format picker offers QR Code, Data Matrix, Aztec, PDF417, Code 128, EAN-13 and UPC-A. Next to it:
- **ECC** (QR Code only): L, M, Q or H; default M.
- **ECC %** (Aztec only): 5 to 95; default 23.
- **Level** (PDF417 only): 0 to 8; default 2.
- **Size**: the export width in pixels, 256 to 4096 in steps of 64; default 512. The export scales every module by a
  whole number of pixels, so the image can be a little smaller (see ADR 0174).
- **Number fields** take the typed value when you press Enter, step with the arrows or leave the field: text that is
  no number keeps the old value, a number beyond the range is set to the limit.
- **Quiet zone**: the margin in modules, 0 to 10; the field shows the format's default (QR 4, Data Matrix and Aztec 1,
  PDF417 2, linear codes 10) until changed.
- **Foreground** and **Background**: colour pickers, black on white by default. The status warns about low contrast
  and about a light foreground on a dark background, but does not block.

## Errors
- Empty text gives no code and no error.
- When the text cannot be made into a code ("Too long for QR Code at ECC H: 1 300 of 1 273 bytes", "EAN-13 takes digits
  only: 'a' (character 3)"), the status line shows why, the last good code stays shown at 35 % opacity, and a click on
  the message selects the character it names.

## Copy and Save
- **Copy** puts the code as an image of the export size on the system clipboard.
- **Save as…** asks for a file with the filters "PNG image (*.png)" and "SVG image (*.svg)"; the extension of the
  chosen name decides the format. Suggested names: `qr-code.png`, `data-matrix.png`, `aztec.png`, `pdf417.png`,
  `code-128.png`, `ean-13.png`, `upc-a.png`.
- Both are disabled without a code and while an error shows (the code still on screen is the last good one, not the
  text's). Save as… writes through a temporary file, so a failed write leaves an existing file whole. An error (an image over 16 384 px per side, a file that cannot be written) shows in
  the status line under the code.

## What is stored
The format and options (error correction, size, quiet zone, colours) are stored user-wide in `shared.json`, field
`barcodes`, shortly after they change (changes within 300 ms end in one write, off the FX thread; quitting writes what is
pending); only values that differ from the defaults are written. Never the text.

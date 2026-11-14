# Barcodes tool

**Modules:** `modules/barcodes` (package `dev.sluide.barcodes`: `BarcodeOptionsStore` and the `Barcodes` window bean,
the ZXing-based FX-free engine in `engine`, the FX-free state in `model`, UI in `ui`); menu in `ide` (`WindowTools`);
decisions in [ADR 0174](../adr/0174-barcodes-tool.md) and [ADR 0110](../adr/0110-tools-menu.md)

The Barcodes tool makes QR codes and six other barcodes from text, so that a URL, a Wi-Fi login or a test value does not
have to go to an online generator. It is a tab of the editor area with the options of the format at the top and two
sides: the text at the left, the generated code or a read image at the right. Below the text a structured view says
what the text is, and a Template menu fills in Wi-Fi and contact texts.

## Opening
- **Tools → Barcodes** (last in the Tools menu) opens the tab, titled "Barcodes". There is one tab per window:
  choosing the item again selects the open tab.
- Shortcut+W closes the tab without asking. The text, format and options belong to the window: when the tab is closed
  and opened again, they are as they were. After a restart the tool starts with an empty text.
- **Show Selection as Barcode** in the context menu of a code editor (after the Translate items) opens the tab with
  the selection as the text, as typed text (the code is made in the current format). It is disabled without a
  selection, and a selection of more than 4 096 characters reads "Show Selection as Barcode (too long)" and is disabled.

## Two sides
- **Text** (left): a text area with **Copy** and **Clear**. Every change makes the code again. The status line shows the
  number of characters, the number of UTF-8 bytes and what was made ("QR version 1 · 21×21 · ECC M"), or the error.
- **Code** (right): the generated code on a themed background, centred. The code is drawn from its module grid with a
  whole number of pixels per module where the space allows, so its edges stay sharp; in less space it is drawn
  smaller (on a scaled display, with whole device pixels). Without text, the pane says "Type text, or paste an image
  (Shortcut+V)".
  The line under it shows the colour warning, if any.

## Structured view
Below the text, shown only while the text is more than plain text: Wi-Fi, contact (vCard or MECARD), email, phone, SMS,
location, calendar event, one-time password (`otpauth`) and link; texts over 8 192 characters are plain. It has the kind
as a small bold heading and a row per field: the label (dimmed), the value as selectable read-only text (a value with
line breaks, such as an event description, in a wrapping area up to four rows high) and a **Copy** button. Wi-Fi
passwords and `otpauth` secrets show as `••••••••` with an eye button (Show/Hide); the value is masked again whenever
the text changes, and Copy copies the real value. The view takes at most a third of the side's height and scrolls beyond
that. It describes the text whatever its source: typed, from a template or read from an image.

## Open in Browser
A button next to the "Text" title, shown only while the text, trimmed, is one http or https URL. A click opens it in the
system browser; nothing opens on its own.

## Templates
**Template ▾** at the right of the toolbar has two items, each opening a dialog owned by the window with the buttons
**Insert** (default) and Cancel:
- **Wi-Fi…** ("Wi-Fi Template"): Network (required: Insert is disabled while empty), Security (WPA/WPA2/WPA3, WEP,
  None; default WPA), Password (hidden, with an eye toggle; disabled for None) and Hidden network. It writes
  `WIFI:T:WPA;S:…;P:…;H:true;;` with backslash, semicolon, comma, colon and double quote escaped.
- **Contact…** ("Contact Template"): Name, Organization, Phone, Email and Website (Insert is disabled while all are
  empty). It writes a vCard 3.0.

Each dialog is prefilled when the current text parses as that type. Insert replaces the whole text like an edit of the
user: Undo (Shortcut+Z in the text) brings the old text back, and the text becomes the source (a read image is replaced
by the generated code).

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
  the message selects the character it names. Text over 7 089 characters (the most any format holds) is refused up
  front: "Too long for Aztec: 7 090 characters; no barcode holds more than 7 089".

## Reading
A code in an image is read from four inputs, all above the right side: **Paste Image** (the clipboard image; without
one, "The clipboard holds no image."), **Shortcut+V** anywhere in the tab (reads when the clipboard holds an image and
no text, otherwise the text area pastes), **Open…** (filter "Images (*.png, *.jpg, *.jpeg, *.gif, *.bmp)") and a
**drop** on the right side (a file, or a raw image; with several files the first is read and the status says so).
- **Limits**: files over 20 MiB are refused ("The image is larger than 20 MiB."); a file is decoded no larger than
  4096 px on the longer side (larger ones scaled down, smaller ones not enlarged), and that decoded image is shown and
  read. Only regular files are read, and at most 20 MiB of one. From the header, before any decoding, a PNG, GIF or
  BMP over 40 megapixels (a GIF: all its frames together, "60 frames of 4 000 × 4 000 pixels"), a progressive JPEG
  over 40 and a baseline JPEG over 100 megapixels are refused ("The image is too large to read (30 000 × 30 000
  pixels; at most 40 megapixels)."): JavaFX allocates a whole PNG, GIF or BMP before it scales it, and a progressive
  JPEG buffers all its data; a baseline JPEG it decodes scaled. JavaFX gives no pixels for an animated GIF,
  so one with more than one frame is refused from its header with "Animated GIFs cannot be read; save one frame as
  PNG." (after the size check: too many pixels in all frames gets the size message). The 40 megapixels also apply
  to pasted and dropped images. An unreadable or corrupt image shows "The image could not be read." and nothing else
  changes.
- **In the background**: the status says "Reading…" while the image is decoded and read off the UI thread. A newer
  read, an edit of the text, or a change of format or option makes a pending read void.
- **Outlines**: the image is fitted and centred, smoothly scaled. Each code found gets an outline with a dark halo
  (3 px, bright blue, for the selected one; 2 px at 60 % for the others), and with two or more codes a numbered badge
  at its top-left corner. Clicking an outline selects that code: its text, and its format if it can be generated, show
  at the left. The hand cursor shows over an outline when there are several codes; with one code nothing is picked.
- **Statuses**: "Read from clipboard · 2 codes · QR Code" (the format is the selected code's; the origin is "clipboard",
  the file name or "dropped image"), with several codes followed by "· click an outline to pick one"; "No barcode found"
  leaves everything as it was. Copy and Save as… stay disabled while an image is the source (tooltip: "Edit the text or
  change an option to make a code"); editing the text or an option makes a generated code again.
- **Formats read**: QR, Data Matrix, Aztec, PDF417, EAN-8, EAN-13, UPC-A, UPC-E, Code 39, Code 93, Code 128, ITF and
  Codabar, also inverted.

## Copy and Save
- **Copy** puts the code as an image of the export size on the system clipboard.
- **Save as…** asks for a file with the filters "PNG image (*.png)" and "SVG image (*.svg)"; the extension of the
  chosen name decides the format. Suggested names: `qr-code.png`, `data-matrix.png`, `aztec.png`, `pdf417.png`,
  `code-128.png`, `ean-13.png`, `upc-a.png`.
- Both are disabled without a code and while an error shows (the code still on screen is the last good one, not the
  text's). Save as… writes through a temporary file, so a failed write leaves an existing file whole. An error (an
  image over 16 384 px per side, a file that cannot be written) shows in the status line under the code.

## What is stored
The format and options (error correction, size, quiet zone, colours) are stored user-wide in `shared.json`, field
`barcodes`, shortly after they change (changes within 300 ms end in one write, off the FX thread; quitting writes what
is pending); only values that differ from the defaults are written. Never the text.

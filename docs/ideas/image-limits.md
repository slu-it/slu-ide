# Image size caps: ideas left for later

[ADR 0175](../adr/0175-image-size-caps.md) caps the images the image viewer, the TODOs panel and the Barcodes tool
decode, from the header before decoding. Left out:
- **Markdown preview and ebook reader:** WebKit loads their `<img>` itself, at full size, through the same JavaFX
  decoders. Covering them needs our own loader: rewrite each source to a `data:` URI after the header check, or to a
  placeholder that says why, before the page reaches WebKit (the ebook reader already serves its resources itself).
- **SVG raster sizes:** an SVG with a huge `width`/`height` drawn by WebKit, or exported as PNG, could allocate a large
  bitmap; cap the raster size the way `PngSizeDialog` caps exports.
- **Image viewer reads the whole file:** it reads up to 50 MB with `readBytes` on the FX thread after a size check,
  not through `ImageLimits.readFile`; reading bounded and off the FX thread would match the rule.
- **TODOs decode at full size:** three images just under the cap can be decoded at once (about 3 × 160 MB) and stay
  in memory at full size; a requested decode width (the panel's width) would cut that.
- **Local TODO images over 20 MiB** say "could not be loaded", not "larger than 20 MiB": `ImageLimits.readFile`
  returns null for both; a result type that keeps "too large" apart would let every user say why.

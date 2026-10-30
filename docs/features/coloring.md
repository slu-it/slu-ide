# Coloring tool

**Modules:** `modules/coloring` (package `dev.sluide.coloring`, UI in `dev.sluide.coloring.ui`); menu in `ide`
(`ToolsMenu`, `WindowTools`); decisions in [ADR 0109](../adr/0109-coloring-tool.md),
[ADR 0110](../adr/0110-tools-menu.md) and [ADR 0111](../adr/0111-coloring-slots-and-inspector.md)

The Coloring tool works with colors in all CSS notations: it compares colors, derives shades, harmonies, mixes and
gradients, shows how people with color vision deficiencies see them, and shows any color in each notation. It is a tab
of the editor area with three columns: the inspector at the left, the tools in tabs in the center (each scrolls when
it is taller than the window) and the picker for the slot being edited at the right.

## Slots
Each tool tab names the colors it works on, its **slots**: Contrast has Text and Background (Vision shows the same
pair), Shades & Harmonies has Base, Mix has Color 1 and Color 2 and, for the opacity, Color and Background, Gradient
has the selected stop. A slot shows its label, a swatch and a field. A click on the swatch, a press in the field or the
focus in it makes it the slot being edited, marked with an accent ring; the picker column then edits it. Each tool tab
remembers the slot last edited in it, so switching tabs edits that slot (at first the tab's first slot).

## Opening
- **Tools → Coloring** opens the tab. There is one Coloring tab per window: choosing the item again selects the open
  tab.
- Shortcut+W closes the tab. It is not restored when the project is opened again.
- The slots and the gradient belong to the window, not to the tab: when the tab is closed and opened again, they are
  as they were.

## Picker column (right): the slot being edited

### Input
"Editing: <tab> › <slot>" at the top names the slot being edited. The field below reads any CSS color notation as you
type: hex (a bare `3b82f6` and a trailing `;` too), `rgb()`, `hsl()`, `hwb()`, `lab()`, `lch()`, `oklab()`, `oklch()`,
`color()`, named colors, and the non-CSS `hsv()` and `cmyk()`. A valid text becomes the slot's color; text that is no
color marks the field with a red border and leaves the color unchanged (blank text is neither). When the color changes
elsewhere the field shows its CSS text, except while the typed text already denotes that color. Escape restores the text
of the slot's color, Enter selects all. Below the field a 56 px preview shows the color on a checkerboard (so alpha is
visible).

### Picker
The picker sets the color of the slot being edited in two modes, switched by the Square | Wheel buttons (Square by
default), with an alpha slider below both.

- **Square**: saturation (left to right) and value (bottom to top) for the current hue, and a vertical hue strip
  beside it.
- **Wheel**: a disc where the angle is the hue and the distance from the center the saturation, with a vertical value
  slider beside it. A drag outside the disc clamps to the rim.
- **Alpha**: a slider on a checkerboard from transparent to the opaque color.

Pressing and dragging set the color live and stay inside the control. The picker keeps its own hue, saturation, value
and alpha, so the hue does not jump when the color becomes gray or black while dragging, and a gray or black color set
from elsewhere keeps the last hue. The color it sets is in HSV (`hsv()` coordinates); a color set from elsewhere is
gamut-mapped into sRGB first if needed.

### Saved colors
The section at the bottom of the picker column lists the colors saved with the inspector's Save button. Nothing is saved
automatically, there is no history. The colors are stored per project in the project data (field `colors`), or in
`shared.json` in a window without a folder. A color is saved once: the same 8-bit color in another notation counts as
saved. Click a swatch to write it into the slot being edited; right-click it and choose Remove to delete it. All windows
of a project show the same saved colors and follow changes made in another window. If the colors cannot be written, a
muted message says so and the log has the reason.

## Inspector
The column at the left shows one color: a click on a derived swatch (a shade, a harmony, a mix, a suggestion, a
simulated color) shows it here and changes no slot; otherwise it follows the slot being edited. It has a large preview,
a note when the color lies outside sRGB and is shown mapped into the gamut, and "Name: x" for an exact named color or
"Nearest name: x" for the closest by deltaE OK (the distance in the tooltip); a click on the name shows that named color
here, keeping the inspected color's alpha (only Use for writes into a slot). The formats follow in groups (CSS, Modern
CSS, Design tools) with a selectable text and a copy button per row; a "≈" marks a text that is the gamut-mapped color
of one outside sRGB, and the Name row appears only for an exact named color. **Save** (star icon) saves the color in the
project, disabled while it is already saved; **Use for <tab> › <slot>** writes it into the slot being edited, disabled
while the slot has that color.

## Contrast
Top to bottom: the Text slot, the Background slot with White and Black (they set the background), Swap (exchanges the
two colors), then a sample: the background with normal (16 px) and large (24 px bold) text and a 1 px outlined UI
element in the text color.

WCAG 2 shows the ratio truncated to two decimals and never rounded up (4.4999 is "4.49 : 1", as WCAG demands), and Pass
or Fail for AA normal (4.5), AA large (3), AAA normal (7), AAA large (4.5) and UI components (3); the unrounded ratio is
compared. APCA shows "Lc 63.1" with its sign, informational only (a WCAG 3 draft); its tooltip gives rough guidance (75
body text, 60 content text, 45 large or bold, 30 non-text).

If AA normal fails, the pane offers the nearest color passing AA (if only AAA fails, the nearest passing AAA) for the
slot being edited: a new background while the Background slot is edited ("Nearest background passing AA:"), a new text
color otherwise. It changes the OKLCH lightness and keeps the hue (chroma may shrink to stay in sRGB) and the other
slot; a translucent color is measured as it is composited. A click on its swatch shows it in the inspector; "Use"
writes it into that slot. If no lightness reaches it, the pane says so.

## Shades & Harmonies
The Base slot is at the top; the shades and harmonies below follow it and wrap in a narrow column.

- **Shades**: eleven steps, 50 to 950, Tailwind-like: each step has a fixed OKLCH lightness and keeps the hue of the
  Base slot; chroma follows a bell curve around the middle steps, gamut-mapped to sRGB. The step nearest in
  lightness is the base color itself, exact, and is underlined. A click on a step shows it in the inspector.
- **Copy as CSS variables** copies one `--color-50: #f0f6ff;` line per step; the field next to the button changes the
  prefix (`color`).
- **Harmonies**: Complementary (+180), Analogous (-30, +30), Triadic (+120, +240) and Split complementary (+150, +210),
  hue rotations in OKLCH with lightness and chroma kept. Each row starts with the base color; the text below a
  swatch is its CSS color. A click shows a swatch in the inspector. A gray has no hue, so all its harmonies are the
  gray.

## Mix
Two sections, one below the other.

- **Mix**: the slots Color 1 and Color 2 are mixed with the slider "Amount of Color 2" (0 to 100 %, default 50) in
  sRGB, Oklab or OKLCH (default). The result swatch shows the mix, with the CSS `color-mix(in oklch, <color 1> 50%,
  <color 2> 50%)` and the result's color, each with a copy button. Both percentages are always written. The strip below
  shows the mixes at 10 % to 90 % in the chosen space. A click on a result or strip swatch shows it in the inspector.
- **Opacity over a background**: the slot Color at the slider "Opacity" (0 to 100 %, multiplied with its own alpha)
  over the slot Background (default white) is flattened to one color, composited on the sRGB components as browsers
  do; it is solid only over an opaque background. Useful for the solid color of a translucent overlay. A click on the
  result shows it in the inspector.

The mix shown is that of the colors as the emitted CSS text reads them (8-bit hex, rounded `oklch()`), so it equals
what a browser renders for the `color-mix()` text.

## Gradient
A CSS gradient builder. It starts as a linear gradient at 90deg from #3b82f6 to #8b5cf6, interpolated in OKLCH, and is
shown large on a checkerboard. A click on the preview shows the color at that point in the inspector (along the
gradient line, by the distance from the center, or by the angle from the start angle); it changes no slot.

- **Type:** Linear, Radial (a circle to the farthest corner) or Conic. The angle (slider and number field, 0 to 360)
  is the direction of a linear gradient (0deg points up, 90deg to the right, so the corners lie on 0 % and 100 %) or
  the `from` angle of a conic one; it is disabled for Radial.
- **Interpolation:** sRGB (the CSS default, left out of the text unless a stop is in a modern notation such as
  `oklch()`, which browsers would then interpolate in Oklab, so `in srgb` is written), OKLab or OKLCH, written as
  `in oklch`. Mixing in sRGB passes through a dull gray between complementary colors such as blue and yellow; OKLCH
  interpolates lightness and chroma and turns the hue along the shorter arc, so the middle stays colorful. The preview
  is drawn from the interpolated colors, so it shows the chosen space, and colors outside sRGB are clipped per channel,
  as browsers paint gradients.
- **Stops:** a bar under the preview has a handle per stop. Click one to select it and edit it as the slot Stop
  ("Stop 2 of 3" beside it says which), drag it to move it (it may pass the other stops; two at one position make a
  sharp edge). The selected stop's color is edited in the slot's field, in any notation (`:invalid` while the text is
  not a color: the stop keeps its last valid color and the mark stays until the text is corrected or Escape restores
  the stop's text), or in the picker column. **Add stop** inserts a stop in the middle of the widest gap with the
  interpolated color there, selects it and edits it; **Remove stop** removes the selected one (two stay).
- **Output:** the CSS in a read-only field, for example `linear-gradient(in oklch 90deg, #3b82f6 0%, #8b5cf6 100%)`,
  with a button that copies it to the clipboard.

The gradient belongs to the window, like the slots: it outlives the tab.

## Vision
Shows how people with color-vision deficiencies see the Contrast pair. The Text and Background slots at the top are the
Contrast tab's (editing them here changes them there too); the Vision tab remembers which of them it edits. The
simulation applies the Machado et al. 2009 matrices at full severity on linear sRGB (protanopia, deuteranopia,
tritanopia) and maps the luminance to a gray for achromatopsia.

One row each for normal vision, protanopia, deuteranopia, tritanopia and achromatopsia (a tooltip on the label
describes the deficiency): the simulated pair as an "Aa" sample, the simulated text and background as swatches with
their CSS texts (a click shows one in the inspector), and the WCAG ratio, truncated to two decimals, with a Pass AA /
Fail AA badge. A translucent pair is composited first (background over white, text over that background) and simulated
afterwards, as a viewer sees the pixels, so the simulated colors are opaque. When the pair passes AA with normal vision
but fails under a deficiency, a line below names the deficiencies and asks for a bigger lightness difference.

## In the code editor
Code editors of the window (also those embedded in other views) show the colors written in style files (`.css`, `.scss`,
`.sass`, `.less`), markup (`.html`, `.htm`, `.xhtml`, `.svg`, `.xml`), scripts (`.js`, `.mjs`, `.cjs`, `.ts`, `.mts`,
`.cts`, `.jsx`, `.tsx`) and components (`.vue`, `.svelte`); other files show none, and a file renamed to or away from
such a type follows. A line with colors gets a small swatch (on a checkerboard, so that alpha shows) in the gutter,
right of the line numbers and change markers. It shows the first color of the line; its tooltip lists the color texts
of the line (ten at most, then "…and N more"). A click on the swatch shows that color in the Coloring tool's
inspector (no slot changes) and shows its tab; it does not move the caret. Edited rows update at once, and only the
lines on screen are looked at.

The editor's context menu has "Open "#3b82f6" in Coloring" after the editing items for a caret on or just after a
color (disabled, "Open in Coloring", elsewhere in these files); it shows that color in the tool's inspector.

Colors are found line by line, without tracking comments or strings that span lines, so a few false hits and misses
remain, for example a hex color in a block comment or a selector like `#abc`; lines over 5,000 characters show none.
Details and limits: [ADR 0109](../adr/0109-coloring-tool.md), `ColorFinder`.

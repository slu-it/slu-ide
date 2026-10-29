# Coloring tool

**Modules:** `modules/coloring` (package `dev.sluide.coloring`, UI in `dev.sluide.coloring.ui`); menu in `ide`
(`ToolsMenu`, `WindowTools`); decisions in [ADR 0109](../adr/0109-coloring-tool.md) and
[ADR 0110](../adr/0110-tools-menu.md)

The Coloring tool works with colors in all CSS notations: it shows the current color in each of them, compares it with
a second color, derives shades, harmonies, mixes and gradients, and shows how people with color vision deficiencies
see it. It is a tab of the editor area with the sections of the current color in a column at the left and the tools in
tabs at the right.

## Opening
- **Tools → Coloring** opens the tab. There is one Coloring tab per window: choosing the item again selects the open
  tab.
- Shortcut+W closes the tab. It is not restored when the project is opened again.
- The current color and the second color belong to the window, not to the tab: when the tab is closed and opened
  again, they are as they were.

## Current color

### Input and formats
The field at the top of the left column reads any CSS color notation as you type: hex (a bare `3b82f6` and a
trailing `;` too), `rgb()`, `hsl()`, `hwb()`, `lab()`, `lch()`, `oklab()`, `oklch()`, `color()`, named colors, and the
non-CSS `hsv()` and `cmyk()`. A valid text becomes the current color; text that is no color marks the field with a red
border and leaves the color unchanged (blank text is neither). When the color changes elsewhere the field shows its CSS
text, except while the typed text already denotes that color. Escape restores the text of the current color, Enter
selects all.

Below the field a 56 px preview shows the color on a checkerboard (so alpha is visible). A note appears when the color
lies outside sRGB and is shown mapped into the gamut. "Name: x" shows an exact named color, "Nearest name: x" the closest
by deltaE OK (the distance in the tooltip); a click on the name makes that named color current, keeping the alpha.

### Picker
(described with its implementation)

### Saved colors
The section at the bottom of the left column keeps colors you save explicitly with the Save button (star icon; it is
disabled while the current color is already saved). Nothing is saved automatically, there is no history. The colors are
stored per project in the project data (field `colors`), or in `shared.json` in a window without a folder. A color is
saved once: the same 8-bit color in another notation counts as saved. Click a swatch to make it the current color;
right-click it and choose Remove to delete it. All windows of a project show the same saved colors and follow changes
made in another window. If the colors cannot be written, a muted message under the swatches says so and the log has
the reason.

## Contrast
(described with its implementation)

## Shades & Harmonies
(described with its implementation)

## Mix
(described with its implementation)

## Gradient
(described with its implementation)

## Vision
(described with its implementation)

## In the code editor
(described with its implementation)

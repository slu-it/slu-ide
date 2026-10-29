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

The Formats section below the picker lists the color in groups (CSS, Modern CSS, Design tools) with a selectable text
and a copy button per row. A "≈" marks a text that is the gamut-mapped color of one outside sRGB. The Name row appears
only for an exact named color.

### Picker
The picker sets the current color in two modes, switched by the Square | Wheel buttons (Square by default), with an
alpha slider below both.

- **Square**: saturation (left to right) and value (bottom to top) for the current hue, and a vertical hue strip
  beside it.
- **Wheel**: a disc where the angle is the hue and the distance from the center the saturation, with a vertical value
  slider beside it. A drag outside the disc clamps to the rim.
- **Alpha**: a slider on a checkerboard from transparent to the opaque current color.

Pressing and dragging set the color live and stay inside the control. The picker keeps its own hue, saturation, value
and alpha, so the hue does not jump when the color becomes gray or black while dragging, and a gray or black color set
from elsewhere keeps the last hue. The color it sets is in HSV (`hsv()` coordinates); a color set from elsewhere is
gamut-mapped into sRGB first if needed.

### Saved colors
The section at the bottom of the left column keeps colors you save explicitly with the Save button (star icon; it is
disabled while the current color is already saved). Nothing is saved automatically, there is no history. The colors are
stored per project in the project data (field `colors`), or in `shared.json` in a window without a folder. A color is
saved once: the same 8-bit color in another notation counts as saved. Click a swatch to make it the current color;
right-click it and choose Remove to delete it. All windows of a project show the same saved colors and follow changes
made in another window. If the colors cannot be written, a muted message under the swatches says so and the log has
the reason.

## Contrast
The pane compares the current color with the second color. "Current is: Text | Background" says which role the current color has; Swap exchanges the two. The second color has a swatch (a click makes it current), a field that takes any CSS notation (invalid input is marked) and White and Black buttons. A sample shows the background with normal (16 px) and large (24 px bold) text and a 1 px outlined UI element in the text color.

WCAG 2 shows the ratio truncated to two decimals and never rounded up (4.4999 is "4.49 : 1", as WCAG demands), and Pass or Fail for AA normal (4.5), AA large (3), AAA normal (7), AAA large (4.5) and UI components (3); the unrounded ratio is compared. APCA shows "Lc 63.1" with its sign, informational only (a WCAG 3 draft); its tooltip gives rough guidance (75 body text, 60 content text, 45 large or bold, 30 non-text).

If AA normal fails, the pane offers the nearest text color passing AA, found by changing the OKLCH lightness while keeping the hue (chroma may shrink to stay in sRGB); if only AAA fails, the nearest one passing AAA. "Use" sets it as the text color, whichever of current and second that is. If no lightness reaches it, the pane says so.

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

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
- **Shades**: eleven steps, 50 to 950, Tailwind-like: each step has a fixed OKLCH lightness and keeps the hue of the
  current color; chroma follows a bell curve around the middle steps, gamut-mapped to sRGB. The step nearest in
  lightness is the current color itself, exact, and is underlined. A click on a step makes it the current color.
- **Copy as CSS variables** copies one `--color-50: #f0f6ff;` line per step; the field next to the button changes the
  prefix (`color`).
- **Harmonies**: Complementary (+180), Analogous (-30, +30), Triadic (+120, +240) and Split complementary (+150, +210),
  hue rotations in OKLCH with lightness and chroma kept. Each row starts with the current color; the text below a
  swatch is its CSS color. A click makes a swatch current. A gray has no hue, so all its harmonies are the gray.

## Mix
- **Mix**: the current color and the second color (swatch and a text field, `:invalid` on bad input) are mixed with
  the slider "Amount of second color" (0 to 100 %, default 50) in sRGB, Oklab or OKLCH (default). The result swatch
  (click: current) shows the mix, with the CSS `color-mix(in oklch, <current> 50%, <second> 50%)` and the result's
  color, each with a copy button. Both percentages are always written. The strip below shows the mixes at 10 % to
  90 % in the chosen space; a click makes one current.
- **Opacity over a background**: the current color at the slider's opacity (0 to 100 %, multiplied with its own
  alpha) over a background (default white, text field as above) is flattened to one color, composited on the
  sRGB components as browsers do; it is solid only over an opaque background. Useful for the solid color of a
  translucent overlay. The mix shown is that of the colors as the emitted CSS text reads them (8-bit hex, rounded
  `oklch()`), so it equals what a browser renders for the `color-mix()` text.

## Gradient
A CSS gradient builder. It starts as a linear gradient at 90deg from the current color to the second color,
interpolated in OKLCH, and is shown large on a checkerboard.

- **Type:** Linear, Radial (a circle to the farthest corner) or Conic. The angle (slider and number field, 0 to 360)
  is the direction of a linear gradient (0deg points up, 90deg to the right, so the corners lie on 0 % and 100 %) or
  the `from` angle of a conic one; it is disabled for Radial.
- **Interpolation:** sRGB (the CSS default, left out of the text), OKLab or OKLCH, written as `in oklch`. Mixing in
  sRGB passes through a dull gray between complementary colors such as blue and yellow; OKLCH interpolates lightness
  and chroma and turns the hue along the shorter arc, so the middle stays colorful. The preview is drawn from the
  interpolated colors, so it shows the chosen space, and colors outside sRGB are clipped per channel, as browsers
  paint gradients.
- **Stops:** a bar under the preview has a handle per stop. Click one to select it, drag it to move it (it may pass
  the other stops; two at one position make a sharp edge). The selected stop's color is edited in the text field beside its swatch, in any notation
  (`:invalid` while the text is not a color); **Set to current color** copies the current color into it,
  **Use stop as current** makes its color the current color. **Add stop** inserts a stop in the middle of the widest
  gap with the interpolated color there; **Remove stop** removes the selected one (two stay).
- **Output:** the CSS in a read-only field, for example `linear-gradient(in oklch 90deg, #3b82f6 0%, #ffffff 100%)`,
  with a button that copies it to the clipboard.

The gradient belongs to the tab, not to the window: closing the tab drops it, and opening it again starts from the
current and second color.

## Vision
Shows how people with color-vision deficiencies see the current color and the contrast pair. The simulation applies
the Machado et al. 2009 matrices at full severity on linear sRGB (protanopia, deuteranopia, tritanopia) and maps the
luminance to a gray for achromatopsia. One row each for normal vision, protanopia, deuteranopia, tritanopia and
achromatopsia (a tooltip on the label describes the deficiency). Columns: the simulated current color as a swatch
(a click makes it the current color) with its CSS text; the simulated pair as an "Aa" sample (roles as in Contrast:
`currentIsText`); its WCAG ratio, truncated to two decimals, with a Pass AA / Fail AA badge. When the pair passes AA
with normal vision but fails under a deficiency, a line below the grid names the deficiencies and asks for a bigger lightness difference.
A translucent pair is composited first (background over white, text over that background) and simulated afterwards,
as a viewer sees the pixels; the swatch keeps its alpha. The pane follows the current color, the second color and the text/background roles.

## In the code editor
Code editors of the window (also those embedded in other views) show the colors written in style files (`.css`,
`.scss`, `.sass`, `.less`), markup (`.html`, `.htm`, `.xhtml`, `.svg`, `.xml`), scripts (`.js`, `.mjs`, `.cjs`, `.ts`,
`.mts`, `.cts`, `.jsx`, `.tsx`) and components (`.vue`, `.svelte`); other files show none, and a file renamed to or
away from such a type follows. A line with colors gets a small swatch (on a checkerboard, so that alpha
shows) in the gutter, right of the line numbers and change markers. It shows the first color of the
line; its tooltip
lists the color texts of the line (ten at most, then "…and N more"). A click on the swatch makes that color the current
one of the Coloring tool and shows its tab; it does not move the caret. Edited rows update at once, and only the
lines on screen are looked at.

The editor's context menu has "Open "#3b82f6" in Coloring" after the editing items for a caret on or just after a
color (disabled, "Open in Coloring", elsewhere in these files); it opens that color in the tool.

Colors are found line by line, without tracking comments or strings that span lines, so a few false hits and misses
remain, for example a hex color in a block comment or a selector like `#abc`; lines over 5,000 characters show none.
Details and limits: [ADR 0109](../adr/0109-coloring-tool.md), `ColorFinder`.

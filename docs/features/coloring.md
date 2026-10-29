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
(described with its implementation: input, picker, formats, saved colors)

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

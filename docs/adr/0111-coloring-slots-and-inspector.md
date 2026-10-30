# 0111. Coloring: color slots, a context picker and an inspector

- Status: accepted; amends [0109](0109-coloring-tool.md) (the window keeps the slots' colors, not one current color);
  columns swapped on 2026-10-30: the inspector at the left, the picker column at the right
- Date: 2026-10-29

## Context
The first Coloring tool ([ADR 0109](0109-coloring-tool.md)) had one "current color" in the left column that drove
every tab, plus a second color typed into each tab. In use, the second color is clicked to change it, and the left
column is read as the picker for whatever is being edited. Derived colors (shades, harmonies, mix results) replaced
the current color on a click, so looking at one lost the color being worked on.

## Decision
- **Slots.** Each tab names the colors it works on: Contrast Text and Background (Vision shows the same pair),
  Shades & Harmonies Base, Mix Color 1 and Color 2 plus Color and Background for the opacity, Gradient the selected
  stop. A slot shows a swatch and a field; a click on either, or focus in the field, makes it the slot being edited.
  Slots are independent across tabs, live in the window (`ColoringState`) and outlive the tab, the gradient too.
- **The picker column is a context picker**: "Editing: <tab> › <slot>", a field, the preview, the picker and the saved
  colors, all on the slot being edited. Each tab remembers its slot; switching tabs edits that tab's slot. A click on
  a saved color writes it into that slot.
- **The inspector** is a fixed column at the left: the color, its formats, its nearest name, Save and "Use for
  <slot>". It shows a derived color when its swatch is clicked, and otherwise follows the slot being edited. Colors
  from the code editor (gutter swatch, "Open in Coloring") go to the inspector only and never overwrite a slot.
- **Contrast** drops the text/background role toggle; its nearest passing color replaces the slot being edited.

## Consequences
- Looking at a derived color never changes a slot; copying one into a slot is an explicit "Use for".
- Save is only in the inspector; the picker column lists the saved colors.
- Listed in [../index.md](../index.md).

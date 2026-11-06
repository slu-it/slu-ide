# 0135. Wrapping toolbars above views

- Status: accepted
- Date: 2026-11-06

## Context
Since the split editor area ([ADR 0134](0134-split-editor-area.md)) a side can be half the window or narrower, and
every view clips its content at the side's edge. About twenty toolbars at the top of editor tabs (previews, the code
view's row, diff, `.env`, Dockerfile, Compose, Gradle, shell scripts, HTTP, Read Aloud, Translate, databases, games,
Codecs and Converting) are plain `HBox`es with a growing spacer between their left and right controls. Narrower than
the toolbar, the view cuts off its right end, which holds the controls used most (Export, document zoom, the
Source/Split/Preview toggles), and the `HBox`'s minimum width pushes the split divider.

JavaFX has two ready answers. A `FlowPane` wraps, but it has one alignment for all its nodes: it cannot keep a group at
the right end. A `ToolBar` moves what does not fit into a » overflow menu, which hides controls behind a click and
turns toggle buttons into menu items.

## Decision
- A custom `Region`, the public `WrappingToolbar` in `file-viewers`' root package, replaces the `HBox`es. It has two
  lists instead of the spacer: `leading` (before the spacer) and `trailing` (after it). `children` follows them
  (leading, then trailing), so lookups and CSS keep working. Gaps are the styleable `-fx-hgap` (default 0, in place of
  `-fx-spacing` or `HBox(n)`) and `-fx-vgap` (default 2); margins are set with `WrappingToolbar.setMargin`.
- **The trailing group stays top right.** Its nodes sit at the right end of the first row. The leading nodes fill the
  first row up to it and continue on further rows, left-aligned, at full width. Only when the trailing group alone is
  wider than the toolbar does it wrap itself, each row right-aligned, and the leading nodes then fill the free space
  left of its last row (up to the group distance before that row's first node) and continue on the rows below.
  The groups are at least twice `hgap`, and at least 12 px, apart, so a toolbar with `hgap` 0 still parts them.
- **Nodes are the units.** A node wraps as a whole, so a nested `HBox` (zoom −/100%/+, Read Aloud's controls) moves as
  a group. A node wider than the toolbar gets the toolbar's width, but not less than its minimum width; beyond that the
  view clips it, as before.
- **Toggle groups are one unit.** Consecutive managed `ToggleButton`s of one non-null `ToggleGroup` in the same list
  (the Source/Split/Preview and Source/Split/Output modes) wrap together and keep `hgap` between them. A unit wider than
  the row keeps its nodes at their preferred widths and is clipped; the toolbar's minimum width stays the widest single
  node's, so such a unit is clipped rather than forcing the split pane wider. Hidden (unmanaged) nodes between the
  toggles of one group do not split them; a visible node of another kind does. Grouped `RadioButton`s merge too.
- **Separators.** A `Separator` at a row's start or end takes no width and gets the pseudo-class `row-edge`, which the
  toolbar's user-agent stylesheet makes transparent (`-fx-opacity: 0`). Its `visible` and `managed` stay untouched,
  because views bind them (`managed` to `visible` is the common pattern) and a layout that set them would fight those
  bindings or drop the node for good.
- **Margins** apply as in an `HBox`, except that a node starting a row drops its left margin (a group gap such as
  Read Aloud's 12 px would otherwise indent the row). Unmanaged nodes are skipped.
- **Sizes.** The toolbar has a horizontal content bias: its preferred height is that of its rows for the width given,
  and its minimum height equals it, so no parent squeezes rows away. `BorderPane.top` and a `VBox` honour the bias,
  so the content below moves down. Its preferred width puts everything on one row; its minimum width is the widest
  node's, so the toolbar no longer forces a minimum on its side of the split.
- The row layout is a pure function (`toolbarRows`) on the nodes' widths and margins, unit-tested without a toolkit
  ([ADR 0084](0084-testability-logic-out-of-controls.md)).

Rejected: `FlowPane` (no right-aligned group), `ToolBar`'s overflow (hides controls), and two rows always (wastes a row
in the common wide case).

## Consequences
- All toolbars at the top of editor tabs move to `WrappingToolbar`, module by module; `file-viewers`' own (code view
  row, diff, `.env`) first. A toolbar property typed `HBox` becomes `WrappingToolbar`, and tests that read
  `toolbar.children` read `leading` or `trailing`.
- Unchanged: Docker logs keep their `ToolBar` with its overflow; the Codecs/Converting option rows and Compose's flags
  stay `FlowPane`s; the database result filter row (two growing fields) and bars outside editor tabs stay `HBox`es.
- A narrow toolbar is two or more rows tall, so the view's content starts lower; nothing about the layout is stored.
- Where a row wraps depends on the fonts, so macOS wraps at other widths than Linux.

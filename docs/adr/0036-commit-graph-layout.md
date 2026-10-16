# 0036. Commit graph: own lane layout, drawn in the subject cell with CSS colours

- Status: accepted
- Date: 2026-10-16

## Context
The git log ([ADR 0035](0035-git-log-area.md)) lists commits in pages of 500, read with the git command line in
date order; every row has the full hashes of its commit's parents. Like IntelliJ's Git Log, every row should show
where its commit sits in the history: coloured lanes, edges where branches fork and merge, and a dot for the commit.
The layout has to work page by page, as the log is loaded while the user scrolls, and its colours have to follow the
light and dark themes ([ADR 0009](0009-themes.md)).

## Decision
- **Own layout from the parents, row by row** (`GraphLayout`, no JavaFX). Git's date order lists no parent before all
  its children, so one pass from the top is enough. Between two rows the layout keeps an ordered list of lanes, each
  the commit it expects next and a colour. For a row with commit C:
  - C's dot goes to the first lane expecting it; if none does, C is a branch tip and gets a new lane at the right end
    (without an edge from above).
  - Upper half (row top to the dot's middle): lanes not expecting C go straight down; all lanes expecting C converge
    into the dot (this is where branches forked off).
  - Lower half (the middle to the row bottom): the lanes not expecting C keep their order; at the dot's position the
    first parent continues the commit's lane and colour; right after it, every further parent (a merge) gets a new
    lane with a new colour, unless a lane already expects that parent, in which case an edge goes from the dot to the
    leftmost such lane. A root commit ends its lane, and the other lanes expecting C end at the dot. The lanes are
    kept without gaps, so lanes right of the dot can move by a position, drawn as a diagonal lower edge.
  - A first parent that another lane already expects does not join it: both lanes run until that commit and
    converge there, as in IntelliJ.
  - New lanes take the next of 8 colour indices in turn; a continued lane keeps its colour. An edge has the colour of
    its lane; an edge converging into a dot that of the converging lane, a merge edge that of the lane it goes into.
- **Incremental across pages:** `GitLogPages` keeps one layout per load: a further page continues it, so its rows are
  the same as if all were laid out at once; a reload and opening another folder start a new one. Every row
  (`GitLogRow`) keeps its commit and its graph row, as tens of thousands of rows can be loaded: the dot, the number of
  positions it uses and the edges in one `IntArray`, an upper edge as one value (it starts at its own position, so
  only its colour and whether it ends at the dot are stored), a lower edge as a (from, to, colour) triple. Edges with
  both ends at position 30 or beyond are not stored (see the cap below), so a row stores about 2 × 30 edges, plus
  those crossing the cap into or out of its dot (converging lanes, merges); the layout's own list of lanes stays
  complete.
- **Drawn in the Subject cell,** left of the subject, like IntelliJ: no column of its own, the text of every row
  starts right after that row's own graph, so the indent varies from row to row. Lanes are 14 px wide, dots have a
  radius of 4 px, lines are 2 px wide with round ends, clipped to the row. The graph takes the cell's full height
  whatever the font size, so the lines of neighbouring rows meet; it is rebuilt only when the cell gets another row
  or another height.
- **Colours from CSS:** a `Path` per colour index (`git-graph-edge-0`…`7`) and a `Circle` (`git-graph-dot-0`…`7`),
  the colours being looked-up colours `-git-graph-0`…`7` on `.git-log`, redefined in its
  `@media (prefers-color-scheme: dark)` block. Selected rows keep the lane colours, but every line and the dot get a
  halo underneath, 2.5 px wider, in the table's background colour (`-fx-control-inner-background`), so that lanes
  close to the selection bar's colour stay visible. It is a second `Path` and `Circle` in every row, made visible only
  by the row's `:selected` pseudo-class in `git-log.css`; unselected rows look the same without it. It does not depend
  on the table's focus (the strong bar), as `:focused` follows the window's focus; on the pale bar of an unfocused
  table it hardly shows. The selected text colour was tried first, but turns dark on that pale bar.
- **At most 30 lanes are drawn** (`MAX_LANES`): edges with both ends at position 30 or beyond and dots there are left
  out; an edge with one end inside (a lane shifting from 30 to 29, a merge from position 3 into lane 35) is drawn and
  cut off by the graph's clip at its right edge. The subject starts after at most 30 lanes. The layout's lanes
  themselves are not limited.
- **Rejected:**
  - JGit's `revplot` (`PlotCommitList`): a second git implementation next to the command line, see ADR 0035.
  - Parsing the ASCII graph of `git log --graph`: positions are character cells, not lanes with colours;
    `--graph` implies a topological walk of the whole history, and its state starts again with every `--skip` page.
  - Painting on a `Canvas` with colours in Kotlin: it would bypass the CSS themes.

## Consequences
- Lane colours are assigned from the top, so a new branch tip after a refresh can change the colours of the lanes
  below it. A later package may give `HEAD`'s lane a fixed colour once refs are read (P3).
- If refs change between pages (ADR 0035), the graph is off until the next reload: a skipped commit leaves the lane
  expecting it running on to the bottom; a repeated commit is expected by no lane any more, so it becomes a new branch
  tip with an extra lane, which converges into its first parent's lane at that parent.
- Very wide graphs are cut at 30 lanes; the commits are still listed.
- Long edges are not collapsed into arrows as IntelliJ does; a branch that forked far below runs as a line through all
  rows in between.

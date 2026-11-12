package dev.sluide.codereview.list

import java.nio.file.Path

/** What the diff's context menus ask of the IDE: files are [root]-relative paths of the rows. */
internal class LineActions(
    val root: Path,
    /** Opens the working-tree file at the 0-based line, `null` for no line. */
    val openFile: (Path, Int?) -> Unit,
    val showHistory: (Path) -> Unit,
)

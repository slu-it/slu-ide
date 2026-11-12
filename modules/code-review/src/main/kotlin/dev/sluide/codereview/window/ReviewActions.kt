package dev.sluide.codereview.window

import java.nio.file.Path

/** What the review window asks of the IDE, handed through from `Reviews`. */
internal class ReviewActions(
    /** Opens the working-tree file at the 0-based line, `null` for no line. */
    val openFile: (Path, Int?) -> Unit,
    val showHistory: (Path) -> Unit,
)

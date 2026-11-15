package dev.sluide.findinfiles.ui

import dev.sluide.findinfiles.search.FileHits
import dev.sluide.findinfiles.search.LineHit
import dev.sluide.textediting.SearchOptions
import javafx.geometry.Bounds
import javafx.scene.Node
import javafx.stage.Window
import java.nio.file.Path

internal data class FindAnchor(
    val owner: Window,
    val area: Bounds, // screen
    val returnFocus: Node?,
)

internal fun interface PreviewTexts {
    fun load(
        file: Path,
        done: (String?) -> Unit, // on the FX thread
    )
}

internal interface FindView {
    val isShowing: Boolean
    var query: String
    var mask: String
    var options: SearchOptions
    var scopeLabel: String? // null = whole window; set before open
    var severalRoots: Boolean // headers prefix the root's folder name; set before results arrive
    var onChanged: () -> Unit // user edited query, mask or a toggle
    var onWiden: () -> Unit // x on the scope chip
    var onOpen: (FileHits, LineHit) -> Unit
    var onHidden: () -> Unit

    fun open(anchor: FindAnchor) // shows, focuses the query field, selects its text

    fun clear()

    fun add(files: List<FileHits>)

    fun status(text: String)

    fun hide() // returns the focus to anchor.returnFocus

    fun hideForOpen() // hides without returning the focus
}

package dev.sluide.codereview

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.codereview.window.ReviewActions
import dev.sluide.codereview.window.ReviewPrefs
import dev.sluide.codereview.window.ReviewWindow
import dev.sluide.codereview.window.reviewWindowBounds
import dev.sluide.fileviewers.FileViewers
import dev.sluide.projectdata.ProjectDataStore
import dev.sluide.settings.FontSettings
import javafx.geometry.Rectangle2D
import javafx.stage.Screen
import javafx.stage.Window
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * The review windows of one IDE window (ADR 0168): each [open] makes a new top-level window with a review of a
 * scope, read through the [source] the `ide` module implements over git. The windows close with the IDE window
 * ([closeAll]). FX thread.
 */
@Component
// One instance per IDE window (the "window" scope of the ide module, ADR 0014).
@Scope("window")
class Reviews(
    private val source: ReviewSource,
    private val fonts: FontSettings,
    store: ProjectDataStore,
    private val viewers: FileViewers,
) {
    /** Opens the working-tree file at the 0-based line (`null` for none); set by ide. */
    var openFile: (Path, line: Int?) -> Unit = { _, _ -> }

    /** Shows the history of the file; set by ide. */
    var showHistory: (Path) -> Unit = {}

    private val prefs = ReviewPrefs(store)
    private val actions = ReviewActions({ path, line -> openFile(path, line) }, { path -> showHistory(path) })
    private val open = mutableListOf<ReviewWindow>()
    private val prefsWriter = NamedThreads.single("sluide-review-prefs")

    /** The windows that are open, oldest first. */
    internal val windows: List<ReviewWindow> get() = open

    /** The next window's place in the cascade; starts again at the centre once all windows are closed. */
    private var cascade = 0

    /** Opens a new review window of [scope] in the project at [root], on the screen of [ideWindow]. */
    fun open(
        root: Path,
        scope: ReviewScope,
        ideWindow: Window,
    ) {
        if (open.isEmpty()) cascade = 0
        val area = screenAreaOf(ideWindow)
        val window =
            ReviewWindow(
                root,
                scope,
                ideWindow,
                reviewWindowBounds(area, cascade++),
                source,
                fonts,
                prefs,
                viewers::highlighterFor,
                actions,
                onClosed = { open.remove(it) },
                prefsWriter = prefsWriter,
            )
        open += window
        window.show()
    }

    /** The IDE window changed `.git` or the status: each review checks whether its changes changed. */
    fun repositoryChanged() {
        open.toList().forEach(ReviewWindow::checkStale)
    }

    /** Closes every review window. */
    fun closeAll() {
        open.toList().forEach(ReviewWindow::close)
    }

    private fun screenAreaOf(ide: Window): Rectangle2D {
        val screens =
            Screen.getScreensForRectangle(
                ide.x,
                ide.y,
                ide.width.coerceAtLeast(1.0),
                ide.height.coerceAtLeast(1.0),
            )
        return (screens.firstOrNull() ?: Screen.getPrimary()).visualBounds
    }
}

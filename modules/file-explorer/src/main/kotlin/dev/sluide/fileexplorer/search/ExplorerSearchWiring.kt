package dev.sluide.fileexplorer.search

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.fileexplorer.tree.selectRow
import javafx.animation.PauseTransition
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.beans.property.SimpleStringProperty
import kotlin.time.Duration
import javafx.util.Duration as FxDuration

/**
 * The explorer's search bar wired to [this] view: the walk lists what the tree lists ([FileExplorerView.shownEntries])
 * on one background thread, results narrow the tree ([FileExplorerView.applySearch]) and refresh the cells, which
 * highlight by the applied query; changes of the files and settings search again, new roots end it. [delay] is the
 * debounce, asked for every keystroke.
 */
internal fun FileExplorerView.buildSearchBar(delay: () -> Duration): ExplorerSearchBar {
    val status = SimpleStringProperty("")
    val search =
        ExplorerSearch(
            scheduler = { wait, task ->
                val pause = PauseTransition(FxDuration.millis(wait.inWholeMilliseconds.toDouble()))
                pause.setOnFinished { task.run() }
                pause.play()
                AutoCloseable { pause.stop() }
            },
            delay = delay,
            background = NamedThreads.single("sluide-explorer-search"),
            runOnFx = Platform::runLater,
            roots = ::searchRoots,
            walk = {
                roots,
                query,
                cancelled,
                ->
                searchNames(
                    roots,
                    query,
                    ::shownEntries,
                    limit = ExplorerSearch.LIMIT,
                    maxVisited = ExplorerSearch.MAX_VISITED,
                    cancelled = cancelled,
                )
            },
            apply = {
                applySearch(it)
                refresh()
            },
            resetExtras = ::resetSearchExtras,
            isActive = { searchActive },
            onStatus = status::set,
        )
    val bar =
        ExplorerSearchBar(
            search = search,
            status = status,
            hasRoots = rootProperty().isNotNull,
            rows = { (0 until expandedItemCount).mapNotNull { getTreeItem(it) as? FileTreeItem } },
            select = { selectRow(it) },
            focusTree = ::requestFocus,
            open = ::requestOpen,
        )
    searchInvalidated.addListener(InvalidationListener { search.invalidated() })
    rootProperty().addListener { _ -> bar.rootsChanged() }
    return bar
}

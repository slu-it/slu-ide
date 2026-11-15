package dev.sluide.settings

import javafx.scene.Node
import javafx.stage.Window
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path

/** One page of the Project Settings dialog; a window bean (`@Scope("window")`) that stores per project (ADR 0090). */
interface ProjectSettingsPage {
    /** Where the page sits in the tree, e.g. `["Java"]`; the last element is the page's own name. */
    val path: List<String>

    /** The page's content; created on first use, on the FX thread. */
    val node: Node

    /** Shows the project's current settings, dropping unapplied edits. Called every time the dialog opens. */
    fun load(folder: Path)

    /**
     * Applies and stores the page's edits for the project in [folder].
     *
     * @throws java.io.IOException if they cannot be stored; the dialog reports it. `ProjectDataStore.update` returns
     *   false instead of throwing, so a page that stores through it throws an `IOException` when it returns false.
     */
    fun apply(folder: Path)
}

/** A [ProjectSettingsPage] bound to the project [folder], as a page of the shared [SettingsDialog]. */
private class BoundPage(
    private val page: ProjectSettingsPage,
    private val folder: Path,
) : SettingsPage {
    override val path get() = page.path
    override val node get() = page.node

    override fun load() = page.load(folder)

    override fun apply() = page.apply(folder)
}

/**
 * The window's project settings (File → Project Settings…): its [pages], ordered by path, and the opener [show] that
 * `ide` sets. Works with no pages.
 */
@Component
@Scope("window")
class ProjectSettings(
    pages: List<ProjectSettingsPage>,
) {
    val pages: List<ProjectSettingsPage> = pages.sortedBy { it.path.joinToString("\u0000") }

    /** Opens the dialog at [path] (null: the first page); set by `ide`. Does nothing until set. FX thread. */
    var show: (path: List<String>?) -> Unit = {}

    /**
     * A Project Settings dialog for the project in [folder], at the page [path] (null or unknown: the first page).
     * Other code calls [show], which `ide` sets to open it; this is what `ide` uses to build the dialog.
     */
    fun dialog(
        folder: Path,
        owner: Window?,
        path: List<String>? = null,
    ): SettingsDialog =
        SettingsDialog(pages.map { BoundPage(it, folder) }, owner, "Project Settings").also { dialog ->
            path?.let(dialog::select)
        }
}

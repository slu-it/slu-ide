package dev.sluide.ide.files

import dev.sluide.projects.RecentProject
import javafx.scene.control.Menu
import javafx.scene.control.MenuItem
import java.nio.file.Files
import java.nio.file.Path

/** A remembered project as a menu entry: its [label], and whether its [folder] still [exists]. */
internal data class RecentEntry(
    val folder: Path,
    val label: String,
    val exists: Boolean,
)

/**
 * The [limit] newest [projects] (given newest first) as entries `name — displayPath`, leaving out the [current]
 * project. [exists] tells whether a folder is still there, [home] is shown as `~`.
 */
internal fun recentEntries(
    projects: List<RecentProject>,
    current: Path?,
    limit: Int = 10,
    home: Path = Path.of(System.getProperty("user.home")),
    exists: (Path) -> Boolean = Files::isDirectory,
): List<RecentEntry> {
    val own = current?.toAbsolutePath()?.normalize()
    return projects
        .filter { it.folder.toAbsolutePath().normalize() != own }
        .take(limit)
        .map { RecentEntry(it.folder, "${it.name} — ${it.displayPath(home)}", exists(it.folder)) }
}

/** The "Open Recent Project" submenu; its items are rebuilt from [entries] each time it is about to show. */
internal fun recentProjectsMenu(
    entries: () -> List<RecentEntry>,
    open: (Path) -> Unit,
): Menu {
    fun placeholder() = MenuItem("No Recent Projects").apply { isDisable = true }
    return Menu("Open Recent Project").apply {
        // One item from the start, so that the submenu has an arrow before its first showing.
        items.add(placeholder())
        setOnShowing {
            val rebuilt =
                entries().map { entry ->
                    MenuItem(entry.label).apply {
                        isDisable = !entry.exists
                        setOnAction { open(entry.folder) }
                    }
                }
            items.setAll(rebuilt.ifEmpty { listOf(placeholder()) })
        }
    }
}

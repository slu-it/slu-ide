package dev.sluide.ide.explorer

import dev.sluide.editorarea.EditorArea
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.projectdata.ProjectDataStore
import java.nio.file.Path

/**
 * What the explorer's moves and deletions do to the rest of the window: the per-file project data (run-argument
 * histories, ADR 0091) follows files and folders renamed, moved or deleted, also of files not open, and the open tabs
 * follow or close. Used on the JavaFX thread.
 */
internal class ExplorerProjectData(
    private val fileExplorer: FileExplorerView,
    private val editorArea: EditorArea,
    private val projectData: ProjectDataStore,
    private val folder: () -> Path?,
) {
    fun wire() {
        // Re-keyed first, so a view that follows reads its data under the new path. Tabs follow instead of closing as
        // if they were deleted.
        fileExplorer.onMoved = { from, to ->
            projectData.moved(from, to, folder())
            editorArea.moved(from, to)
        }
        // Deleting warns about unsaved changes and, once done, forgets the per-file project data below the path and
        // closes the tabs without asking again: clean ones and those it warned about.
        fileExplorer.unsavedUnder = editorArea::dirtyUnder
        fileExplorer.onDeleted = { path, unsaved ->
            projectData.deleted(path, folder())
            editorArea.closeUnder(path, unsaved)
        }
    }
}

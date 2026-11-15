package dev.sluide.ide.find

import dev.sluide.editorarea.EditorArea
import dev.sluide.fileexplorer.FileExplorerView
import dev.sluide.fileviewers.CodeEditorExtension
import dev.sluide.fileviewers.EditableFileView
import dev.sluide.fileviewers.FileViewers
import dev.sluide.fileviewers.TextEditableView
import dev.sluide.findinfiles.FindInFiles
import dev.sluide.findinfiles.MatchOpener
import dev.sluide.ide.WINDOW_SCOPE
import dev.sluide.ide.codeintelligence.CodeIntelligenceWiring
import dev.sluide.settings.FontSettings
import javafx.stage.Window
import org.springframework.context.annotation.Scope
import org.springframework.stereotype.Component
import java.nio.file.Path

/** What the Navigate menu needs of Find in Files: why it cannot open now, and opening it. */
interface FindInFilesEntry {
    /** Why [open] would do nothing (no folder open); `null` if it can. */
    fun blocker(): String?

    /** Opens Find in Files over [window], scoped to the folder the focused explorer tree has selected, else all. */
    fun open(window: Window): Boolean
}

/**
 * Find in Files of one window (ADR 0177) wired to the window's explorer (what is searched, where Find in Folder
 * starts), editors (unsaved texts, opening a match) and code intelligence (a match is recorded for Back when the popup
 * was opened from an editor with code intelligence, as Go to Symbol). Closed with the window's scope, like
 * [CodeIntelligenceWiring].
 */
@Component
@Scope(WINDOW_SCOPE)
class FindInFilesWiring(
    private val fileExplorer: FileExplorerView,
    private val editorArea: EditorArea,
    codeIntelligence: CodeIntelligenceWiring,
    fileViewers: FileViewers,
    fontSettings: FontSettings,
) : FindInFilesEntry,
    AutoCloseable {
    private val findInFiles = FindInFiles(fileViewers, fontSettings)

    init {
        val intelligence = codeIntelligence.intelligence
        findInFiles.roots = fileExplorer::searchRoots
        findInFiles.entries = fileExplorer::shownEntries
        findInFiles.unsavedTexts = ::unsavedTexts
        findInFiles.opener = {
            val start = intelligence.jumpStart()
            // Intended retry: code intelligence cannot open the file without a host (no code editor was ever opened
            // in the window), then the editor area opens it; if the file cannot be shown at all both say no.
            MatchOpener { file, line, column ->
                if (!intelligence.jump(start, file, line, column)) editorArea.open(file, line, column)
            }
        }
        fileExplorer.findInFolder = { folder -> fileExplorer.scene?.window?.let { findInFiles.open(it, folder) } }
    }

    /** The extension that adds Find Selection in Files to the code editors' context menus. */
    val editorExtension: CodeEditorExtension get() = findInFiles

    override fun blocker(): String? = findInFiles.blocker()

    override fun open(window: Window): Boolean = findInFiles.open(window, fileExplorer.focusedFolder())

    /** The texts of the open editors with unsaved changes, by normalised absolute path; FX thread. */
    private fun unsavedTexts(): Map<Path, String> =
        editorArea.openPaths
            .mapNotNull { path ->
                val view = editorArea.viewOf(path)
                if (view !is TextEditableView || !view.canReplaceText) return@mapNotNull null
                if ((view as? EditableFileView)?.dirty?.get() != true) return@mapNotNull null
                path.toAbsolutePath().normalize() to view.text
            }.toMap()

    override fun close() = findInFiles.close()
}

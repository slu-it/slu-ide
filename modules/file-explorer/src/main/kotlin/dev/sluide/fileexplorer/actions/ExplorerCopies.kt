package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.ExplorerClipboard
import dev.sluide.fileexplorer.tree.FileTreeItem
import dev.sluide.fileexplorer.tree.outermost
import dev.sluide.fileexplorer.tree.sourceRows
import javafx.scene.control.TreeItem
import java.io.IOException
import java.io.UncheckedIOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

private val log = System.getLogger(ExplorerCopies::class.java.name)

/**
 * Copy, cut and paste of explorer entries over [clipboard], and copying entries into a folder (ADR 0092). Copies are
 * made in the background; pasting cut entries moves them with [moves].
 */
internal class ExplorerCopies(
    private val host: ExplorerEditHost,
    private val moves: ExplorerMoves,
    /** The clipboard, read when used; tests replace it. */
    private val clipboard: () -> ExplorerClipboard,
    /** Whether an entry can be renamed, moved or deleted; only such entries can be cut. */
    private val canChange: (TreeItem<Path>?) -> Boolean,
    /** Whether a folder is being deleted or lies in an entry being deleted; nothing is pasted into it. */
    private val isBeingDeleted: (Path) -> Boolean,
) {
    /** Puts [items] (outermost only, a compacted row's first folder) on the clipboard to be copied; also top-level folders. Ends a cut. */
    fun copy(items: List<FileTreeItem>): Boolean {
        if (items.isEmpty()) return false
        clipboard().copy(sourceRows(items).map { it.sourcePath })
        return true
    }

    /** Puts [items] (as for [copy]) on the clipboard marked as cut, if all can be changed; returns whether it did. */
    fun cut(items: List<FileTreeItem>): Boolean {
        if (items.isEmpty() || !items.all(canChange)) return false
        clipboard().cut(sourceRows(items).map { it.sourcePath })
        return true
    }

    /** Ends a pending cut; returns whether there was one. */
    fun cancelCut(): Boolean = clipboard().cancelCut()

    /**
     * The folder a paste for [targets] goes into: the single folder, the folder of the single file, the project folder
     * for none; `null` for several, or a folder being deleted.
     */
    fun pasteTarget(targets: List<FileTreeItem>): FileTreeItem? {
        val folder =
            when {
                targets.isEmpty() -> host.projectItem()
                targets.size > 1 -> null
                targets.single().isDirectory -> targets.single()
                else -> targets.single().parent as? FileTreeItem
            }
        return folder?.takeIf { !isBeingDeleted(it.value) }
    }

    /**
     * Whether a paste for [targets] would paste something: files on the clipboard and a folder for them
     * ([pasteTarget]). Checks the cut against the clipboard first.
     */
    fun canPaste(targets: List<FileTreeItem>): Boolean {
        if (pasteTarget(targets) == null) return false
        val board = clipboard()
        board.checkCut()
        return board.files().isNotEmpty()
    }

    /**
     * Pastes the files on the clipboard into the folder for [targets] ([pasteTarget]); returns whether there was
     * something to paste and a folder for it. Entries cut in the explorer, while the clipboard still holds them, are
     * moved there ([ExplorerMoves.move], asking only about names taken there), after which the cut ends. Anything
     * else is copied ([copyInto]) without asking, unless files with unsaved changes are among them.
     */
    fun paste(targets: List<FileTreeItem>): Boolean {
        val folder = pasteTarget(targets) ?: return false
        val board = clipboard()
        val files = board.files()
        if (files.isEmpty()) return false
        val cut = board.checkCut()
        if (cut.isNotEmpty()) {
            if (moves.move(files, folder)) board.cutPasted()
            return true
        }
        copyInto(files, folder)
        return true
    }

    /**
     * Copies [paths] into [folder] ([FileOperations.copy]): asks first only if files with unsaved changes are among
     * them, whose copies get what is saved. Refused with an error if [folder] lies in one of them.
     */
    fun copyInto(
        paths: List<Path>,
        folder: FileTreeItem,
    ) {
        val sources = outermost(paths) { it }
        if (sources.isEmpty() || refusedInto(sources, folder)) return
        val unsaved = sources.flatMap(host.unsavedUnder)
        if (unsaved.isNotEmpty() && !host.confirmCopy(copyUnsavedQuestion(unsaved.map { it.fileName.toString() }))) {
            return
        }
        copyInBackground(sources, folder)
    }

    /**
     * Asks once whether to copy [items] (outermost only) into [folder], naming the copies that get another name and
     * the files with unsaved changes, and copies them; e.g. after a drop. Refused with an error if [folder] lies in one
     * of them.
     */
    fun copyDrop(
        items: List<FileTreeItem>,
        folder: FileTreeItem,
    ) {
        val rows = sourceRows(items)
        val sources = rows.map { it.sourcePath }
        if (sources.isEmpty() || refusedInto(sources, folder)) return
        val folderName = folder.displayName
        val names =
            try {
                plannedNames(sources, folder.value).zip(rows) { (name, copy), row ->
                    // A compacted row is named as shown; its copy keeps the folders below the first.
                    when {
                        row.isCompacted -> row.displayName to (copy + row.displayName.removePrefix(name))
                        else -> name to copy
                    }
                }
            } catch (e: IOException) {
                host.showError("Could not copy into “$folderName”: ${e.message ?: e::class.simpleName}")
                return
            } catch (e: UncheckedIOException) {
                host.showError("Could not copy into “$folderName”: ${e.cause?.message ?: e.message}")
                return
            }
        val question =
            copyDropQuestion(
                names,
                folderName,
                sources.flatMap(host.unsavedUnder).map { it.fileName.toString() },
            )
        if (!host.confirmCopy(question)) return
        copyInBackground(sources, folder)
    }

    /** Shows an error and returns true if [folder] is one of [sources] or lies inside one, also through a link. */
    private fun refusedInto(
        sources: List<Path>,
        folder: FileTreeItem,
    ): Boolean {
        val into =
            sources.firstOrNull {
                Files.isDirectory(it, NOFOLLOW_LINKS) && FileOperations.isAtOrBelow(folder.value, it)
            }
        into?.let { host.showError(FileOperations.copyIntoItself(it.fileName.toString())) }
        return into != null
    }

    /**
     * The names of [sources] and the names their copies in [folder] would get, copied in this order; fails if [folder]
     * cannot be listed.
     */
    private fun plannedNames(
        sources: List<Path>,
        folder: Path,
    ): List<Pair<String, String>> {
        val taken =
            Files
                .list(folder)
                .use { entries ->
                    entries.map { it.fileName.toString().lowercase() }.toList()
                }.toMutableSet()
        return sources.map { source ->
            val name = source.fileName.toString()
            val copy =
                FileOperations.copyName(name, Files.isDirectory(source, NOFOLLOW_LINKS)) {
                    it.lowercase() in
                        taken
                }
            taken += copy.lowercase()
            name to copy
        }
    }

    /**
     * Copies [sources] into [folder] in the background, in order, stopping at the first failure; sources that are gone
     * are skipped. Back on the JavaFX thread, [folder] is read again and expanded, the copies are selected and what
     * went wrong is shown in one error.
     */
    private fun copyInBackground(
        sources: List<Path>,
        folder: FileTreeItem,
    ) {
        // Read here, not in the background: the tree item belongs to the JavaFX thread.
        val target = folder.value
        host.runInBackground {
            val copies = mutableListOf<Path>()
            val gone = mutableListOf<Path>()
            var failure: String? = null
            try {
                for (source in sources) {
                    if (!Files.exists(source, NOFOLLOW_LINKS)) {
                        gone.add(source)
                        continue
                    }
                    log.log(System.Logger.Level.INFO, "Copying $source into $target")
                    try {
                        copies.add(FileOperations.copy(source, target))
                    } catch (e: FileOperationException) {
                        log.log(System.Logger.Level.WARNING, "Cannot copy $source", e)
                        failure = e.message.orEmpty()
                        break
                    } catch (e: Exception) {
                        // Unexpected: the end must still run, so the copies made show.
                        log.log(System.Logger.Level.ERROR, "Copying $source failed unexpectedly", e)
                        failure = "Could not copy “${source.fileName}”: ${e.message ?: e::class.simpleName}"
                        break
                    }
                }
            } finally {
                val goneText = goneMessage(gone, "copied")
                val outcome = failure
                host.dispatch {
                    if (host.isShown(folder)) {
                        folder.isExpanded = true
                        folder.refresh()
                        host.selectRows(folder.children.filter { (it as? FileTreeItem)?.sourcePath in copies })
                    }
                    listOfNotNull(goneText, outcome).takeIf { it.isNotEmpty() }?.let {
                        host.showError(it.joinToString("\n\n"))
                    }
                }
            }
        }
    }
}

/** Says that the entries at [gone] no longer exist and were not [done] (e.g. "copied"); `null` for none. */
internal fun goneMessage(
    gone: List<Path>,
    done: String,
): String? =
    when (gone.size) {
        0 -> null
        1 -> "“${gone.single().fileName}” no longer exists and was not $done."
        else -> "These no longer exist and were not $done:\n" + nameLines(gone.map { "“${it.fileName}”" })
    }

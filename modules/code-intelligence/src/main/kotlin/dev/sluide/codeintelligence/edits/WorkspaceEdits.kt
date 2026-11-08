package dev.sluide.codeintelligence.edits

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.languageservers.LspUris
import javafx.application.Platform
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import java.nio.file.Path

private val log = System.getLogger(WorkspaceEdits::class.java.name)

/** The LSP text edits of one file in a workspace edit; [version] is the document version they were made for. */
internal data class FileEdits(
    val file: Path,
    val version: Int?,
    val edits: List<TextEdit>,
)

/** What a workspace edit asks: text edits per file, in the order the files first appear, or why it is refused. */
internal sealed interface PlannedEdit {
    data class Files(
        val files: List<FileEdits>,
    ) : PlannedEdit

    data class Refused(
        val reason: String,
    ) : PlannedEdit
}

/**
 * The files and edits of [edit]: its `documentChanges` if it has them (LSP prefers them), else its `changes`. Creating,
 * renaming and deleting files, snippet edits, URIs that are no local file and several edits of one file (LSP applies
 * them one after the other, each to the text the one before left) are refused.
 */
internal fun planOf(edit: WorkspaceEdit): PlannedEdit {
    val byFile = LinkedHashMap<Path, FileEdits>()

    fun add(
        uri: String?,
        version: Int?,
        edits: List<TextEdit>,
    ): PlannedEdit.Refused? {
        val file =
            uri?.let(LspUris::toPath)?.toAbsolutePath()?.normalize()
                ?: return PlannedEdit.Refused("$uri is no local file")
        if (file in byFile) return PlannedEdit.Refused("Several edits of ${file.fileName} are not supported.")
        byFile[file] = FileEdits(file, version, edits)
        return null
    }
    val documentChanges = edit.documentChanges
    if (documentChanges != null) {
        for (change in documentChanges) {
            if (change == null) continue
            if (change.isRight) {
                return PlannedEdit.Refused("Creating, renaming and deleting files is not supported yet.")
            }
            val documentEdit = change.left
            val edits = documentEdit.edits.orEmpty().filterNotNull()
            if (edits.any { it.isRight }) return PlannedEdit.Refused("Snippet edits are not supported.")
            add(documentEdit.textDocument?.uri, documentEdit.textDocument?.version, edits.map { it.left })
                ?.let { return it }
        }
    } else {
        for ((uri, edits) in edit.changes.orEmpty()) add(uri, null, edits.orEmpty().filterNotNull())?.let { return it }
    }
    return PlannedEdit.Files(byFile.values.toList())
}

/** What applying a workspace edit did: the [files] changed, the number of [edits] applied, and what failed. */
internal data class WorkspaceEditResult(
    val files: List<Path>,
    val edits: Int,
    val failures: List<String>,
) {
    /** Whether everything was applied. */
    val applied: Boolean get() = failures.isEmpty()

    companion object {
        fun refused(reason: String) = WorkspaceEditResult(emptyList(), 0, listOf(reason))
    }
}

/** The editor of a file bound in this window, as a workspace edit sees it. FX thread. */
internal interface BoundText {
    /** The document version the server knows. */
    val version: Int

    /** The document's text (`\n` line breaks). */
    val text: String

    /** Applies [edits] (sorted, not overlapping) as one undo step; `false` if the editor cannot be edited. */
    fun apply(edits: List<OffsetEdit>): Boolean
}

/** Where the files of a workspace edit are: bound editors, other open editors, the disk. FX thread. */
internal interface WorkspaceEditTarget {
    /** The window's project folder: closed files outside it are not edited. */
    val projectFolder: Path?

    /** The bound editor of [file] in this window, if any. */
    fun bound(file: Path): BoundText?

    /** The text of [file] in an editor of this window that is not bound, `null` if it is not open (`currentText`). */
    fun openText(file: Path): String?

    /** Changes the text of the open [file] to `edit(text)`, unsaved (`editText`); `false` if it cannot. */
    fun editOpenText(
        file: Path,
        edit: (String) -> String,
    ): Boolean

    /** Tells the language servers that [files] changed on disk. */
    fun filesChanged(files: List<Path>)
}

/**
 * Applies workspace edits of a language server (quick fixes, rename; ADR 0148). Every file is checked before anything
 * changes (positions in its text, edits not overlapping, the document version a `TextDocumentEdit` names), and one
 * failed check refuses the whole edit. Then per file: a bound editor gets the edits as one undo step, another open
 * editor its new text (unsaved; only if it equals the file on disk, which the server read), a closed file in the
 * project is read (UTF-8, writable, at most 10 MiB), edited in its own line breaks and written atomically off the FX
 * thread (not library code while [libraryReadOnly] is true, ADR 0150), and the servers hear it changed. An open
 * editor that changed while the files were read refuses the edit. FX thread; [io] runs the file work. [libraryReadOnly] is read on the io thread, once per closed file (any thread).
 */
internal class WorkspaceEdits(
    private val target: WorkspaceEditTarget,
    private val io: (Runnable) -> Unit = { NamedThreads.virtual("sluide-workspace-edit", it) },
    private val fx: (Runnable) -> Unit = Platform::runLater,
    private val libraryReadOnly: () -> Boolean = { false },
) {
    /** Applies [edit] and tells [done] the result on the FX thread (at once when only bound editors change). */
    fun apply(
        edit: WorkspaceEdit,
        done: (WorkspaceEditResult) -> Unit,
    ) {
        val files =
            when (val plan = planOf(edit)) {
                is PlannedEdit.Refused -> return done(WorkspaceEditResult.refused(plan.reason))
                is PlannedEdit.Files -> plan.files
            }
        val snapshots = files.map(::snapshot)
        check(snapshots)?.let { return done(WorkspaceEditResult.refused(it)) }
        if (snapshots.all { it.bound != null }) return done(applyOpen(snapshots))
        val projectFolder = target.projectFolder
        io {
            val read =
                try {
                    snapshots.map { readOnDisk(it, projectFolder) }
                } catch (e: RuntimeException) {
                    log.log(System.Logger.Level.ERROR, "Could not read the files of a workspace edit", e)
                    listOf(ClosedEdit(failure = "The files could not be read: $e"))
                }
            fx { applyAll(snapshots, read.filterNotNull(), done) }
        }
    }

    /** Back on the FX thread with the files read: checks the open ones again, applies them, writes the closed ones. */
    private fun applyAll(
        snapshots: List<FileSnapshot>,
        read: List<ClosedEdit>,
        done: (WorkspaceEditResult) -> Unit,
    ) {
        read.firstNotNullOfOrNull { it.failure }?.let { return done(WorkspaceEditResult.refused(it)) }
        changedMeanwhile(snapshots)?.let { return done(WorkspaceEditResult.refused(it)) }
        val opened = applyOpen(snapshots.filter { !it.closed })
        val closed = read.filter { it.original != null }
        if (closed.isEmpty()) return done(opened)
        io {
            val failures = mutableListOf<String>()
            val written = mutableListOf<Path>()
            for (edit in closed) {
                val original = checkNotNull(edit.original)
                try {
                    writeClosedFile(original, checkNotNull(edit.newText))
                    written.add(original.file)
                } catch (e: EditRefusedException) {
                    failures += "${original.file.fileName}: ${e.message}."
                } catch (e: RuntimeException) {
                    log.log(System.Logger.Level.ERROR, "Could not write ${original.file}", e)
                    failures += "${original.file.fileName}: $e"
                }
            }
            fx {
                if (written.isNotEmpty()) target.filesChanged(written)
                done(
                    WorkspaceEditResult(
                        opened.files + written,
                        opened.edits + closed.filter { it.original?.file in written }.sumOf { it.count },
                        opened.failures + failures,
                    ),
                )
            }
        }
    }

    /** Where [fileEdits]' file is now: its bound editor's version, another editor's text, or closed. */
    private fun snapshot(fileEdits: FileEdits): FileSnapshot {
        val bound = target.bound(fileEdits.file)
        return FileSnapshot(
            fileEdits,
            bound,
            bound?.version,
            if (bound == null) target.openText(fileEdits.file) else null,
        )
    }

    /** Why the edits cannot be applied to the files as [snapshots] found them, `null` if they can. */
    private fun check(snapshots: List<FileSnapshot>): String? {
        for (snapshot in snapshots) {
            val fileEdits = snapshot.fileEdits
            val name = fileEdits.file.fileName
            if (fileEdits.version != null && fileEdits.version != snapshot.version) {
                return if (snapshot.bound != null) {
                    "$name changed since the server made this edit."
                } else {
                    "$name is no longer open as the server knows it."
                }
            }
            val text = snapshot.bound?.text ?: snapshot.openText ?: continue
            val checked = checkEdits(text, fileEdits.edits)
            if (checked is CheckedEdits.Invalid) return "$name: ${checked.reason}."
        }
        return null
    }

    /**
     * Why the edit is refused after the files were read, `null` if not: a bound editor's document has another version
     * (typing moves the text under versionless edits), another editor's text changed, a closed file was opened.
     */
    private fun changedMeanwhile(snapshots: List<FileSnapshot>): String? {
        for (snapshot in snapshots) {
            val file = snapshot.fileEdits.file
            val bound = target.bound(file)
            val unchanged =
                when {
                    snapshot.bound != null -> bound != null && bound.version == snapshot.version
                    snapshot.openText != null -> bound == null && target.openText(file) == snapshot.openText
                    else -> bound == null && target.openText(file) == null
                }
            if (!unchanged) {
                return if (snapshot.closed) {
                    "${file.fileName} was opened meanwhile."
                } else {
                    "${file.fileName} changed meanwhile."
                }
            }
        }
        return null
    }

    /** Applies the checked edits of the open files of [snapshots]. */
    private fun applyOpen(snapshots: List<FileSnapshot>): WorkspaceEditResult {
        val changed = mutableListOf<Path>()
        val failures = mutableListOf<String>()
        var count = 0
        for (snapshot in snapshots) {
            val fileEdits = snapshot.fileEdits
            val bound = snapshot.bound
            val ok =
                if (bound != null) {
                    val replacements = (checkEdits(bound.text, fileEdits.edits) as CheckedEdits.Valid).replacements
                    bound.apply(replacements.map { OffsetEdit(it.start, it.end, it.text) })
                } else {
                    var applied = false
                    val edited =
                        target.editOpenText(fileEdits.file) { text ->
                            val checked = checkEdits(text, fileEdits.edits)
                            if (text == snapshot.openText && checked is CheckedEdits.Valid) {
                                applied = true
                                applyReplacements(text, checked.replacements)
                            } else {
                                text
                            }
                        }
                    edited && applied
                }
            if (ok) {
                changed.add(fileEdits.file)
                count += fileEdits.edits.size
            } else {
                failures += "${fileEdits.file.fileName}: cannot be edited."
            }
        }
        return WorkspaceEditResult(changed, count, failures)
    }

    /**
     * Off the FX thread: a closed file read and edited in memory, an open unbound file's disk text compared with its
     * editor's (the server read the disk), `null` for a bound file.
     */
    private fun readOnDisk(
        snapshot: FileSnapshot,
        projectFolder: Path?,
    ): ClosedEdit? {
        val file = snapshot.fileEdits.file
        val name = file.fileName
        if (snapshot.bound != null) return null
        if (snapshot.openText != null) {
            val unsaved = readDiskText(file) != snapshot.openText.removePrefix(BOM_CHAR)
            return if (unsaved) ClosedEdit(failure = "$name has unsaved changes the server does not know.") else null
        }
        return try {
            val original = readClosedFile(file, projectFolder, libraryReadOnly())
            when (val checked = checkEdits(original.text, snapshot.fileEdits.edits, lineEndingOf(original.text))) {
                is CheckedEdits.Invalid -> {
                    ClosedEdit(failure = "$name: ${checked.reason}.")
                }

                is CheckedEdits.Valid -> {
                    ClosedEdit(
                        original = original,
                        newText = applyReplacements(original.text, checked.replacements),
                        count = snapshot.fileEdits.edits.size,
                    )
                }
            }
        } catch (e: EditRefusedException) {
            ClosedEdit(failure = "$name: ${e.message}.")
        }
    }

    /** A file of the edit as found on the FX thread before anything was read. */
    private class FileSnapshot(
        val fileEdits: FileEdits,
        val bound: BoundText?,
        val version: Int?,
        val openText: String?,
    ) {
        val closed: Boolean get() = bound == null && openText == null
    }

    /** A closed file read and edited in memory, or why the edit is refused. */
    private class ClosedEdit(
        val original: ClosedFileText? = null,
        val newText: String? = null,
        val count: Int = 0,
        val failure: String? = null,
    )

    private companion object {
        val BOM_CHAR = Char(0xFEFF).toString()
    }
}

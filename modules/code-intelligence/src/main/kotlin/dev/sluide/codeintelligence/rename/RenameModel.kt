package dev.sluide.codeintelligence.rename

import dev.sluide.codeintelligence.edits.PlannedEdit
import dev.sluide.codeintelligence.edits.WorkspaceEditResult
import dev.sluide.codeintelligence.edits.planOf
import dev.sluide.codeintelligence.navigation.shownPath
import dev.sluide.fileviewers.OffsetEdit
import org.eclipse.lsp4j.PrepareRenameDefaultBehavior
import org.eclipse.lsp4j.PrepareRenameResult
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.TextEdit
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.jsonrpc.messages.Either3
import java.nio.file.Path

// The decisions of rename (ADR 0149), without the server and the controls.

/** What a server's answer to `textDocument/prepareRename` means. */
internal sealed interface PrepareAnswer {
    /** Rename the text of [range]; [placeholder] is the name to offer, else the range's text. */
    data class At(
        val range: Range,
        val placeholder: String?,
    ) : PrepareAnswer

    /** The server leaves it to the client: the word at the caret. */
    data object WordAtCaret : PrepareAnswer

    /** Nothing can be renamed here. */
    data object Refused : PrepareAnswer
}

/** The meaning of a `prepareRename` [result]: a range (with a placeholder), the default behaviour, or none. */
internal fun prepareAnswer(result: Either3<Range, PrepareRenameResult, PrepareRenameDefaultBehavior>?): PrepareAnswer =
    when {
        result == null -> {
            PrepareAnswer.Refused
        }

        result.isFirst -> {
            result.first?.let { PrepareAnswer.At(it, null) } ?: PrepareAnswer.Refused
        }

        result.isSecond -> {
            result.second?.range?.let { PrepareAnswer.At(it, result.second.placeholder) }
                ?: PrepareAnswer.Refused
        }

        result.third?.isDefaultBehavior == true -> {
            PrepareAnswer.WordAtCaret
        }

        else -> {
            PrepareAnswer.Refused
        }
    }

/** The name to rename to when the user typed [typed] for [old]; `null` for an empty or unchanged one (nothing to do). */
internal fun wantedName(
    old: String,
    typed: String,
): String? = typed.trim().takeIf { it.isNotEmpty() && it != old }

/** The text edits of one file in a rename. */
internal data class FileChanges(
    val file: Path,
    val edits: List<TextEdit>,
) {
    val count: Int get() = edits.size
}

/** The changes of a rename's workspace edit per file, in the order the server sent them, or why it is refused. */
internal sealed interface RenameChanges {
    data class Files(
        val files: List<FileChanges>,
    ) : RenameChanges

    data class Refused(
        val reason: String,
    ) : RenameChanges
}

/** The changes of [edit] per file ([planOf]: creating, renaming and deleting files and the like are refused). */
internal fun renameChanges(edit: WorkspaceEdit): RenameChanges =
    when (val plan = planOf(edit)) {
        is PlannedEdit.Refused -> RenameChanges.Refused(plan.reason)
        is PlannedEdit.Files -> RenameChanges.Files(plan.files.map { FileChanges(it.file, it.edits) })
    }

/** The message of a rename that was not applied, or only to some files ([WorkspaceEditResult.failures]). */
internal fun renameFailure(result: WorkspaceEditResult): String {
    val failures = result.failures.joinToString(" ")
    if (result.files.isEmpty()) return "Rename not applied: $failures"
    val changed = result.files.joinToString(", ") { it.fileName.toString() }
    return "Renamed in $changed only. $failures"
}

/** How a file of a rename stands before it is applied, for the preview. */
internal enum class RenameFileState(
    /** What the preview says of the file, `null` for nothing. */
    val note: String?,
    /** Whether the workspace edit will be refused because of it. */
    val refuses: Boolean,
) {
    /** Closed, inside the project: written on disk, which no undo takes back. */
    CLOSED("written to disk", false),

    /** Open in an editor, as the server knows it: changed there, left unsaved. */
    OPEN(null, false),

    /** Open in an editor with code intelligence and unsaved changes, which the server knows: changed there. */
    UNSAVED("unsaved changes", false),

    /** Open in an editor without code intelligence with unsaved changes the server never saw. */
    UNSAVED_UNKNOWN("unsaved changes the server does not know", true),

    /** Closed and outside the project: not written. */
    OUTSIDE("outside the project", true),
}

/** One file in the rename preview: its [path] as shown, its number of changes and what to know about it. */
internal data class PreviewRow(
    val path: String,
    val count: Int,
    val note: String?,
)

/** The rename preview dialog's content. */
internal data class RenamePreview(
    val header: String,
    val summary: String,
    val rows: List<PreviewRow>,
    /** Why the rename will be refused, `null` if it will not; the dialog offers Cancel only then. */
    val refusal: String?,
)

/**
 * The preview of renaming [old] to [new] with [changes]: "N changes in M files", the files by shown path ([shownPath]:
 * relative to [projectFolder], absolute outside it) with their counts and [states], and why it will be refused.
 */
internal fun renamePreview(
    old: String,
    new: String,
    changes: List<FileChanges>,
    states: Map<Path, RenameFileState>,
    projectFolder: Path?,
): RenamePreview {
    val rows =
        changes
            .map { PreviewRow(shownPath(it.file, projectFolder), it.count, states[it.file]?.note) }
            .sortedBy { it.path }
    val total = changes.sumOf { it.count }
    val refusing =
        changes.mapNotNull { change ->
            val state = states[change.file]?.takeIf { it.refuses } ?: return@mapNotNull null
            val name = change.file.fileName
            if (state == RenameFileState.OUTSIDE) "$name is outside the project" else "$name has ${state.note}"
        }
    return RenamePreview(
        header = "Rename “$old” to “$new”?",
        summary = "${counted(total, "change")} in ${counted(changes.size, "file")}",
        rows = rows,
        refusal = refusing.takeIf { it.isNotEmpty() }?.let { reasons -> refusalText(reasons) },
    )
}

private fun refusalText(reasons: List<String>): String {
    val joined = reasons.joinToString("; ")
    return "The rename cannot be applied: $joined."
}

/** "1 change", "3 changes". */
internal fun counted(
    count: Int,
    noun: String,
): String = if (count == 1) "1 $noun" else "$count ${noun}s"

/**
 * Where the caret goes after a rename: [caretInName] characters into the new name (at most its [newLength]), whose start
 * moved by the [edits] of the file (offsets of the text before) that lie wholly before the old name's [nameStart].
 */
internal fun caretAfterRename(
    edits: List<OffsetEdit>,
    nameStart: Int,
    caretInName: Int,
    newLength: Int,
): Int {
    val shift =
        edits.filter { it.start < nameStart && it.end <= nameStart }.sumOf {
            it.text.length -
                (it.end - it.start)
        }
    return nameStart + shift + caretInName.coerceIn(0, newLength)
}

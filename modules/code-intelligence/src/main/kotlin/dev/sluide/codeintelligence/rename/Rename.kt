package dev.sluide.codeintelligence.rename

import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.edits.BindingEditTarget
import dev.sluide.codeintelligence.edits.BoundText
import dev.sluide.codeintelligence.edits.WorkspaceEditTarget
import dev.sluide.codeintelligence.edits.WorkspaceEdits
import dev.sluide.codeintelligence.navigation.navigationBlocker
import dev.sluide.codeintelligence.navigation.sameFile
import dev.sluide.codeintelligence.navigation.unavailable
import dev.sluide.codeintelligence.navigation.wordAt
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.languageservers.LanguageServerNotRunningException
import dev.sluide.languageservers.RequestSlot
import org.eclipse.lsp4j.PrepareRenameParams
import org.eclipse.lsp4j.RenameParams
import org.eclipse.lsp4j.WorkspaceEdit
import java.nio.file.Path

internal const val RENAME = "textDocument/rename"
internal const val PREPARE_RENAME = "textDocument/prepareRename"

/** Where a [Rename] asks for the name and shows what happened: popups and dialogs, a fake in tests. FX thread. */
internal interface RenameView : AutoCloseable {
    /** Shows [text] at [offset] until the next key or click. */
    fun note(
        offset: Int,
        text: String,
    )

    /**
     * Asks for the new name of the text [range] (`start until end`), offering [name]. A name typed is given to [check]
     * first: a problem it names ("Not a valid name: …") is shown and the question stays; else [onName] gets the name.
     */
    fun askName(
        range: IntRange,
        name: String,
        check: (String) -> String?,
        onName: (String) -> Unit,
    )

    /** Shows [preview] of a rename that changes other files too; `true` if the user wants it applied. */
    fun confirm(preview: RenamePreview): Boolean

    /** Tells [message] about a rename that was not applied, or only partly. */
    fun tell(message: String)
}

/** How the files of a rename stand ([RenameFileState]), found off the FX thread where the disk is read. */
internal fun interface RenameFileStates {
    /** Tells [done] on the FX thread how each of [files] stands. */
    fun of(
        files: List<Path>,
        done: (Map<Path, RenameFileState>) -> Unit,
    )
}

/**
 * Rename in one bound editor (ADR 0149): asks the server where the name at the caret is (`textDocument/prepareRename`
 * if it offers it, else the word at the caret), asks the user for the new name ([RenameView.askName]), then the server
 * for the workspace edit (`textDocument/rename`). An edit of the editor's file only is applied at once (one undo step);
 * one changing other files too is shown in a preview first. The edit goes through [edits]; a refusal or a partial
 * failure is told, success leaves the caret in the renamed name. A name the language does not allow
 * ([dev.sluide.languageservers.LanguageServerSpec.isIdentifier]) is refused while it is typed. A bound file changed
 * while the server answered or the preview showed (ty's edits name no versions) drops the answer. FX thread.
 *
 * @param edits the applier over a target, made per rename: the target moves the caret as the editor's own edits apply.
 */
internal class Rename(
    private val binding: DocumentBinding,
    private val view: RenameView,
    private val edits: (WorkspaceEditTarget) -> WorkspaceEdits,
    private val states: RenameFileStates,
) : AutoCloseable {
    private val slot = RequestSlot()
    private var closed = false

    /** Renames the name at [offset] (the caret, or a context menu's place). */
    fun start(offset: Int) {
        if (closed) return
        slot.cancel()
        val blocker = navigationBlocker(binding, RENAME)
        if (blocker != null) return view.note(offset, unavailable(ACTION, blocker))
        val word = binding.editor.wordAt(offset, orBefore = true)
        val at = if (word == null || offset in word) offset else word.first
        if (!binding.session.features.supports(PREPARE_RENAME)) return ask(at, word, null)
        val version = binding.document.version
        val params = PrepareRenameParams(binding.documentId, binding.positionOf(at))
        slot.send(
            binding.session,
            { it.textDocumentService.prepareRename(params) },
            { result ->
                when {
                    gone() -> {}

                    !current(version) -> {
                        view.note(at, CHANGED)
                    }

                    else -> {
                        when (val answer = prepareAnswer(result)) {
                            is PrepareAnswer.At -> ask(at, binding.offsetsOf(answer.range), answer.placeholder)
                            PrepareAnswer.WordAtCaret -> ask(at, word, null)
                            PrepareAnswer.Refused -> view.note(at, CANNOT)
                        }
                    }
                }
            },
            { error -> if (!gone()) view.note(at, if (current(version)) cannot(error) else CHANGED) },
        )
    }

    /** Asks for the new name of [range] (the name at [at]), offering [placeholder] or the range's text. */
    private fun ask(
        at: Int,
        range: IntRange?,
        placeholder: String?,
    ) {
        val text = binding.document.text
        if (range == null || range.isEmpty() || range.last >= text.length) return view.note(at, CANNOT)
        val old = placeholder?.takeIf { it.isNotEmpty() } ?: text.substring(range.first, range.last + 1)
        val version = binding.document.version
        val caretInName = (binding.editor.caretOffset - range.first).coerceIn(0, range.last + 1 - range.first)
        val check = { typed: String ->
            wantedName(old, typed)?.takeUnless(binding.spec::isIdentifier)?.let { "Not a valid name: $it" }
        }
        view.askName(range, old, check) { typed ->
            val name = wantedName(old, typed)
            if (name != null) rename(Target(at, range.first, caretInName, old, name, version, boundVersions()))
        }
    }

    private fun rename(target: Target) {
        if (!current(target.version)) return view.note(target.at, CHANGED)
        val params = RenameParams(binding.documentId, binding.positionOf(target.at), target.name)
        slot.send(
            binding.session,
            { it.textDocumentService.rename(params) },
            { edit ->
                if (gone()) return@send
                received(target, edit)
            },
            { error -> if (!closed) view.tell(failed(error)) },
        )
    }

    private fun received(
        target: Target,
        edit: WorkspaceEdit?,
    ) {
        if (edit == null) return view.note(target.at, NOTHING)
        val files =
            when (val changes = renameChanges(edit)) {
                is RenameChanges.Refused -> return view.tell("Rename not applied: ${changes.reason}")
                is RenameChanges.Files -> changes.files
            }
        if (files.isEmpty()) return view.note(target.at, NOTHING)
        if (!unchanged(target, files)) return view.note(target.at, CHANGED)
        if (files.all { sameFile(it.file, binding.file) }) return apply(target, edit, files)
        states.of(files.map { it.file }) { found ->
            if (gone()) return@of
            if (!unchanged(target, files)) return@of view.note(target.at, CHANGED)
            val preview = renamePreview(target.old, target.name, files, found, binding.editor.host.projectFolder)
            if (!view.confirm(preview) || preview.refusal != null || gone()) return@of
            // Typing in another tab while the preview showed moves the text under the server's versionless edits.
            if (unchanged(target, files)) apply(target, edit, files) else view.note(target.at, CHANGED)
        }
    }

    /** The versions of the window's bound documents now, to see later whether one changed. */
    private fun boundVersions(): Map<DocumentBinding, Int> =
        binding.bindings().filter { !it.isClosed }.associateWith { it.document.version }

    /**
     * Whether the editor's document and every bound file of [files] are as they were when the rename was asked: same
     * document version, and no file bound since.
     */
    private fun unchanged(
        target: Target,
        files: List<FileChanges>,
    ): Boolean =
        current(target.version) &&
            files.all { change ->
                val bound = binding.bindingOf(change.file)?.takeUnless { it.isClosed } ?: return@all true
                bound === binding || target.versions[bound] == bound.document.version
            }

    private fun apply(
        target: Target,
        edit: WorkspaceEdit,
        files: List<FileChanges>,
    ) {
        val own = files.firstOrNull { sameFile(it.file, binding.file) }?.let { binding.editsOf(it.edits) }.orEmpty()
        val caret = caretAfterRename(own, target.nameStart, target.caretInName, target.name.length)
        edits(CaretTarget(BindingEditTarget(binding), binding, caret)).apply(edit) { result ->
            if (!gone() && !result.applied) view.tell(renameFailure(result))
        }
    }

    /** Whether the binding is open and its document still has [version]. */
    private fun current(version: Int) = !gone() && binding.document.version == version

    /** Whether this rename or its binding ended. */
    private fun gone() = closed || binding.isClosed

    override fun close() {
        closed = true
        slot.cancel()
        view.close()
    }

    /** A rename asked for: of the name at [at] starting at [nameStart], from [old] to [name], for the text [version]. */
    private data class Target(
        val at: Int,
        val nameStart: Int,
        val caretInName: Int,
        val old: String,
        val name: String,
        val version: Int,
        val versions: Map<DocumentBinding, Int>,
    )

    private companion object {
        const val ACTION = "Rename"
        const val CANNOT = "Cannot rename this"
        const val CHANGED = "The text changed. Rename again."
        const val NOTHING = "Nothing to rename"
    }
}

/**
 * [target] that moves the caret of [binding]'s editor to [caret] right after its own edits applied (one undo step), so
 * the caret is in the new name before other files are written, and only then.
 */
private class CaretTarget(
    private val target: WorkspaceEditTarget,
    private val binding: DocumentBinding,
    private val caret: Int,
) : WorkspaceEditTarget by target {
    override fun bound(file: Path): BoundText? {
        val bound = target.bound(file) ?: return null
        if (!sameFile(file, binding.file)) return bound
        return object : BoundText by bound {
            override fun apply(edits: List<OffsetEdit>): Boolean =
                bound.apply(edits).also { applied -> if (applied) binding.editor.moveCaret(caret) }
        }
    }
}

/** The note for a `prepareRename` that failed with [error]. */
private fun cannot(error: Throwable): String =
    when {
        error is LanguageServerNotRunningException -> unavailable("Rename", "server not running")
        error.message.isNullOrBlank() -> "Cannot rename this"
        else -> "Cannot rename this: ${error.message}"
    }

/** The message of a `rename` request that failed with [error]. */
private fun failed(error: Throwable): String =
    if (error is LanguageServerNotRunningException) {
        unavailable("Rename", "server not running")
    } else {
        "Rename failed: ${error.message ?: error.javaClass.simpleName}"
    }

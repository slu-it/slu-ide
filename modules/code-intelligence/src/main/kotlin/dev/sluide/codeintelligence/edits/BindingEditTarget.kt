package dev.sluide.codeintelligence.edits

import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.fileviewers.OffsetEdit
import dev.sluide.languageservers.FileChange
import dev.sluide.languageservers.FileChangeKind
import java.nio.file.Path

/**
 * The window of [binding] as the target of workspace edits: its bound editors ([DocumentBinding.bindingOf]), the
 * other open editors through the editor's host, and the window's language servers for files written on disk.
 */
internal class BindingEditTarget(
    private val binding: DocumentBinding,
) : WorkspaceEditTarget {
    override val projectFolder: Path? get() = binding.editor.host.projectFolder

    override fun bound(file: Path): BoundText? {
        val bound = binding.bindingOf(file)?.takeUnless { it.isClosed } ?: return null
        return object : BoundText {
            override val version get() = bound.document.version
            override val text get() = bound.document.text

            override fun apply(edits: List<OffsetEdit>) = bound.editor.applyEdits(edits)
        }
    }

    override fun openText(file: Path): String? = binding.editor.host.currentText(file)

    override fun editOpenText(
        file: Path,
        edit: (String) -> String,
    ) = binding.editor.host.editText(file, edit)

    override fun filesChanged(files: List<Path>) =
        binding.languageServers.filesChanged(files.map { FileChange(it, FileChangeKind.CHANGED) })
}

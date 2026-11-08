package dev.sluide.codeintelligence.rename

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.edits.readDiskText
import javafx.application.Platform
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val log = System.getLogger(BindingRenameFileStates::class.java.name)

/**
 * How the files of a rename stand in [binding]'s window: bound editors by their unsaved state, read on the FX thread;
 * other open editors compared with the disk and closed files placed inside or outside the project on [io], as the
 * workspace edit will find them (ADR 0148). [done][RenameFileStates.of] runs on [fx].
 */
internal class BindingRenameFileStates(
    private val binding: DocumentBinding,
    private val io: (Runnable) -> Unit = { NamedThreads.virtual("sluide-rename-preview", it) },
    private val fx: (Runnable) -> Unit = Platform::runLater,
) : RenameFileStates {
    override fun of(
        files: List<Path>,
        done: (Map<Path, RenameFileState>) -> Unit,
    ) {
        val known = LinkedHashMap<Path, RenameFileState>()
        val openTexts = LinkedHashMap<Path, String>()
        val closed = mutableListOf<Path>()
        for (file in files) {
            val bound = binding.bindingOf(file)?.takeUnless { it.isClosed }
            val openText = if (bound == null) binding.editor.host.currentText(file) else null
            when {
                bound != null -> {
                    known[file] =
                        if (bound.editor.dirty.get()) RenameFileState.UNSAVED else RenameFileState.OPEN
                }

                openText != null -> {
                    openTexts[file] = openText
                }

                else -> {
                    closed.add(file)
                }
            }
        }
        val projectFolder = binding.editor.host.projectFolder
        io {
            val found = LinkedHashMap(known)
            try {
                for ((file, text) in openTexts) {
                    val saved = readDiskText(file) == text.removePrefix(BOM)
                    found[file] = if (saved) RenameFileState.OPEN else RenameFileState.UNSAVED_UNKNOWN
                }
                for (file in closed) {
                    found[file] = if (isInside(file, projectFolder)) RenameFileState.CLOSED else RenameFileState.OUTSIDE
                }
            } catch (e: RuntimeException) {
                log.log(System.Logger.Level.ERROR, "Could not look at the files of a rename", e)
            }
            fx { done(found) }
        }
    }

    private companion object {
        val BOM = Char(0xFEFF).toString()
    }
}

/**
 * Whether [file] lies inside [projectFolder] by real paths, as a workspace edit checks closed files; a missing file by
 * the real path of its nearest existing folder, so it stays inside a project reached through a link.
 */
private fun isInside(
    file: Path,
    projectFolder: Path?,
): Boolean =
    try {
        val project = projectFolder?.takeIf { Files.isDirectory(it) }?.toRealPath()
        project != null && realOrNearest(file).startsWith(project)
    } catch (_: IOException) {
        false
    }

/** The real path of [file], or of its nearest existing ancestor with the missing rest appended. */
private fun realOrNearest(file: Path): Path {
    // Not normalized before resolving: a `..` behind a link leads where the link points.
    val absolute = file.toAbsolutePath()
    var existing: Path? = absolute
    while (existing != null && !Files.exists(existing)) existing = existing.parent
    return (existing?.toRealPath()?.resolve(existing.relativize(absolute)) ?: absolute).normalize()
}

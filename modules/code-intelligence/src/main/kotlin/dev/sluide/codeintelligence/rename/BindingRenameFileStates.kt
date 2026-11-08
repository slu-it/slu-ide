package dev.sluide.codeintelligence.rename

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.codeintelligence.edits.readDiskText
import dev.sluide.librarycode.LibraryCode
import javafx.application.Platform
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

private val log = System.getLogger(BindingRenameFileStates::class.java.name)

/**
 * How the files of a rename stand in [binding]'s window: bound editors by their unsaved state, read on the FX thread;
 * other open editors compared with the disk and closed files found missing, inside or outside the project on [io], as
 * the workspace edit will find them (ADR 0148, library code ADR 0150). [done][RenameFileStates.of] runs on [fx].
 */
internal class BindingRenameFileStates(
    private val binding: DocumentBinding,
    private val io: (Runnable) -> Unit = { NamedThreads.virtual("sluide-rename-preview", it) },
    private val fx: (Runnable) -> Unit = Platform::runLater,
    private val libraryReadOnly: () -> Boolean = { false },
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
                    found[file] =
                        when {
                            !Files.isRegularFile(file) -> RenameFileState.MISSING
                            else -> stateOfClosed(file, projectFolder)
                        }
                }
            } catch (e: RuntimeException) {
                log.log(System.Logger.Level.ERROR, "Could not look at the files of a rename", e)
            }
            fx { done(found) }
        }
    }

    /** CLOSED, LIBRARY or OUTSIDE for the existing [file], by real paths as the workspace edit finds it. */
    private fun stateOfClosed(
        file: Path,
        projectFolder: Path?,
    ): RenameFileState {
        val project = realFolder(projectFolder)
        val real = realPath(file)
        return when {
            project == null || real == null || !real.startsWith(project) -> RenameFileState.OUTSIDE
            libraryReadOnly() && LibraryCode.locate(real, project) != null -> RenameFileState.LIBRARY
            else -> RenameFileState.CLOSED
        }
    }

    private companion object {
        val BOM = Char(0xFEFF).toString()
    }
}

private fun realFolder(folder: Path?): Path? =
    try {
        folder?.takeIf { Files.isDirectory(it) }?.toRealPath()
    } catch (_: IOException) {
        null
    }

private fun realPath(file: Path): Path? =
    try {
        file.toRealPath()
    } catch (_: IOException) {
        null
    }

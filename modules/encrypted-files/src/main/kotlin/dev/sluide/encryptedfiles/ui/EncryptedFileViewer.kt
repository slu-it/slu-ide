package dev.sluide.encryptedfiles.ui

import dev.sluide.encryptedfiles.EncryptedFiles
import dev.sluide.encryptedfiles.settings.EncryptedFilesSettings
import dev.sluide.fileviewers.ExtensionFileViewer
import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewers
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension

/**
 * Shows `.sluenc` files (ADR 0162): a locked tab first, or an unlocked one when a key was handed over for the file
 * ([EncryptedFiles.handOver]); unlocked, the view of the inner type that [FileViewers.contentViewerFor] picks by the
 * name without `.sluenc`. Opening neither reads nor decrypts the file.
 */
@Component
class EncryptedFileViewer internal constructor(
    private val files: EncryptedFiles,
    private val settings: EncryptedFilesSettings,
    /** Lazy: [FileViewers] takes every viewer, this one included. */
    private val viewers: () -> FileViewers,
) : ExtensionFileViewer {
    @Autowired
    constructor(
        files: EncryptedFiles,
        settings: EncryptedFilesSettings,
        viewers: ObjectProvider<FileViewers>,
    ) : this(files, settings, viewers::getObject)

    override val fileExtensions: Set<String> = setOf(SLUENC_EXTENSION)

    /** A readable regular file named `*.sluenc`; its content is not looked at (it is binary). */
    override fun canOpen(file: Path): Boolean =
        file.extension.equals(SLUENC_EXTENSION, ignoreCase = true) && Files.isRegularFile(file) &&
            Files.isReadable(file)

    override fun open(file: Path): FileView {
        val handover = files.takeHandover(file)
        try {
            return EncryptedFileView(file, files, viewers()::contentViewerFor, settings, handover)
        } catch (e: Throwable) {
            handover?.close()
            throw e
        }
    }
}

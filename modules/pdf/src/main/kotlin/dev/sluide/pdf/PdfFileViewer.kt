package dev.sluide.pdf

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.fileviewers.ExtensionFileViewer
import dev.sluide.fileviewers.FileView
import dev.sluide.pdf.engine.PdfSetup
import dev.sluide.pdf.view.PdfFileView
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ExecutorService
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/** Opens `.pdf` files in a [PdfFileView], read-only (ADR 0192). */
@Component
class PdfFileViewer internal constructor(
    private val renderExecutor: () -> ExecutorService,
    private val textExecutor: () -> ExecutorService,
    setup: () -> Unit,
) : ExtensionFileViewer {
    @Autowired
    constructor() : this(
        { NamedThreads.single("sluide-pdf-render") },
        { NamedThreads.single("sluide-pdf-text") },
        { PdfSetup.init(PdfSetup.defaultCacheFolder()) },
    )

    init {
        // Before the first document opens: PDFBox reads where to keep its font cache when it first maps a font.
        setup()
    }

    override val fileExtensions: Set<String> = setOf("pdf")

    override fun canOpen(file: Path): Boolean {
        if (file.extension.lowercase(Locale.ROOT) !in fileExtensions) return false
        return try {
            file.isRegularFile() && Files.isReadable(file)
        } catch (_: IOException) {
            false
        }
    }

    /** Returns at once; the document opens in the background while the view shows "Loading…". */
    override fun open(file: Path): FileView = PdfFileView(file, renderExecutor(), textExecutor())
}

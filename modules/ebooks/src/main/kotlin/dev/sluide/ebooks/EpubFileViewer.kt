package dev.sluide.ebooks

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.ebooks.view.BookLocation
import dev.sluide.ebooks.view.BookPosition
import dev.sluide.ebooks.view.EpubFileView
import dev.sluide.ebooks.view.EpubViewConfig
import dev.sluide.ebooks.view.ProjectDataPositionStore
import dev.sluide.fileviewers.ExtensionFileViewer
import dev.sluide.fileviewers.FileView
import dev.sluide.projectdata.ProjectDataStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile

/** Opens `.epub` files in an [EpubFileView], the ebook reader (ADR 0165). */
@Component
class EpubFileViewer internal constructor(
    private val data: ProjectDataStore,
    private val positionWriter: Executor,
    private val saveDelay: Duration,
    private val loaderFactory: () -> ExecutorService,
    private val config: EpubViewConfig,
) : ExtensionFileViewer {
    @Autowired
    constructor(data: ProjectDataStore) : this(
        data,
        NamedThreads.single("sluide-ebook-position"),
        Duration.ofSeconds(1),
        { NamedThreads.single("sluide-ebook-load") },
        EpubViewConfig(),
    )

    override val fileExtensions: Set<String> = setOf("epub")

    override fun canOpen(file: Path): Boolean {
        if (file.extension.lowercase(Locale.ROOT) !in fileExtensions) return false
        return try {
            file.isRegularFile() && Files.isReadable(file)
        } catch (_: IOException) {
            false
        }
    }

    /** Returns at once; the book is read and built in the background while the view shows "Loading…". */
    override fun open(file: Path): FileView {
        val location = BookLocation(file)
        val position = BookPosition(ProjectDataPositionStore(data, location), positionWriter, saveDelay)
        return EpubFileView(location, position, loaderFactory(), config)
    }
}

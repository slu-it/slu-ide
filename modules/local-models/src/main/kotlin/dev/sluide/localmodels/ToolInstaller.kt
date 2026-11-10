package dev.sluide.localmodels

import dev.sluide.backgroundtasks.BackgroundTask
import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

private val log = System.getLogger(ToolInstaller::class.java.name)

/** The row of a running download changes its name in steps of this many percent. */
private const val ROW_STEP = 5

private val ASSET_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}")

/** The folder in an install's staging folder that the archive unpacks to. */
private const val CONTENT = ".content"

/**
 * The installed versions of one command-line tool under [root] (ADR 0143): `<root>/<version>/` per version and the
 * pointers `current` and `previous`; older versions and leftovers of installs are deleted after each switch, anything
 * else in the root stays. Use one installer per root, app-wide.
 *
 * [install] downloads a release with [downloader] (part file, size and checksum check, stall watchdog), unpacks it into
 * a staging folder in the root, makes the executable executable, runs [smokeTest] on it there (null: it works, else
 * why not), marks the folder installed, moves it into place and switches the pointers. Until the switch, a failure or
 * a cancel leaves `current`, `previous` and their folders as they were and removes what the install wrote.
 * [smokeTest] runs on [executor]'s thread and must end on its own (a timeout of its own); a cancel takes effect after
 * it returns. [taskName] names the background task row: with the download's percent in steps of 5, null while
 * unpacking and checking.
 *
 * All methods and the properties belong to the FX thread; the work runs on [executor] and its results come back
 * through [dispatch] (public with the real defaults, so that tests in other modules can run the work in step). The
 * properties are read from disk on the first access of any of them. [close] cancels an install and closes the
 * [downloader].
 */
class ToolInstaller(
    root: Path,
    private val smokeTest: (executable: Path) -> String?,
    private val taskName: (release: ToolRelease, percent: Int?) -> String,
    private val downloader: ModelDownloader = ModelDownloader(),
    private val executor: Executor = Executor { NamedThreads.virtual("tool-install", it) },
    private val dispatch: (Runnable) -> Unit = Platform::runLater,
    private val maxBytes: Long = DEFAULT_MAX_TOOL_BYTES,
    maxEntries: Int = DEFAULT_MAX_TOOL_ENTRIES,
) : AutoCloseable {
    private val store = ToolStore(root)
    private val extractor = ArchiveExtractor(maxBytes, maxEntries)
    private val stateWrapper = ReadOnlyObjectWrapper<InstallState>(InstallState.NotInstalled)
    private val currentWrapper = ReadOnlyObjectWrapper<InstalledTool?>()
    private val previousWrapper = ReadOnlyObjectWrapper<InstalledTool?>()
    private var loaded = false

    /** An install, rollback or delete runs; the others wait for it to end. */
    private var busy = false
    private var cancelFlag: AtomicBoolean? = null

    /**
     * Not installed, Downloading (a negative progress while unpacking and checking), Installed (there is a current
     * version) or Failed with a readable message, until the next action; a failure keeps the current version.
     */
    val state: ReadOnlyObjectProperty<InstallState>
        get() = loaded().stateWrapper.readOnlyProperty

    /** The version in use; null when none is installed or its folder or executable has gone. */
    val current: ReadOnlyObjectProperty<InstalledTool?>
        get() = loaded().currentWrapper.readOnlyProperty

    /** The version [rollback] goes back to; null when there is none. */
    val previous: ReadOnlyObjectProperty<InstalledTool?>
        get() = loaded().previousWrapper.readOnlyProperty

    private fun loaded(): ToolInstaller {
        if (!loaded) {
            loaded = true
            show(store.read())
        }
        return this
    }

    private fun show(
        pointers: ToolPointers,
        failure: String? = null,
    ) {
        currentWrapper.set(pointers.current)
        previousWrapper.set(pointers.previous)
        stateWrapper.set(
            when {
                failure != null -> InstallState.Failed(failure)
                pointers.current != null -> InstallState.Installed
                else -> InstallState.NotInstalled
            },
        )
    }

    /** Installs [release] and makes it current; nothing while another action runs. With [tasks], a cancellable row. */
    fun install(
        release: ToolRelease,
        tasks: BackgroundTasks?,
    ) {
        loaded()
        if (busy) return
        busy = true
        val cancelled = AtomicBoolean()
        cancelFlag = cancelled
        stateWrapper.set(InstallState.Downloading(0.0))
        val task = tasks?.start(taskName(release, 0)) { cancel() }
        executor.execute {
            try {
                val installed = installNow(release, task, cancelled)
                log.log(System.Logger.Level.INFO, "Installed version ${installed.version} as ${installed.executable}")
                ended(store.read())
                task?.close()
            } catch (e: CancellationException) {
                ended(store.read())
                task?.failed(e)
            } catch (e: IOException) {
                val message = message(release, e)
                if (task == null) log.log(System.Logger.Level.WARNING, message)
                ended(store.read(), message)
                task?.failed(message)
            } catch (e: Throwable) {
                if (task == null) log.log(System.Logger.Level.ERROR, "Install of ${release.version} failed", e)
                ended(store.read(), "Install failed: ${e.message ?: e.javaClass.simpleName}")
                task?.failed(e)
                if (e is Error) throw e
            }
        }
    }

    private fun installNow(
        release: ToolRelease,
        task: BackgroundTask?,
        cancelled: AtomicBoolean,
    ): InstalledTool {
        val executable = checked(release)
        val asset = assetName(release)
        val staging = store.staging()
        try {
            val file = ModelFile(asset, release.url, release.bytes, release.checksum)
            downloader.download(
                listOf(file),
                staging,
                { progress ->
                    task?.rename(taskName(release, (progress * 100).toInt() / ROW_STEP * ROW_STEP))
                    dispatch(Runnable { stateWrapper.set(InstallState.Downloading(progress)) })
                },
                cancelled::get,
            )
            stop(cancelled)
            task?.rename(taskName(release, null))
            dispatch(Runnable { stateWrapper.set(InstallState.Downloading(-1.0)) })
            val content = staging.resolve(CONTENT)
            val path = extractor.extract(staging.resolve(asset), release.format, executable, content, cancelled::get)
            if (Files.exists(content.resolve(TOOL_PROPERTIES), LinkOption.NOFOLLOW_LINKS)) {
                throw ToolInstallException(
                    "Cannot unpack $asset: it has a $TOOL_PROPERTIES at its top, Thoreau's marker",
                )
            }
            stop(cancelled)
            smokeTest(path.toAbsolutePath())?.let {
                throw ToolInstallException("Version ${release.version} does not work: $it")
            }
            stop(cancelled)
            store.mark(content, release.version, executable)
            return placeAndSwitch(release, content)
        } finally {
            try {
                deleteTree(staging)
            } catch (e: IOException) {
                log.log(System.Logger.Level.WARNING, "Cannot delete ${staging.fileName}: ${e.message}")
            }
        }
    }

    /** Moves the checked and marked [content] into its version folder and switches to it; removes it on a failure. */
    private fun placeAndSwitch(
        release: ToolRelease,
        content: Path,
    ): InstalledTool {
        val folder = store.place(content, release.version)
        var switched = false
        try {
            store.switchTo(folder)
            switched = true
        } finally {
            if (!switched) {
                try {
                    deleteTree(folder)
                } catch (e: IOException) {
                    log.log(System.Logger.Level.WARNING, "Cannot delete ${folder.fileName}: ${e.message}")
                }
            }
        }
        try {
            store.prune()
        } catch (e: IOException) {
            // The switch is done; what is left goes with the next one.
            log.log(System.Logger.Level.WARNING, "Cannot delete old versions in ${store.root}: ${e.message}")
        }
        return store.read().current ?: throw ToolInstallException("Version ${release.version} is not usable")
    }

    /** The executable's path inside the archive, after checking what the caller supplied; readable failures. */
    private fun checked(release: ToolRelease): String {
        if (!validVersion(release.version)) throw ToolInstallException("${release.version} is not a valid version")
        if (!release.checksum.wellFormed) {
            throw ToolInstallException(
                "The checksum of version ${release.version} is not a ${release.checksum.algorithm.javaName} value",
            )
        }
        if (release.bytes !in 1..maxBytes) {
            throw ToolInstallException("The download of version ${release.version} is too large")
        }
        return entryPath(release.executable)
            ?: throw ToolInstallException("${release.executable} is not a path inside the archive")
    }

    private fun stop(cancelled: AtomicBoolean) {
        if (cancelled.get()) throw CancellationException("Install cancelled")
    }

    private fun message(
        release: ToolRelease,
        e: IOException,
    ): String =
        when (e) {
            is ModelDownloadException, is ToolInstallException -> e.message ?: "Install failed"
            is ArchiveException -> "Cannot unpack ${assetName(release)}: ${e.message}"
            else -> "Install of version ${release.version} failed: ${e.message ?: e.javaClass.simpleName}"
        }

    /** Stops a running install; the state then goes back to what is on disk. */
    fun cancel() {
        cancelFlag?.set(true)
    }

    /** Swaps the current and the previous version; nothing without a previous one or while another action runs. */
    fun rollback() {
        loaded()
        if (busy || previous.get() == null) return
        runBusy("Cannot roll back") { store.rollback() }
    }

    /** Deletes every version and the pointers (the caller asks the user first); nothing while another action runs. */
    fun deleteAll() {
        loaded()
        if (busy) return
        runBusy("Cannot delete") { store.deleteAll() }
    }

    private fun runBusy(
        failure: String,
        work: () -> Unit,
    ) {
        busy = true
        executor.execute {
            try {
                work()
                ended(store.read())
            } catch (e: IOException) {
                log.log(System.Logger.Level.WARNING, "$failure in ${store.root}: ${e.message}")
                ended(store.read(), "$failure: ${e.message ?: e.javaClass.simpleName}")
            } catch (e: Throwable) {
                log.log(System.Logger.Level.ERROR, "$failure in ${store.root}", e)
                ended(store.read(), "$failure: ${e.message ?: e.javaClass.simpleName}")
                if (e is Error) throw e
            }
        }
    }

    private fun ended(
        pointers: ToolPointers,
        failure: String? = null,
    ) = dispatch(
        Runnable {
            busy = false
            cancelFlag = null
            show(pointers, failure)
        },
    )

    override fun close() {
        cancel()
        downloader.close()
    }

    private companion object {
        /** The name of the downloaded file in messages and the staging folder: the URL's, if it is a plain one. */
        fun assetName(release: ToolRelease): String =
            release.url.path
                ?.substringAfterLast('/')
                ?.takeIf { ASSET_NAME.matches(it) }
                ?: "download"
    }
}

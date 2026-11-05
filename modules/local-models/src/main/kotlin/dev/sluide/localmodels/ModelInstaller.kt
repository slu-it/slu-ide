package dev.sluide.localmodels

import dev.sluide.backgroundtasks.BackgroundTask
import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import java.io.IOException
import java.nio.file.DirectoryIteratorException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val log = System.getLogger(ModelInstaller::class.java.name)

/** The row of a running download changes its name in steps of this many percent. */
private const val ROW_STEP = 5

/**
 * The state of the model files of each key of [specs], app-wide: one download per key at a time. All methods and the
 * state properties belong to the FX thread; downloads and deletes run on [executor] (a virtual thread each), and their
 * results come back through [dispatch]. A state that ends a download or a delete is read from disk for every key, so
 * what another key's download or delete did meanwhile (a shared file) shows.
 *
 * [executor] and [dispatch] are public with the real defaults so that tests in other modules can run the work in step.
 * [close] closes the [downloader] (its HTTP client), when the app shuts down.
 */
class ModelInstaller<K : Any>(
    private val specs: ModelSpecs<K>,
    private val downloader: ModelDownloader = ModelDownloader(),
    private val executor: Executor = Executor { NamedThreads.virtual("model-download", it) },
    private val dispatch: (Runnable) -> Unit = Platform::runLater,
) : AutoCloseable {
    private val states = LinkedHashMap<K, ReadOnlyObjectWrapper<InstallState>>()
    private val cancelFlags = HashMap<K, AtomicBoolean>()

    /** The downloads that run, in any key; a delete then keeps the part files and lets [delete]'s `keep` know. */
    private val running = AtomicInteger()

    private fun initial(key: K): InstallState =
        if (specs.installed(key)) InstallState.Installed else InstallState.NotInstalled

    private fun wrapper(key: K) = states.getOrPut(key) { ReadOnlyObjectWrapper(initial(key)) }

    private fun set(
        key: K,
        state: InstallState,
    ) = wrapper(key).set(state)

    /** Where [key] stands; created from disk on the first call. */
    fun state(key: K): ReadOnlyObjectProperty<InstallState> = wrapper(key).readOnlyProperty

    /**
     * Downloads what [key] is missing; nothing when it is downloading already. With [tasks], a row named by
     * [ModelSpecs.taskName] shows it and cancels it.
     */
    fun download(
        key: K,
        tasks: BackgroundTasks?,
    ) {
        if (state(key).get() is InstallState.Downloading) return
        val cancelled = AtomicBoolean()
        cancelFlags[key] = cancelled
        set(key, InstallState.Downloading(0.0))
        val task = tasks?.start(specs.taskName(key, 0)) { cancel(key) }
        running.incrementAndGet()
        executor.execute {
            try {
                downloader.download(
                    specs.files(key),
                    specs.folder(key),
                    { progress ->
                        task?.rename(specs.taskName(key, (progress * 100).toInt() / ROW_STEP * ROW_STEP))
                        dispatch(Runnable { set(key, InstallState.Downloading(progress)) })
                    },
                    cancelled::get,
                )
                dispatch(Runnable { ended(key) })
                task?.close()
            } catch (e: CancellationException) {
                dispatch(Runnable { ended(key) })
                task?.failed(e)
            } catch (e: ModelDownloadException) {
                fail(key, task, e.message ?: "Download failed", e)
            } catch (e: Throwable) {
                if (task == null) log.log(System.Logger.Level.ERROR, "Download of $key failed", e)
                fail(key, task, "Download failed: ${e.message ?: e.javaClass.simpleName}", e)
                if (e is Error) throw e
            } finally {
                running.decrementAndGet()
            }
        }
    }

    private fun fail(
        key: K,
        task: BackgroundTask?,
        message: String,
        error: Throwable,
    ) {
        dispatch(
            Runnable {
                // What it fetched before it failed may complete another key (a shared file).
                refreshAll()
                set(key, InstallState.Failed(message))
            },
        )
        // A cancelled task counts the failure as cancelled; otherwise the user is told with the message.
        if (error is ModelDownloadException) task?.failed(message) else task?.failed(error)
    }

    /** Stops the download of [key], if one runs; its state then goes back to what is on disk. */
    fun cancel(key: K) {
        cancelFlags[key]?.set(true)
    }

    /** Deletes the files of [key] and stray part files next to them; it is then not installed. */
    fun delete(key: K) = delete(key) { emptySet() }

    /**
     * Deletes the files of [key] but those [keep] names by [ModelFile.path]: files that another key shares. [keep]
     * runs on the worker thread right before the delete and learns whether a download runs (in any key), which may
     * need a shared file. Stray `*.part` files next to the deleted ones go too, unless a download runs. Nothing while
     * [key] downloads.
     */
    fun delete(
        key: K,
        keep: (downloadsRunning: Boolean) -> Set<String>,
    ) {
        if (state(key).get() is InstallState.Downloading) return
        executor.execute {
            var current: Path? = null
            try {
                val downloading = running.get() > 0
                val kept = keep(downloading)
                val folder = specs.folder(key)
                val files = specs.files(key).filter { it.path !in kept }.map { resolveIn(folder, it.path) }
                for (file in files) {
                    current = file
                    Files.deleteIfExists(file)
                }
                if (!downloading) {
                    for (dir in files.map { it.parent }.distinct().filter { Files.isDirectory(it) }) {
                        Files.newDirectoryStream(dir, "*.part").use { parts ->
                            parts.forEach {
                                current = it
                                Files.deleteIfExists(it)
                            }
                        }
                    }
                }
                dispatch(Runnable { refreshAll() })
            } catch (e: IOException) {
                deleteFailed(key, current, e.message)
            } catch (e: DirectoryIteratorException) {
                deleteFailed(key, current, e.cause?.message)
            } catch (e: IllegalArgumentException) {
                deleteFailed(key, null, e.message)
            }
        }
    }

    private fun deleteFailed(
        key: K,
        file: Path?,
        message: String?,
    ) {
        val name = file?.fileName ?: key
        dispatch(Runnable { set(key, InstallState.Failed("Cannot delete $name: $message")) })
    }

    /** A download of [key] ended without a failure: its state and every other key's are read from disk. */
    private fun ended(key: K) {
        set(key, initial(key))
        refreshAll()
    }

    /** Reads what is on disk again for every key that is not downloading. */
    private fun refreshAll() {
        for ((key, state) in states) {
            if (state.get() !is InstallState.Downloading) state.set(initial(key))
        }
    }

    override fun close() = downloader.close()
}

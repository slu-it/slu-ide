package dev.sluide.speech.install

import dev.sluide.backgroundtasks.BackgroundTask
import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.speech.engine.SpeechException
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private val log = System.getLogger(ModelInstaller::class.java.name)

/** Where the model files of one [KokoroVariant] stand. */
sealed interface InstallState {
    data object NotInstalled : InstallState

    data class Downloading(
        val progress: Double,
    ) : InstallState

    data object Installed : InstallState

    data class Failed(
        val message: String,
    ) : InstallState
}

/** What a download does: the variant, a progress callback (0..1) and a cancel check; blocking. */
internal typealias DownloadWork = (KokoroVariant, (Double) -> Unit, () -> Boolean) -> Unit

/** The row of a running download changes its name in steps of this many percent. */
private const val ROW_STEP = 5

/**
 * The state of the Kokoro model files, app-wide: one download per variant at a time across windows. All methods and
 * the state properties belong to the FX thread; the work runs on a thread of its own, and its results come back
 * through [dispatch]. A state that ends a download or a delete is read from disk, so what another variant's download
 * or delete did meanwhile shows.
 */
@Component
class ModelInstaller internal constructor(
    private val store: ModelStore,
    private val work: DownloadWork,
    private val executor: Executor,
    private val dispatch: (Runnable) -> Unit,
    private val onClose: () -> Unit = {},
) : DisposableBean {
    @Autowired
    constructor(store: ModelStore) : this(store, Downloads(store))

    private constructor(store: ModelStore, downloads: Downloads) : this(
        store,
        downloads::download,
        { NamedThreads.virtual("kokoro-model", it) },
        Platform::runLater,
        downloads::close,
    )

    /** The one HTTP client of the installer, created with the first download. */
    private class Downloads(
        private val store: ModelStore,
    ) {
        private val client = lazy { ModelDownloader.defaultHttpClient() }

        fun download(
            v: KokoroVariant,
            progress: (Double) -> Unit,
            cancelled: () -> Boolean,
        ) = ModelDownloader(store, client.value).download(v, progress, cancelled)

        fun close() {
            if (client.isInitialized()) client.value.shutdownNow()
        }
    }

    private val states = KokoroVariant.entries.associateWith { ReadOnlyObjectWrapper<InstallState>(initial(it)) }
    private val cancelFlags = HashMap<KokoroVariant, AtomicBoolean>()

    /** The downloads that run; a delete then keeps the voices file they rely on. */
    private val running = AtomicInteger()

    private fun initial(v: KokoroVariant): InstallState =
        if (store.installed(v)) InstallState.Installed else InstallState.NotInstalled

    private fun set(
        v: KokoroVariant,
        state: InstallState,
    ) = checkNotNull(states[v]).set(state)

    fun state(v: KokoroVariant): ReadOnlyObjectProperty<InstallState> = checkNotNull(states[v]).readOnlyProperty

    /**
     * Downloads what [v] is missing; nothing when it is downloading already. With [tasks], a row "Downloading Kokoro
     * model 40 %" shows it and cancels it.
     */
    fun download(
        v: KokoroVariant,
        tasks: BackgroundTasks?,
    ) {
        if (state(v).get() is InstallState.Downloading) return
        val cancelled = AtomicBoolean()
        cancelFlags[v] = cancelled
        set(v, InstallState.Downloading(0.0))
        val task = tasks?.start(taskName(0.0)) { cancel(v) }
        running.incrementAndGet()
        executor.execute {
            try {
                work(
                    v,
                    { progress ->
                        task?.rename(taskName(progress))
                        dispatch(Runnable { set(v, InstallState.Downloading(progress)) })
                    },
                    cancelled::get,
                )
                dispatch(Runnable { set(v, initial(v)) })
                task?.close()
            } catch (e: CancellationException) {
                dispatch(Runnable { set(v, initial(v)) })
                task?.failed(e)
            } catch (e: SpeechException) {
                fail(v, task, e.message ?: "Download failed", e)
            } catch (e: Throwable) {
                if (task == null) log.log(System.Logger.Level.ERROR, "Download of ${v.fileName} failed", e)
                fail(v, task, "Download failed: ${e.message ?: e.javaClass.simpleName}", e)
                if (e is Error) throw e
            } finally {
                running.decrementAndGet()
            }
        }
    }

    private fun fail(
        v: KokoroVariant,
        task: BackgroundTask?,
        message: String,
        error: Throwable,
    ) {
        dispatch(Runnable { set(v, InstallState.Failed(message)) })
        // A cancelled task counts the failure as cancelled; otherwise the user is told with the message.
        if (error is SpeechException) task?.failed(message) else task?.failed(error)
    }

    /** Stops the download of [v], if one runs; its state then goes back to what is on disk. */
    fun cancel(v: KokoroVariant) {
        cancelFlags[v]?.set(true)
    }

    /** Deletes the model of [v] (and the voices file if no variant stays or downloads); it is then not installed. */
    fun delete(v: KokoroVariant) {
        if (state(v).get() is InstallState.Downloading) return
        executor.execute {
            try {
                store.delete(v, keepVoices = running.get() > 0)
                dispatch(Runnable { refreshAll() })
            } catch (e: IOException) {
                dispatch(Runnable { set(v, InstallState.Failed("Cannot delete ${v.fileName}: ${e.message}")) })
            }
        }
    }

    /** Reads what is on disk again for every variant that is not downloading. */
    private fun refreshAll() {
        for (v in KokoroVariant.entries) {
            if (state(v).get() !is InstallState.Downloading) set(v, initial(v))
        }
    }

    override fun destroy() = onClose()

    private fun taskName(progress: Double) =
        "Downloading Kokoro model ${(progress * 100).toInt() / ROW_STEP * ROW_STEP} %"
}

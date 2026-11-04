package dev.sluide.speech.install

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.speech.engine.SpeechException
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.net.http.HttpClient
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

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

/**
 * The state of the Kokoro model files, app-wide: one download per variant at a time across windows. All methods and
 * the state properties belong to the FX thread; the work runs on a thread of its own, and its results come back
 * through [dispatch].
 */
@Component
class ModelInstaller internal constructor(
    private val store: ModelStore,
    private val work: DownloadWork,
    private val executor: Executor,
    private val dispatch: (Runnable) -> Unit,
) {
    @Autowired
    constructor(store: ModelStore) : this(
        store,
        { v, progress, cancelled ->
            val http =
                HttpClient
                    .newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(15))
                    .build()
            ModelDownloader(store, http).download(v, progress, cancelled)
        },
        { NamedThreads.virtual("kokoro-model", it) },
        Platform::runLater,
    )

    private val states =
        KokoroVariant.entries.associateWith {
            ReadOnlyObjectWrapper<InstallState>(initial(it))
        }
    private val cancelFlags = HashMap<KokoroVariant, AtomicBoolean>()

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
                dispatch(Runnable { set(v, InstallState.Installed) })
                task?.close()
            } catch (e: CancellationException) {
                dispatch(Runnable { set(v, initial(v)) })
                task?.failed(e)
            } catch (e: SpeechException) {
                fail(v, task, e.message ?: "Download failed", e)
            } catch (e: Exception) {
                log.log(System.Logger.Level.ERROR, "Download of ${v.fileName} failed", e)
                fail(v, task, "Download failed: ${e.message ?: e.javaClass.simpleName}", e)
            }
        }
    }

    private fun fail(
        v: KokoroVariant,
        task: dev.sluide.backgroundtasks.BackgroundTask?,
        message: String,
        error: Exception,
    ) {
        dispatch(Runnable { set(v, InstallState.Failed(message)) })
        // A cancelled task counts the failure as cancelled; otherwise the user is told with the message.
        if (error is SpeechException) task?.failed(message) else task?.failed(error)
    }

    /** Stops the download of [v], if one runs; its state then goes back to what is on disk. */
    fun cancel(v: KokoroVariant) {
        cancelFlags[v]?.set(true)
    }

    /** Deletes the model of [v] (and the voices file if no variant stays); it is then not installed. */
    fun delete(v: KokoroVariant) {
        if (state(v).get() is InstallState.Downloading) return
        executor.execute {
            try {
                store.delete(v)
                dispatch(Runnable { refreshAll() })
            } catch (e: java.io.IOException) {
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

    private fun taskName(progress: Double) = "Downloading Kokoro model ${(progress * 100).toInt()} %"
}

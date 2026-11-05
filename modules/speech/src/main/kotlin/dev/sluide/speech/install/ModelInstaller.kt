package dev.sluide.speech.install

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.localmodels.InstallState
import dev.sluide.localmodels.ModelDownloader
import javafx.application.Platform
import javafx.beans.property.ReadOnlyObjectProperty
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.util.concurrent.Executor
import dev.sluide.localmodels.ModelInstaller as LocalModelInstaller

/**
 * The Kokoro model files, app-wide: the shared [LocalModelInstaller] over the variants of [store] (ADR 0125), one
 * download per variant at a time across windows. All methods and the states belong to the FX thread.
 *
 * What stays Kokoro's own: the variants share the voices file, so deleting one keeps it while another variant is
 * installed or a download runs ([ModelStore.keptOnDelete]).
 */
@Component
class ModelInstaller internal constructor(
    private val store: ModelStore,
    downloader: ModelDownloader,
    executor: Executor,
    dispatch: (Runnable) -> Unit,
) : DisposableBean {
    @Autowired
    constructor(store: ModelStore) : this(
        store,
        ModelDownloader(),
        { NamedThreads.virtual("kokoro-model", it) },
        Platform::runLater,
    )

    private val installer = LocalModelInstaller(store, downloader, executor, dispatch)

    fun state(v: KokoroVariant): ReadOnlyObjectProperty<InstallState> = installer.state(v)

    /**
     * Downloads what [v] is missing; nothing when it is downloading already. With [tasks], a row "Downloading Kokoro
     * model 40 %" shows it and cancels it.
     */
    fun download(
        v: KokoroVariant,
        tasks: BackgroundTasks?,
    ) = installer.download(v, tasks)

    /** Stops the download of [v], if one runs; its state then goes back to what is on disk. */
    fun cancel(v: KokoroVariant) = installer.cancel(v)

    /** Deletes the model of [v] (and the voices file if no variant stays or downloads); it is then not installed. */
    fun delete(v: KokoroVariant) = installer.delete(v) { running -> store.keptOnDelete(v, running) }

    override fun destroy() = installer.close()
}

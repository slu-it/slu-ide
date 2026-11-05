package dev.sluide.translation.session

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.localmodels.InstallState
import dev.sluide.localmodels.ModelInstaller
import dev.sluide.translation.engine.PairModel
import javafx.beans.value.ChangeListener

/** The install states of the pair models as the Translate model sees them; FX thread only. */
internal interface ModelInstalls {
    fun state(model: PairModel): InstallState

    /** What downloading [models] would still fetch, in bytes (files they share counted once, present ones not). */
    fun missingBytes(models: List<PairModel>): Long

    /**
     * Starts the download of each of [models] that is not downloading. An installed one is not skipped: its download
     * fetches nothing and ends with a state change, so a watcher that missed the install hears of it.
     */
    fun download(models: List<PairModel>)

    /** Stops the downloads of [models] that run. */
    fun cancel(models: List<PairModel>)

    /** Calls [listener] whenever the state of one of [models] changes, until the returned handle is closed. */
    fun watch(
        models: List<PairModel>,
        listener: () -> Unit,
    ): AutoCloseable
}

/**
 * [ModelInstalls] over the app-wide [installer]; [bytes] tells the download size of models (the engine's catalog; called
 * on the FX thread, so a cheap look at the disk at most), [tasks] shows each download as a background task row.
 */
internal class InstallerModelInstalls(
    private val installer: ModelInstaller<PairModel>,
    private val bytes: (List<PairModel>) -> Long,
    private val tasks: BackgroundTasks?,
) : ModelInstalls {
    override fun state(model: PairModel): InstallState = installer.state(model).get()

    override fun missingBytes(models: List<PairModel>): Long = bytes(models)

    override fun download(models: List<PairModel>) {
        // ModelInstaller refuses a second download of a key, and the downloader skips files already there.
        for (model in models) installer.download(model, tasks)
    }

    override fun cancel(models: List<PairModel>) {
        for (model in models) installer.cancel(model)
    }

    override fun watch(
        models: List<PairModel>,
        listener: () -> Unit,
    ): AutoCloseable {
        val properties = models.distinct().map(installer::state)
        val change = ChangeListener<InstallState> { _, _, _ -> listener() }
        properties.forEach { it.addListener(change) }
        return AutoCloseable { properties.forEach { it.removeListener(change) } }
    }
}

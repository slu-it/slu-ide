package dev.sluide.docker.tab

import dev.sluide.docker.DockerSession
import dev.sluide.docker.cli.DockerCli
import dev.sluide.docker.model.Container
import dev.sluide.docker.model.ContainerDetails
import dev.sluide.docker.tab.tree.ContainerRow
import dev.sluide.docker.tab.tree.ResourceKey
import dev.sluide.docker.tab.tree.Resources
import dev.sluide.docker.tab.tree.TreeNode
import dev.sluide.docker.tab.tree.TreeRow
import dev.sluide.docker.tab.tree.findRow
import dev.sluide.docker.tab.tree.resourceTree
import javafx.application.Platform
import javafx.beans.InvalidationListener
import javafx.beans.Observable
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.property.SimpleStringProperty
import javafx.collections.FXCollections
import javafx.collections.ObservableSet
import java.nio.file.Path
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/** What the details of the selected container are: being loaded, loaded, or why they could not be. */
internal sealed interface DetailsLoad {
    data object Loading : DetailsLoad

    data class Loaded(
        val details: ContainerDetails,
    ) : DetailsLoad

    data class Failed(
        val message: String,
    ) : DetailsLoad
}

/**
 * The state behind one Docker tab, without controls (ADR 0084): it subscribes to the [session] while the tab is open,
 * builds the [tree] from the session's lists, the [projectFolder] and the [filter], keeps the [selection] by resource
 * identity across reloads, loads the [containerDetails] of a selected container (again when its list entry changes)
 * and runs actions, reporting failures in [error]. FX thread only; results of the work [session] runs elsewhere come
 * back through [fx].
 */
internal class DockerTabModel(
    val session: DockerSession,
    private val fx: (Runnable) -> Unit = Platform::runLater,
) : AutoCloseable {
    /** The open project folder; its Compose projects come first and are marked. */
    val projectFolder = SimpleObjectProperty<Path?>()

    /** The text of the tree's filter field. */
    val filter = SimpleStringProperty("")

    /** The message of the error bar, `null` while it is hidden. */
    val error = SimpleStringProperty()

    private val treeWrapper = ReadOnlyObjectWrapper<List<TreeNode>>(emptyList())
    private val selectionWrapper = ReadOnlyObjectWrapper<ResourceKey?>()
    private val selectedRowWrapper = ReadOnlyObjectWrapper<TreeRow?>()
    private val detailsWrapper = ReadOnlyObjectWrapper<DetailsLoad?>()
    private val runningKeys = FXCollections.observableSet<String>()

    /** The tree's rows, rebuilt on every change of the lists, the folder or the filter. */
    val tree: ReadOnlyObjectProperty<List<TreeNode>> = treeWrapper.readOnlyProperty

    /** What is selected; cleared when it is gone from the [tree]. */
    val selection: ReadOnlyObjectProperty<ResourceKey?> = selectionWrapper.readOnlyProperty

    /** The current row of the [selection], replaced when its resource changes. */
    val selectedRow: ReadOnlyObjectProperty<TreeRow?> = selectedRowWrapper.readOnlyProperty

    /** The details of the selected container; `null` unless a container is selected. */
    val containerDetails: ReadOnlyObjectProperty<DetailsLoad?> = detailsWrapper.readOnlyProperty

    /** The keys ([actionKey]) of the actions running now; their buttons are disabled meanwhile. */
    val running: ObservableSet<String> = FXCollections.unmodifiableObservableSet(runningKeys)

    private var pending: ResourceKey? = null
    private var detailsFor: Container? = null
    private var detailsGeneration = 0
    private var closed = false
    private val observed: List<Observable> =
        listOf(session.containers, session.images, session.volumes, session.networks, projectFolder, filter)
    private val rebuildListener = InvalidationListener { rebuild() }
    private val subscription: AutoCloseable

    init {
        observed.forEach { it.addListener(rebuildListener) }
        subscription = session.subscribe()
        rebuild()
    }

    /**
     * Selects [key]; one not in the tree yet (a container [dev.sluide.docker.Docker.showContainer] asked for before the
     * lists are loaded) is selected once it appears.
     */
    fun select(key: ResourceKey?) {
        if (key != null && findRow(tree.get(), key) == null) {
            pending = key
            return
        }
        pending = null
        selectionWrapper.set(key)
        selectionChanged()
    }

    /** Reloads everything (Refresh, F5, Retry). */
    fun refresh() = session.refresh()

    /**
     * Runs [action] with the CLI off the FX thread; while it runs, [runningKey] (if any) is in [running]. Afterwards
     * [onDone] gets the result on the FX thread, or the failure goes to [error]; a cancelled action reports nothing.
     * Returns the session's future, which [CompletableFuture.cancel] with `true` interrupts.
     */
    fun <T> run(
        name: String,
        runningKey: String? = null,
        onDone: (T) -> Unit = {},
        action: (DockerCli) -> T,
    ): CompletableFuture<T> {
        runningKey?.let { runningKeys += it }
        val future = session.submit(name, action)
        future.whenComplete { result, failure ->
            fx {
                runningKey?.let { runningKeys -= it }
                if (closed) return@fx
                val cause = failure?.unwrapped()
                when {
                    cause == null -> onDone(result)
                    cause is CancellationException -> Unit
                    else -> error.set(messageOf(cause))
                }
            }
        }
        return future
    }

    /**
     * Loads something for the selected [key] off the FX thread (Inspect, details); the result reaches [onLoaded], a
     * failure [onFailed], on the FX thread, and both are dropped if [key] is no longer selected by then.
     */
    fun <T> load(
        name: String,
        key: ResourceKey,
        onFailed: (String) -> Unit = {},
        onLoaded: (T) -> Unit,
        work: (DockerCli) -> T,
    ) {
        session.submit(name, work).whenComplete { result, failure ->
            fx {
                if (closed || selection.get() != key) return@fx
                if (failure == null) onLoaded(result) else onFailed(messageOf(failure.unwrapped()))
            }
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        observed.forEach { it.removeListener(rebuildListener) }
        subscription.close()
    }

    private fun rebuild() {
        if (closed) return
        val nodes =
            resourceTree(
                Resources(
                    session.containers.toList(),
                    session.images.toList(),
                    session.volumes.toList(),
                    session.networks.toList(),
                ),
                projectFolder.get(),
                filter.get() ?: "",
            )
        treeWrapper.set(nodes)
        pending?.takeIf { findRow(nodes, it) != null }?.let {
            pending = null
            selectionWrapper.set(it)
        }
        if (selection.get()?.let { findRow(nodes, it) } == null) selectionWrapper.set(null)
        selectionChanged()
    }

    private fun selectionChanged() {
        val row = selection.get()?.let { findRow(tree.get(), it) }
        if (row != selectedRowWrapper.get()) selectedRowWrapper.set(row)
        val container = (row as? ContainerRow)?.container
        if (container == null) {
            detailsFor = null
            detailsWrapper.set(null)
            return
        }
        if (container == detailsFor) return
        val another = detailsFor?.id != container.id
        detailsFor = container
        // A changed list entry reloads the details and keeps the old ones until then; another container shows Loading.
        if (another) detailsWrapper.set(DetailsLoad.Loading)
        val generation = ++detailsGeneration
        load(
            "details",
            row.key,
            onFailed = { message ->
                if (generation ==
                    detailsGeneration
                ) {
                    detailsWrapper.set(DetailsLoad.Failed(message))
                }
            },
            onLoaded = { details: ContainerDetails ->
                if (generation == detailsGeneration) detailsWrapper.set(DetailsLoad.Loaded(details))
            },
        ) { cli -> cli.containerDetails(container.id) }
    }
}

/** The [DockerTabModel.running] key of [command] on the container [id]. */
internal fun actionKey(
    id: String,
    command: String,
): String = "$id:$command"

internal fun messageOf(failure: Throwable): String = failure.message?.takeIf { it.isNotBlank() } ?: failure.toString()

private fun Throwable.unwrapped(): Throwable = if (this is CompletionException) cause ?: this else this

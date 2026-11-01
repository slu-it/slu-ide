package dev.sluide.docker.tab.details

import dev.sluide.docker.DockerSession
import dev.sluide.docker.tab.logs.LogView
import dev.sluide.docker.tab.stats.StatsView
import dev.sluide.docker.tab.tree.ResourceKey
import javafx.scene.Node

/** The tabs of a resource's details; images, volumes and networks have only Overview and Inspect. */
internal enum class DetailsTab(
    val title: String,
) {
    OVERVIEW("Overview"),
    LOGS("Logs"),
    STATS("Stats"),
    ENVIRONMENT("Environment"),
    INSPECT("Inspect"),
}

/** A view that streams something of one container while it is shown: the Logs and Stats tabs. */
internal interface StreamView {
    val node: Node

    /** Streams for the container [containerId]; `null` stops. */
    fun show(containerId: String?)

    fun dispose()
}

/** Creates the [StreamView]s of a Docker tab; tests replace them, the real ones are [LogView] and [StatsView]. */
internal class StreamViews(
    val logs: (DockerSession) -> StreamView = { session -> LogView(session).asStreamView() },
    val stats: (DockerSession) -> StreamView = { session -> StatsView(session).asStreamView() },
)

private fun LogView.asStreamView(): StreamView {
    val view = this
    return object : StreamView {
        override val node: Node = view.node

        override fun show(containerId: String?) = view.show(containerId)

        override fun dispose() = view.dispose()
    }
}

private fun StatsView.asStreamView(): StreamView {
    val view = this
    return object : StreamView {
        override val node: Node = view.node

        override fun show(containerId: String?) = view.show(containerId)

        override fun dispose() = view.dispose()
    }
}

/**
 * What follows from the selected details tab and resource: [logs] and [stats] stream only while their tab is shown
 * for a selected container, and the Inspect JSON is loaded ([loadInspect]) when its tab is shown for a resource, once
 * per selection; another resource clears it first ([clearInspect]). FX thread only.
 */
internal class DetailsTabSwitch(
    private val logs: StreamView?,
    private val stats: StreamView?,
    private val clearInspect: () -> Unit,
    private val loadInspect: (ResourceKey) -> Unit,
) {
    private var shownFor: ResourceKey? = null
    private var inspected = false

    /** The details now show [tab] for [key] ([containerId] if it is a container, else `null`). */
    fun update(
        tab: DetailsTab,
        key: ResourceKey?,
        containerId: String?,
    ) {
        logs?.show(containerId.takeIf { tab == DetailsTab.LOGS })
        stats?.show(containerId.takeIf { tab == DetailsTab.STATS })
        if (key != shownFor) {
            shownFor = key
            inspected = false
            clearInspect()
        }
        if (tab == DetailsTab.INSPECT && key != null && !inspected) {
            inspected = true
            loadInspect(key)
        }
    }
}

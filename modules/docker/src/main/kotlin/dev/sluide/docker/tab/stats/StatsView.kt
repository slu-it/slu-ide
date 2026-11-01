package dev.sluide.docker.tab.stats

import dev.sluide.docker.DockerSession
import dev.sluide.docker.model.Container
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.model.StatsSample
import dev.sluide.docker.tab.HandOffQueue
import javafx.application.Platform
import javafx.collections.ListChangeListener
import javafx.geometry.Insets
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.VBox
import kotlin.math.max

/** What the reader thread hands over. */
private sealed interface StatsEvent {
    data class Sample(
        val sample: StatsSample,
        val atNanos: Long,
    ) : StatsEvent

    data class Ended(
        val failure: Throwable?,
    ) : StatsEvent
}

/**
 * The Stats tab of a container's details in the Docker tab: live CPU, memory, network and block IO of one container
 * through [session]. The Docker tab calls [show] with the selected container while the Stats tab is shown and with
 * `null` otherwise, so that stats stream only while they are visible. FX thread only.
 *
 * A container that does not run shows "Not running" and opens no stream; the stream starts when it runs. When it
 * stops, the last values stay, greyed, under the note. [handOff] brings samples to the FX thread, [nanoTime] stamps
 * their arrival (the rates are averaged over the last 60 samples, see [StatsHistory]).
 */
internal class StatsView(
    private val session: DockerSession,
    private val handOff: (Runnable) -> Unit = Platform::runLater,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    private val history = StatsHistory()
    private val note = Label()
    private val pids = Label()
    private val cpuValue = Label("–")
    private val memoryValue = Label("–")
    private val networkValue = Label("–")
    private val blockValue = Label("–")
    private val cpuChart = Sparkline()
    private val memoryChart = Sparkline()
    private val networkChart = Sparkline(2, "Receive: accent colour, send: text colour")
    private val blockChart = Sparkline(2, "Read: accent colour, write: text colour")
    private val rows = VBox(10.0)

    private var containerId: String? = null
    private var stream: AutoCloseable? = null
    private var running = false
    private var ended = false
    private var endedEntry: Container? = null
    private var error: String? = null
    private var queue: HandOffQueue<StatsEvent>? = null
    private val containersListener = ListChangeListener<Container> { containersChanged() }

    /** The view's node. */
    val node: Node

    init {
        note.id = "stats-note"
        note.style = "-fx-opacity: 0.7;"
        pids.id = "stats-pids"
        cpuValue.id = "stats-cpu"
        memoryValue.id = "stats-memory"
        networkValue.id = "stats-network"
        blockValue.id = "stats-block"
        rows.children.addAll(
            row("CPU", cpuValue, cpuChart),
            row("Memory", memoryValue, memoryChart),
            row("Network", networkValue, networkChart),
            row("Block IO", blockValue, blockChart),
            pids,
        )
        node = VBox(10.0, note, rows).apply { padding = Insets(12.0) }
        session.containers.addListener(containersListener)
        render()
    }

    /**
     * Shows the stats of the container with [containerId]; `null` stops the stream and clears the view. Calling it
     * again with the container already shown does nothing.
     */
    fun show(containerId: String?) {
        if (containerId == this.containerId) return
        closeStream()
        history.clear()
        this.containerId = containerId
        running = containerId != null && isRunning()
        if (running) open()
        render()
    }

    /** Stops the stream for good; called when the Docker tab closes. */
    fun dispose() {
        session.containers.removeListener(containersListener)
        containerId = null
        closeStream()
    }

    private fun currentContainer(): Container? = session.containers.firstOrNull { it.id == containerId }

    private fun isRunning() = currentContainer()?.state == ContainerState.RUNNING

    /**
     * Follows the container: its stream starts when it runs and stops when it does not; a stream that ended while the
     * container ran (it restarted faster than the list reloads) starts again when its entry changed.
     */
    private fun containersChanged() {
        if (containerId == null) return
        val now = isRunning()
        val restarted = ended && now && currentContainer() != endedEntry
        if (now == running && !restarted) return
        running = now
        if (now) {
            history.clear()
            open()
        } else {
            closeStream()
        }
        render()
    }

    private fun open() {
        val id = containerId ?: return
        ended = false
        error = null
        val events =
            HandOffQueue<StatsEvent>(handOff) { batch ->
                batch.forEach { handle(it) }
                render()
            }
        queue = events
        stream =
            session.openStream(
                "Stats $id",
                { cli ->
                    cli.stats(
                        id,
                        { events.add(StatsEvent.Sample(it, nanoTime())) },
                        { events.add(StatsEvent.Ended(it)) },
                    )
                },
                { failure -> if (queue === events) showError(failure.message ?: failure.toString()) },
            )
    }

    private fun handle(event: StatsEvent) {
        when (event) {
            is StatsEvent.Sample -> {
                history.add(event.sample, event.atNanos)
            }

            is StatsEvent.Ended -> {
                val failure = event.failure
                if (failure != null) {
                    error = failure.message ?: failure.toString()
                } else {
                    ended = true
                    endedEntry = currentContainer()
                    error = "Stream ended"
                }
            }
        }
    }

    private fun showError(message: String) {
        error = message
        render()
    }

    private fun closeStream() {
        queue?.cancel()
        queue = null
        stream?.close()
        stream = null
        error = null
        ended = false
    }

    private fun render() {
        note.text =
            when {
                containerId == null -> ""
                !running -> "Not running"
                else -> error ?: ""
            }
        note.isManaged = note.text.isNotEmpty()
        note.isVisible = note.isManaged
        rows.opacity = if (containerId != null && !running) 0.5 else 1.0
        val last = history.last
        cpuValue.text = last?.let { formatPercent(it.cpuPercent) } ?: "–"
        memoryValue.text = last?.let { formatMemory(it) } ?: "–"
        val rates = history.rates
        networkValue.text =
            rates?.let { "↓ ${formatRate(it.networkReceive)}   ↑ ${formatRate(it.networkSend)}" } ?: "–"
        blockValue.text =
            rates?.let { "read ${formatRate(it.blockRead)}   write ${formatRate(it.blockWrite)}" } ?: "–"
        pids.text = last?.pids?.let { "PIDs: $it" } ?: ""
        pids.isManaged = pids.text.isNotEmpty()
        pids.isVisible = pids.isManaged

        cpuChart.update(listOf(history.cpu.values), max(100.0, history.cpu.values.maxOrNull() ?: 0.0))
        val limit = last?.memoryLimit?.toDouble() ?: 0.0
        memoryChart.update(
            listOf(history.memory.values),
            if (limit > 0) limit else history.memory.values.maxOrNull() ?: 0.0,
        )
        networkChart.update(
            listOf(history.networkReceive.values, history.networkSend.values),
            peak(history.networkReceive, history.networkSend),
        )
        blockChart.update(
            listOf(history.blockRead.values, history.blockWrite.values),
            peak(history.blockRead, history.blockWrite),
        )
    }

    private fun peak(vararg rings: Ring) = rings.maxOf { it.values.maxOrNull() ?: 0.0 }

    private fun row(
        title: String,
        value: Label,
        chart: Sparkline,
    ): Node {
        val name = Label(title)
        name.style = "-fx-font-weight: bold;"
        val spacer = Region()
        HBox.setHgrow(spacer, Priority.ALWAYS)
        return VBox(2.0, HBox(8.0, name, spacer, value), chart)
    }
}

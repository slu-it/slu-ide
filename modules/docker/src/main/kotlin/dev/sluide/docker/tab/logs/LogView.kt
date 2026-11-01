package dev.sluide.docker.tab.logs

import dev.sluide.docker.DockerSession
import dev.sluide.docker.model.Container
import dev.sluide.docker.model.ContainerState
import dev.sluide.docker.tab.HandOffQueue
import javafx.application.Platform
import javafx.collections.FXCollections
import javafx.collections.ListChangeListener
import javafx.geometry.Orientation
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.ComboBox
import javafx.scene.control.ContextMenu
import javafx.scene.control.ListCell
import javafx.scene.control.ListView
import javafx.scene.control.MenuItem
import javafx.scene.control.ScrollBar
import javafx.scene.control.SelectionMode
import javafx.scene.control.TextField
import javafx.scene.control.ToggleButton
import javafx.scene.control.ToolBar
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.KeyCombination
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.BorderPane
import javafx.scene.layout.Region
import javafx.scene.text.Font
import javafx.util.StringConverter
import java.time.Instant

/** How much of the log is loaded when the stream opens. */
internal enum class TailChoice(
    val label: String,
    val lines: Int?,
) {
    LAST_100("Last 100", 100),
    LAST_1000("Last 1000", 1000),
    LAST_10000("Last 10 000", 10_000),
    ALL("All", null),
}

/** What the reader thread hands over: a line, or the end of the stream (`null` = it ended normally). */
private sealed interface LogEvent {
    data class Line(
        val time: Instant?,
        val text: String,
    ) : LogEvent

    data class Ended(
        val failure: Throwable?,
    ) : LogEvent
}

/**
 * The Logs tab of a container's details in the Docker tab: follows the logs of one container through [session]. The
 * Docker tab calls [show] with the selected container while the Logs tab is shown and with `null` otherwise, so that
 * logs stream only while they are visible. FX thread only.
 *
 * Lines are cleaned (escape sequences, `\r`) and their timestamp split off on the reader thread, then handed to the
 * FX thread in batches ([handOff]); the list is virtualized and holds [maxLines] (50 000) lines at most. When the
 * stream ends because the container stopped, a note says so and the stream reopens once the container runs again.
 */
internal class LogView(
    private val session: DockerSession,
    private val handOff: (Runnable) -> Unit = Platform::runLater,
    private val maxLines: Int = MAX_LOG_LINES,
) {
    private val buffer = LogBuffer(maxLines)
    private val visible = FXCollections.observableArrayList<LogLine>()
    private val list = ListView(visible)
    private val filter = TextField()
    private val timestamps = ToggleButton("Timestamps")
    private val wrap = ToggleButton("Wrap")
    private val follow = ToggleButton("Follow")
    private val tail = ComboBox(FXCollections.observableArrayList(*TailChoice.entries.toTypedArray()))

    private var containerId: String? = null
    private var stream: AutoCloseable? = null
    private var queue: HandOffQueue<LogEvent>? = null
    private var endedNormally = false
    private var sawNotRunning = false
    private var endedEntry: Container? = null
    private val containersListener = ListChangeListener<Container> { containersChanged() }

    /** The view's node. */
    val node: Node

    init {
        filter.id = "log-filter"
        filter.promptText = "Filter"
        filter.textProperty().addListener { _, _, _ -> refilter() }
        timestamps.id = "log-timestamps"
        timestamps.selectedProperty().addListener { _, _, _ -> list.refresh() }
        wrap.id = "log-wrap"
        wrap.selectedProperty().addListener { _, _, _ -> list.refresh() }
        follow.id = "log-follow"
        follow.isSelected = true
        follow.selectedProperty().addListener { _, _, on -> if (on) scrollToEnd() }
        val clear = Button("Clear")
        clear.id = "log-clear"
        clear.setOnAction { clear() }
        tail.id = "log-tail"
        tail.value = TailChoice.LAST_1000
        tail.converter =
            object : StringConverter<TailChoice>() {
                override fun toString(choice: TailChoice?) = choice?.label ?: ""

                override fun fromString(text: String?): TailChoice? = null
            }
        tail.valueProperty().addListener { _, _, _ -> if (containerId != null) reopen() }

        list.id = "log-list"
        list.selectionModel.selectionMode = SelectionMode.MULTIPLE
        list.setCellFactory { LogCell() }
        val copy = MenuItem("Copy")
        copy.setOnAction { copySelection() }
        list.contextMenu = ContextMenu(copy)
        list.addEventFilter(KeyEvent.KEY_PRESSED) { event ->
            if (KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN).match(event)) {
                copySelection()
                event.consume()
            } else if (event.code in SCROLL_KEYS) {
                userScrolled(away = event.code in KEYS_AWAY_FROM_END)
            }
        }
        // What the user scrolls with: the wheel anywhere over the list, the scroll bar's thumb.
        list.addEventFilter(ScrollEvent.SCROLL) { event -> userScrolled(away = event.deltaY > 0) }
        list.addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            if (onScrollBar(event.target)) userScrolled(away = true)
        }
        listOf(MouseEvent.MOUSE_DRAGGED, MouseEvent.MOUSE_RELEASED).forEach { type ->
            list.addEventFilter(type) { event -> if (onScrollBar(event.target)) userScrolled(away = false) }
        }

        node = BorderPane(list).apply { top = ToolBar(filter, timestamps, wrap, follow, clear, tail) }
        session.containers.addListener(containersListener)
    }

    /**
     * Shows and follows the logs of the container with [containerId]; `null` stops following and clears the view.
     * Calling it again with the container already shown does nothing.
     */
    fun show(containerId: String?) {
        if (containerId == this.containerId) return
        this.containerId = containerId
        if (containerId == null) {
            closeStream()
            clear()
        } else {
            reopen()
        }
    }

    /** Stops following for good; called when the Docker tab closes. */
    fun dispose() {
        session.containers.removeListener(containersListener)
        containerId = null
        closeStream()
    }

    private fun reopen() {
        val id = containerId ?: return
        closeStream()
        clear()
        endedNormally = false
        val events = HandOffQueue<LogEvent>(handOff, maxLines, ::consume)
        queue = events
        stream =
            session.openStream(
                "Logs $id",
                { cli ->
                    cli.logs(
                        id,
                        tail.value.lines,
                        { raw ->
                            val (time, text) = splitTimestamp(raw)
                            events.add(LogEvent.Line(time, cleanLogText(text)))
                        },
                        { events.add(LogEvent.Ended(it)) },
                    )
                },
                { failure -> if (queue === events) note(failure.message ?: failure.toString()) },
            )
    }

    private fun closeStream() {
        queue?.cancel()
        queue = null
        stream?.close()
        stream = null
    }

    private fun consume(events: List<LogEvent>) {
        val added = mutableListOf<LogLine>()
        val query = filter.text
        // Only the last lines of a batch can stay.
        for (event in events.takeLast(maxLines)) {
            when (event) {
                is LogEvent.Line -> {
                    val line = buffer.append(event.time, event.text)
                    if (matchesFilter(line.text, query)) added += line
                }

                is LogEvent.Ended -> {
                    val failure = event.failure
                    if (failure == null) {
                        endedNormally = true
                        sawNotRunning = !isRunning()
                        endedEntry = currentContainer()
                        added += buffer.append(null, "— container stopped —", note = true)
                    } else {
                        added += buffer.append(null, failure.message ?: failure.toString(), note = true)
                    }
                }
            }
        }
        visible.addAll(added)
        trimVisible()
        if (follow.isSelected) scrollToEnd()
    }

    private fun note(text: String) {
        val line = buffer.append(null, text, note = true)
        visible += line
        trimVisible()
        if (follow.isSelected) scrollToEnd()
    }

    private fun trimVisible() {
        val first = buffer.firstSeq
        val gone = visible.takeWhile { it.seq < first }.size
        if (gone > 0) visible.remove(0, gone)
    }

    private fun clear() {
        buffer.clear()
        visible.clear()
    }

    private fun refilter() {
        visible.setAll(buffer.filtered(filter.text))
        if (follow.isSelected) scrollToEnd()
    }

    private fun currentContainer(): Container? = session.containers.firstOrNull { it.id == containerId }

    private fun isRunning(): Boolean = currentContainer()?.state == ContainerState.RUNNING

    /**
     * A container that runs again after its stream ended: seen not running meanwhile, or (a restart faster than the
     * list reloads) its entry changed, e.g. "Up 3 hours" to "Up Less than a second".
     */
    private fun containersChanged() {
        if (containerId == null || !endedNormally) return
        val container = currentContainer()
        if (container?.state != ContainerState.RUNNING) {
            sawNotRunning = true
        } else if (sawNotRunning || container != endedEntry) {
            reopen()
        }
    }

    private fun scrollToEnd() {
        if (visible.isNotEmpty()) list.scrollTo(visible.size - 1)
    }

    private fun onScrollBar(target: Any?): Boolean =
        generateSequence(target as? Node) { it.parent }.any { it is ScrollBar }

    /**
     * The user scrolled. Away from the end turns Follow off at once, before a line waiting to be handed over scrolls
     * to the end again; whether the scroll ended at the end, which turns it on, is read after the layout.
     */
    private fun userScrolled(away: Boolean) {
        if (away && scrollBar() != null) follow.isSelected = false
        Platform.runLater { followIfAtEnd() }
    }

    private fun scrollBar(): ScrollBar? =
        list
            .lookupAll(".scroll-bar")
            .filterIsInstance<ScrollBar>()
            .firstOrNull { it.orientation == Orientation.VERTICAL && it.isVisible }

    private fun followIfAtEnd() {
        list.layout() // the bar's value is final only after the layout of what was added
        val bar = scrollBar() ?: return
        if (followsAfterScroll(bar.value, bar.max)) follow.isSelected = true
    }

    private fun displayText(line: LogLine): String {
        val time = line.time
        return if (timestamps.isSelected && time != null) "${formatLogTime(time)}  ${line.text}" else line.text
    }

    private fun copySelection() {
        val text = list.selectionModel.selectedItems.joinToString("\n") { displayText(it) }
        if (text.isNotEmpty()) Clipboard.getSystemClipboard().setContent(ClipboardContent().apply { putString(text) })
    }

    private inner class LogCell : ListCell<LogLine>() {
        init {
            font = MONOSPACED
        }

        override fun updateItem(
            item: LogLine?,
            empty: Boolean,
        ) {
            super.updateItem(item, empty)
            text = if (empty || item == null) null else displayText(item)
            isWrapText = wrap.isSelected
            // Wrapping needs the width of the list; without it a long line gets a horizontal scroll bar.
            prefWidth = if (wrap.isSelected) 0.0 else Region.USE_COMPUTED_SIZE
            opacity = if (item?.note == true) 0.6 else 1.0
        }
    }

    private companion object {
        val MONOSPACED: Font = Font.font("Monospaced", 12.0)
        val KEYS_AWAY_FROM_END = setOf(KeyCode.UP, KeyCode.PAGE_UP, KeyCode.HOME)
        val SCROLL_KEYS = KEYS_AWAY_FROM_END + setOf(KeyCode.DOWN, KeyCode.PAGE_DOWN, KeyCode.END)
    }
}

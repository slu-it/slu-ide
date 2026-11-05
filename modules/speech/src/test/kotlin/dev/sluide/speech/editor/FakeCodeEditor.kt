package dev.sluide.speech.editor

import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.StyledRange
import dev.sluide.fileviewers.ViewActivity
import dev.sluide.speech.ui.ReadAloudUi
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.event.Event
import javafx.event.EventHandler
import javafx.event.EventType
import javafx.scene.Group
import javafx.scene.Node
import javafx.scene.control.IndexRange
import javafx.scene.control.MenuItem
import javafx.util.Subscription
import java.nio.file.Path

/** [EditorControls] that only note what they were given. */
internal class FakeControls : EditorControls {
    override val toolbar: Node = Group()
    override val notice: Node = Group()
    val shown = mutableListOf<ReadAloudUi>()
    var disposed = false

    override fun show(ui: ReadAloudUi) {
        shown += ui
    }

    override fun dispose() {
        disposed = true
    }
}

/** An [ExtensibleCodeEditor] that records what is added to it; [open] lists what is still added. */
internal class FakeCodeEditor(
    path: String,
    var lines: List<String>,
    override val plainText: Boolean = false,
) : ExtensibleCodeEditor {
    lateinit var items: (EditorMenuTarget) -> List<MenuItem>

    val fileProperty = SimpleObjectProperty(Path.of(path))
    var caret = 0
    var selected = IndexRange(0, 0)
    val open = mutableSetOf<String>()
    var locks = 0
    val overlays = mutableMapOf<String, List<StyledRange>>()
    val activities = mutableListOf<ObservableValue<ViewActivity>>()
    val banners = mutableListOf<Node>()
    private val filters = mutableListOf<Pair<EventType<*>, EventHandler<in Event>>>()
    private val textListeners = mutableListOf<() -> Unit>()

    override val file: ObservableValue<Path> = fileProperty
    override val lineCount get() = lines.size
    override val shownLines: IntRange = IntRange.EMPTY
    override val caretOffset get() = caret
    override val selection get() = selected

    override fun lineText(line: Int) = lines[line]

    override fun onShownLinesChanged(action: () -> Unit) = Subscription {}

    override fun addContextMenuItems(items: (EditorMenuTarget) -> List<MenuItem>) {
        this.items = items
    }

    override fun addGutterColumn(
        width: Double,
        column: (paragraph: Int) -> Node?,
    ) = Unit

    override fun refreshGutter() = Unit

    private fun added(name: String): AutoCloseable {
        open += name
        return AutoCloseable { open -= name }
    }

    override fun addToolbarItems(nodes: List<Node>) = added("toolbar")

    override fun addBanner(node: Node): AutoCloseable {
        banners += node
        return added("banner").let { handle -> AutoCloseable { handle.close() } }
    }

    override fun addStylesheet(url: String) = added("stylesheet")

    override fun lock(): AutoCloseable {
        locks++
        var closed = false
        return AutoCloseable {
            if (!closed) locks--
            closed = true
        }
    }

    override fun overlay(
        layer: String,
        ranges: List<StyledRange>,
    ) {
        overlays[layer] = ranges
    }

    override fun <T : Event> addEventFilter(
        type: EventType<T>,
        filter: EventHandler<in T>,
    ): Subscription {
        @Suppress("UNCHECKED_CAST")
        val entry = type to filter as EventHandler<in Event>
        filters += entry
        open += "filter ${type.name}"
        return Subscription {
            filters -= entry
            open -= "filter ${type.name}"
        }
    }

    override fun onTextChanged(action: () -> Unit): Subscription {
        textListeners += action
        open += "text"
        return Subscription {
            textListeners -= action
            open -= "text"
        }
    }

    override fun addActivity(activity: ObservableValue<ViewActivity>): Subscription {
        activities += activity
        open += "activity"
        return Subscription {
            activities -= activity
            open -= "activity"
        }
    }

    /** The editor's text changed (an edit or a reload). */
    fun textChanged() = textListeners.toList().forEach { it() }

    /** Sends [event] to the filters added for its type. */
    fun fire(event: Event) = filters.filter { it.first == event.eventType }.forEach { it.second.handle(event) }
}

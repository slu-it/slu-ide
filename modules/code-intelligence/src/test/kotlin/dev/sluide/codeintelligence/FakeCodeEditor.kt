package dev.sluide.codeintelligence

import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.fileviewers.TextChange
import javafx.beans.property.SimpleObjectProperty
import javafx.beans.value.ObservableValue
import javafx.event.Event
import javafx.event.EventHandler
import javafx.event.EventType
import javafx.scene.Node
import javafx.scene.control.MenuItem
import javafx.scene.input.KeyCombination
import javafx.util.Subscription
import java.net.URI
import java.nio.file.Path

/**
 * An [ExtensibleCodeEditor] without a view (copied from speech's, not shared): its text changes through [edit] and
 * [batch], which tell the change listeners as the real editor does; it records banners, key bindings and filters.
 */
internal class FakeCodeEditor(
    file: Path,
    initialText: String,
    override val host: FileViewHost = NoServicesHost,
) : ExtensibleCodeEditor {
    val fileProperty = SimpleObjectProperty(file)
    var currentText = initialText
    val banners = mutableListOf<Node>()
    val keyBindings = mutableListOf<KeyCombination>()
    private val menuProviders = mutableListOf<(EditorMenuTarget) -> List<MenuItem>>()
    private val changeListeners = mutableListOf<(TextChange) -> Unit>()
    private val savedListeners = mutableListOf<() -> Unit>()
    private val filters = mutableListOf<Pair<EventType<*>, EventHandler<in Event>>>()

    override val file: ObservableValue<Path> = fileProperty
    override val text: String get() = currentText
    override val lineCount get() = currentText.split('\n').size
    override val shownLines: IntRange = IntRange.EMPTY

    /** The caret's offset; [moveCaret] sets it. */
    var caret = 0
    override val caretOffset: Int get() = caret

    override fun moveCaret(offset: Int) {
        caret = offset.coerceIn(0, currentText.length)
    }

    override fun lineText(line: Int) = currentText.split('\n')[line]

    override fun onShownLinesChanged(action: () -> Unit) = Subscription {}

    override fun addContextMenuItems(items: (EditorMenuTarget) -> List<MenuItem>) {
        menuProviders += items
    }

    override fun addGutterColumn(
        width: Double,
        column: (paragraph: Int) -> Node?,
    ) = Unit

    override fun refreshGutter() = Unit

    override fun addBanner(node: Node): AutoCloseable {
        banners += node
        return AutoCloseable { banners -= node }
    }

    override fun onTextChange(listener: (TextChange) -> Unit): AutoCloseable {
        changeListeners += listener
        return AutoCloseable { changeListeners -= listener }
    }

    override fun onSaved(listener: () -> Unit): AutoCloseable {
        savedListeners += listener
        return AutoCloseable { savedListeners -= listener }
    }

    override fun addKeyBinding(
        keys: KeyCombination,
        action: () -> Boolean,
    ): AutoCloseable {
        keyBindings += keys
        return AutoCloseable { keyBindings -= keys }
    }

    override fun <T : Event> addEventFilter(
        type: EventType<T>,
        filter: EventHandler<in T>,
    ): Subscription {
        @Suppress("UNCHECKED_CAST")
        val entry = type to filter as EventHandler<in Event>
        filters += entry
        return Subscription { filters -= entry }
    }

    /** The items the editor's context menu would show at [target]. */
    fun menuItems(target: EditorMenuTarget = EditorMenuTarget(0, 0)) = menuProviders.flatMap { it(target) }

    /** Replaces [start, end) by [inserted] and tells the listeners. */
    fun edit(
        start: Int,
        end: Int,
        inserted: String,
    ) = batch(listOf(Triple(start, end, inserted)))

    /**
     * Applies [edits] (each in the text the one before left) and only then tells each change, as the real editor tells
     * a multi-change: inside the listener the text already shows the whole batch.
     */
    fun batch(edits: List<Triple<Int, Int, String>>) {
        val changes =
            edits.map { (start, end, inserted) ->
                val removed = currentText.substring(start, end)
                currentText = currentText.substring(0, start) + inserted + currentText.substring(end)
                TextChange(start, removed, inserted)
            }
        changes.forEach { change -> changeListeners.toList().forEach { it(change) } }
    }

    /** The file was saved. */
    fun save() = savedListeners.toList().forEach { it() }

    /** How many listeners and filters are still subscribed, to see that closing removed them. */
    val subscribed: Int get() = changeListeners.size + savedListeners.size + filters.size + keyBindings.size
}

/** A host without window services: opens nothing. */
internal object NoServicesHost : FileViewHost {
    override fun openFile(file: Path) = false

    override fun openWebPage(uri: URI) {}
}

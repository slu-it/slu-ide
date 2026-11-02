package dev.sluide.databases.ui.cards

import dev.sluide.databases.connect.DatabaseKind
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.ConnectionStatus
import dev.sluide.databases.ui.DatabasesTabModel
import javafx.collections.ListChangeListener
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.ScrollPane
import javafx.scene.control.Tooltip
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignP

/**
 * The left pane of the Databases tab (ADR 0121): a "+" menu to add a PostgreSQL or MariaDB connection above a scrolling
 * column of [ConnectionCard]s in the model's order. A click selects a card ([DatabasesTabModel.selected]), a double
 * click connects a disconnected one, dragging reorders ([CardDrag]). The column takes the focus: Up and Down move the
 * selection, Enter connects the selected disconnected card, Delete asks to delete it. Stops following the model when it
 * leaves its scene.
 */
internal class ConnectionCardList(
    private val model: DatabasesTabModel,
) {
    private val box =
        VBox(4.0).apply {
            styleClass += "db-card-list"
            padding = Insets(4.0)
            isFocusTraversable = true
        }
    private val scroll =
        ScrollPane(box).apply {
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            styleClass += "db-card-scroll"
            VBox.setVgrow(this, Priority.ALWAYS)
        }
    private val hint =
        Label("No connections yet. Add one with +").apply {
            styleClass += "db-card-hint"
            isWrapText = true
            isMouseTransparent = true
            StackPane.setAlignment(this, Pos.TOP_LEFT)
        }
    private val line = Region()
    private val drag = CardDrag(scroll, box, line) { from, to -> model.move(from, to) }
    private val cards = HashMap<ConnectionEntry, ConnectionCard>()

    private val listListener = ListChangeListener<ConnectionEntry> { sync() }
    private val selection = model.selected.subscribe { entry -> highlight(entry) }

    val node: Node

    init {
        val add =
            MenuButton(
                null,
                FontIcon(MaterialDesignP.PLUS),
                *DatabaseKind.entries
                    .map { kind ->
                        MenuItem(kind.label, DbIcons.logo(kind)).apply { setOnAction { model.add(kind) } }
                    }.toTypedArray(),
            ).apply {
                styleClass += "db-card-add"
                tooltip = Tooltip("Add Connection")
                isFocusTraversable = false
            }
        val body = StackPane(scroll, line, hint)
        VBox.setVgrow(body, Priority.ALWAYS)
        node =
            VBox(4.0, add, body).apply {
                styleClass += "db-cards"
                minWidth = 0.0
                padding = Insets(4.0)
                stylesheets += checkNotNull(ConnectionCardList::class.java.getResource("cards.css")).toExternalForm()
            }
        box.addEventHandler(KeyEvent.KEY_PRESSED, ::onKey)
        model.connections.addListener(listListener)
        // The model outlives the tab: a closed tab's list stops following it.
        var shown = false
        node.sceneProperty().addListener { _, _, scene ->
            if (scene != null) {
                shown = true
            } else if (shown) {
                dispose()
            }
        }
        sync()
    }

    private fun dispose() {
        selection.unsubscribe()
        model.connections.removeListener(listListener)
        drag.cancel()
        cards.values.forEach(ConnectionCard::dispose)
        cards.clear()
    }

    private fun sync() {
        val entries = model.connections.toList()
        cards.keys.filter { it !in entries }.forEach { cards.remove(it)?.dispose() }
        box.children.setAll(entries.map { cards.getOrPut(it) { newCard(it) } })
        hint.isVisible = entries.isEmpty()
        highlight(model.selected.get())
    }

    private fun newCard(entry: ConnectionEntry): ConnectionCard =
        ConnectionCard(entry, model).also { card ->
            drag.attach(card)
            card.addEventHandler(MouseEvent.MOUSE_PRESSED) {
                if (it.button == MouseButton.PRIMARY || it.button == MouseButton.SECONDARY) {
                    model.selected.set(entry)
                    box.requestFocus()
                }
            }
            card.addEventHandler(MouseEvent.MOUSE_CLICKED) {
                if (it.button == MouseButton.PRIMARY && it.clickCount == 2 && canConnect(entry)) model.connect(entry)
            }
        }

    private fun highlight(selected: ConnectionEntry?) {
        cards.forEach { (entry, card) -> card.setSelected(entry === selected) }
    }

    private fun canConnect(entry: ConnectionEntry) =
        entry.status.get().let { it == ConnectionStatus.DISCONNECTED || it == ConnectionStatus.ERROR }

    private fun onKey(event: KeyEvent) {
        val entries = model.connections
        val selected = model.selected.get()
        when (event.code) {
            KeyCode.UP, KeyCode.DOWN -> {
                if (entries.isEmpty()) return
                val index = entries.indexOf(selected)
                val next =
                    when {
                        index < 0 -> if (event.code == KeyCode.UP) entries.lastIndex else 0
                        event.code == KeyCode.UP -> (index - 1).coerceAtLeast(0)
                        else -> (index + 1).coerceAtMost(entries.lastIndex)
                    }
                model.selected.set(entries[next])
                cards[entries[next]]?.let(::reveal)
            }

            KeyCode.ENTER -> {
                if (selected != null && canConnect(selected)) model.connect(selected)
            }

            KeyCode.DELETE -> {
                if (selected != null) model.delete(selected)
            }

            else -> {
                return
            }
        }
        event.consume()
    }

    /** Scrolls so that [card] is inside the viewport. */
    private fun reveal(card: Node) {
        val range = box.height - scroll.viewportBounds.height
        if (range <= 0) return
        val top = card.boundsInParent.minY
        val bottom = card.boundsInParent.maxY
        val viewTop = scroll.vvalue * range
        val viewBottom = viewTop + scroll.viewportBounds.height
        when {
            top < viewTop -> scroll.vvalue = (top / range).coerceIn(0.0, 1.0)
            bottom > viewBottom -> scroll.vvalue = ((bottom - scroll.viewportBounds.height) / range).coerceIn(0.0, 1.0)
        }
    }
}

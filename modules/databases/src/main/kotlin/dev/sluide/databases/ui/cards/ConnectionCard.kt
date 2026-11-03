package dev.sluide.databases.ui.cards

import dev.sluide.databases.connect.JdbcUrls
import dev.sluide.databases.ui.ConnectionEntry
import dev.sluide.databases.ui.ConnectionStatus
import dev.sluide.databases.ui.DatabasesTabModel
import dev.sluide.databases.ui.color
import javafx.beans.InvalidationListener
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ContextMenu
import javafx.scene.control.Label
import javafx.scene.control.MenuItem
import javafx.scene.control.OverrunStyle
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.SeparatorMenuItem
import javafx.scene.control.Tooltip
import javafx.scene.layout.Background
import javafx.scene.layout.BackgroundFill
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.paint.Color
import javafx.scene.shape.Circle
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignK
import org.kordamp.ikonli.materialdesign2.MaterialDesignP

/** The colour of a card's status dot. */
internal enum class Dot(
    val styleClass: String,
) {
    DISCONNECTED("db-dot-disconnected"),
    CONNECTED("db-dot-connected"),
    PENDING("db-dot-pending"),
    ERROR("db-dot-error"),
}

/** What a card shows for a status: a [dot] (none while [connecting], which shows a progress indicator) and its buttons. */
internal data class CardState(
    val dot: Dot?,
    val connecting: Boolean,
    val canConnect: Boolean,
    val canDisconnect: Boolean,
)

/** The card state for [status]; [pending] (an open transaction) turns a connected dot orange. */
internal fun cardState(
    status: ConnectionStatus,
    pending: Boolean,
): CardState =
    when (status) {
        ConnectionStatus.DISCONNECTED -> CardState(Dot.DISCONNECTED, false, true, false)
        ConnectionStatus.CONNECTING -> CardState(null, true, false, false)
        ConnectionStatus.CONNECTED -> CardState(if (pending) Dot.PENDING else Dot.CONNECTED, false, false, true)
        ConnectionStatus.ERROR -> CardState(Dot.ERROR, false, true, false)
    }

private const val STRIPE_WIDTH = 4.0
private const val DOT_RADIUS = 4.0

/**
 * The card of [entry]: a colour stripe, then the logo, status dot and name, the connection's address with Connect,
 * Disconnect and Edit buttons, and the last error. Follows the entry's config, status, error and open transaction until
 * [dispose]; selection and the keys are the list's ([ConnectionCardList]).
 */
internal class ConnectionCard(
    val entry: ConnectionEntry,
    private val model: DatabasesTabModel,
) : HBox() {
    private val stripe =
        Region().apply {
            setMinWidth(STRIPE_WIDTH)
            setPrefWidth(STRIPE_WIDTH)
            setMaxWidth(STRIPE_WIDTH)
        }
    private val logoSlot = StackPane()
    private val dotSlot =
        StackPane().apply {
            setMinSize(12.0, 12.0)
            setPrefSize(12.0, 12.0)
            setMaxSize(12.0, 12.0)
        }
    private val keyIcon =
        FontIcon(MaterialDesignK.KEY_VARIANT).apply {
            styleClass += "db-card-key"
            Tooltip.install(this, Tooltip("Password saved"))
        }
    private val name = Label().apply { styleClass += "db-card-name" }
    private val address =
        Label().apply {
            styleClass += "db-card-address"
            textOverrun = OverrunStyle.ELLIPSIS
        }
    private val errorLine =
        Label().apply {
            styleClass += "db-card-error"
            textOverrun = OverrunStyle.ELLIPSIS
        }
    private val errorTooltip = Tooltip()

    private val connectButton =
        iconButton(MaterialDesignP.POWER_PLUG, "Connect", "db-card-connect") { model.connect(entry) }
    private val disconnectButton =
        iconButton(MaterialDesignP.POWER_PLUG_OFF, "Disconnect", "db-card-disconnect") { model.disconnect(entry) }
    private val editButton = iconButton(MaterialDesignP.PENCIL, "Edit…", "db-card-edit") { model.edit(entry) }

    private val refresh = InvalidationListener { refresh() }

    /** The context menu, rebuilt with every state change (an empty menu would not show). */
    internal val contextMenu = ContextMenu()

    init {
        styleClass += "db-card"
        HBox.setHgrow(address, Priority.ALWAYS)
        address.maxWidth = Double.MAX_VALUE
        val titleRow = HBox(6.0, logoSlot, dotSlot, name, keyIcon).apply { alignment = Pos.CENTER_LEFT }
        val addressRow =
            HBox(4.0, address, connectButton, disconnectButton, editButton).apply {
                alignment =
                    Pos.CENTER_LEFT
            }
        val body = VBox(2.0, titleRow, addressRow, errorLine).apply { padding = Insets(6.0, 6.0, 6.0, 8.0) }
        HBox.setHgrow(body, Priority.ALWAYS)
        body.minWidth = 0.0
        children.addAll(stripe, body)
        listOf(entry.config, entry.status, entry.error, entry.pendingTransaction).forEach { it.addListener(refresh) }
        setOnContextMenuRequested {
            contextMenu.show(this, it.screenX, it.screenY)
            it.consume()
        }
        refresh()
    }

    /** Stops following the entry. */
    fun dispose() {
        listOf(entry.config, entry.status, entry.error, entry.pendingTransaction).forEach { it.removeListener(refresh) }
    }

    /** Shows or hides the selection highlight. */
    fun setSelected(selected: Boolean) {
        if (selected) {
            if ("selected" !in styleClass) styleClass += "selected"
        } else {
            styleClass -= "selected"
        }
    }

    private fun refresh() {
        val config = entry.config.get()
        val state = cardState(entry.status.get(), entry.pendingTransaction.get())
        name.text = config.name
        keyIcon.isVisible = config.savedPassword != null
        keyIcon.isManaged = config.savedPassword != null
        address.text = JdbcUrls.display(config)
        logoSlot.children.setAll(DbIcons.logo(config.kind))
        val paint = color(config.color)
        stripe.background = Background(BackgroundFill(paint ?: Color.TRANSPARENT, null, null))
        dotSlot.children.setAll(
            if (state.connecting) {
                ProgressIndicator().apply {
                    styleClass += "db-card-progress"
                    setMinSize(12.0, 12.0)
                    setPrefSize(12.0, 12.0)
                    setMaxSize(12.0, 12.0)
                }
            } else {
                Circle(DOT_RADIUS).apply { styleClass += checkNotNull(state.dot).styleClass }
            },
        )
        connectButton.isDisable = !state.canConnect
        disconnectButton.isDisable = !state.canDisconnect
        val error = entry.error.get()
        errorLine.text = error
        errorLine.isVisible = error.isNotEmpty()
        errorLine.isManaged = error.isNotEmpty()
        errorTooltip.text = error
        errorLine.tooltip = if (error.isEmpty()) null else errorTooltip
        buildMenu()
    }

    private fun buildMenu() {
        val state = cardState(entry.status.get(), entry.pendingTransaction.get())
        val items = mutableListOf<MenuItem>()
        if (state.canConnect) items += MenuItem("Connect").apply { setOnAction { model.connect(entry) } }
        if (state.canDisconnect) items += MenuItem("Disconnect").apply { setOnAction { model.disconnect(entry) } }
        items += MenuItem("Edit…").apply { setOnAction { model.edit(entry) } }
        items += MenuItem("Duplicate").apply { setOnAction { model.duplicate(entry) } }
        if (entry.config.get().savedPassword != null) {
            items += MenuItem("Forget Saved Password").apply { setOnAction { model.forgetSavedPassword(entry) } }
        }
        items += SeparatorMenuItem()
        items += MenuItem("Delete…").apply { setOnAction { model.delete(entry) } }
        contextMenu.items.setAll(items)
    }

    private fun iconButton(
        icon: MaterialDesignP,
        tip: String,
        cssClass: String,
        action: () -> Unit,
    ) = Button().apply {
        graphic = FontIcon(icon)
        tooltip = Tooltip(tip)
        styleClass += listOf("db-card-button", cssClass)
        isFocusTraversable = false
        setOnAction { action() }
    }
}

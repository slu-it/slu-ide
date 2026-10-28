package dev.sluide.gitlog.area

import dev.sluide.gitlog.push.PUSH_KEYS
import dev.sluide.gitlog.ui.contains
import javafx.beans.binding.Bindings
import javafx.beans.property.BooleanProperty
import javafx.beans.value.ObservableBooleanValue
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.Region
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignA
import org.kordamp.ikonli.materialdesign2.MaterialDesignC
import org.kordamp.ikonli.materialdesign2.MaterialDesignR
import org.kordamp.ikonli.materialdesign2.MaterialDesignS

/**
 * The header bar of the git log area (ADRs 0035, 0046): a chevron that follows [expanded], the tabs' [titles], the
 * [status] message, Cancel (only while the operation going on is [cancellable], ADR 0043) and, on the right, Push,
 * Pull, Fetch All Remotes and Refresh. Fetch and Pull are off while an operation is [running] or there are [noRemotes],
 * Push without a remote. Clicking the bar (but not its buttons) expands or collapses the area.
 */
internal class HeaderBar(
    titles: Node,
    status: Node,
    expanded: BooleanProperty,
    running: ObservableBooleanValue,
    cancellable: ObservableBooleanValue,
    noRemotes: ObservableBooleanValue,
    refresh: () -> Unit,
    fetch: () -> Unit,
    push: () -> Unit,
    pull: () -> Unit,
    cancel: () -> Unit,
) {
    private val chevron = FontIcon(MaterialDesignC.CHEVRON_RIGHT)

    val refreshButton =
        Button(null, FontIcon(MaterialDesignR.REFRESH)).apply {
            styleClass += "git-log-refresh"
            tooltip = Tooltip("Refresh")
            isFocusTraversable = false
            // Always whole: the tabs left of it give way first.
            minWidth = Region.USE_PREF_SIZE
            setOnAction { refresh() }
        }

    /** Fetches all remotes (ADR 0043); not while an operation runs or there is no remote. */
    val fetchButton =
        Button(null, FontIcon(MaterialDesignA.ARROW_DOWN)).apply {
            styleClass += "git-log-fetch"
            tooltip = Tooltip("Fetch All Remotes")
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            setOnAction { fetch() }
            disableProperty().bind(Bindings.or(running, noRemotes))
        }

    /** Opens the Push dialog (ADR 0049); not without a remote, but while an operation runs. */
    val pushButton =
        Button(null, FontIcon(MaterialDesignA.ARROW_UP)).apply {
            styleClass += "git-log-push"
            tooltip = Tooltip("Push… (${PUSH_KEYS.displayText})")
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            setOnAction { push() }
            disableProperty().bind(noRemotes)
        }

    /** Opens the Pull dialog (ADR 0106); not while an operation runs or without a remote. */
    val pullButton =
        Button(null, FontIcon(MaterialDesignS.SOURCE_PULL)).apply {
            styleClass += "git-log-pull"
            tooltip = Tooltip("Pull…")
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            setOnAction { pull() }
            disableProperty().bind(Bindings.or(running, noRemotes))
        }

    /** Cancels the fetch, commit or push going on; shown only while there is one. */
    val cancelButton =
        Button("Cancel").apply {
            styleClass += "git-log-cancel"
            isFocusTraversable = false
            minWidth = Region.USE_PREF_SIZE
            visibleProperty().bind(cancellable)
            managedProperty().bind(cancellable)
            setOnAction { cancel() }
        }

    val node: HBox =
        HBox(
            chevron,
            titles,
            status,
            cancelButton,
            Region().apply { HBox.setHgrow(this, Priority.ALWAYS) },
            pushButton,
            pullButton,
            fetchButton,
            refreshButton,
        ).apply {
            styleClass += "git-log-header"
            alignment = Pos.CENTER_LEFT
            setOnMouseClicked { event ->
                val onButton =
                    listOf(
                        refreshButton,
                        pushButton,
                        pullButton,
                        fetchButton,
                        cancelButton,
                    ).any { it.contains(event.target) }
                if (event.button == MouseButton.PRIMARY && !onButton) {
                    expanded.set(!expanded.get())
                }
            }
        }

    init {
        expanded.subscribe { isExpanded ->
            chevron.iconCode = if (isExpanded) MaterialDesignC.CHEVRON_DOWN else MaterialDesignC.CHEVRON_RIGHT
        }
    }
}

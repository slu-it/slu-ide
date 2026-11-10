package dev.sluide.encryptedfiles.ui

import dev.sluide.fileviewers.WrappingToolbar
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.PasswordField
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.scene.layout.VBox
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignL

/**
 * What a locked tab shows: a centred column with a large lock, "`<name>` is encrypted.", the password field, Unlock
 * (the default button, so Enter unlocks) and a message line for "Unlocking…" or why the last unlock failed. Without a
 * viewer for the inner type it shows only why, with neither field nor button.
 */
internal class LockedPane(
    onUnlock: () -> Unit,
) {
    private val title = Label().apply { styleClass += "encrypted-title" }

    val field =
        PasswordField().apply {
            promptText = "Password"
            styleClass += "encrypted-password"
            // Enter in the field unlocks once, and the default button does not see the key again.
            setOnAction { event ->
                event.consume()
                onUnlock()
            }
        }

    val unlockButton =
        Button("Unlock").apply {
            isDefaultButton = true
            setOnAction { onUnlock() }
        }

    val message = Label().apply { styleClass += "encrypted-message" }

    val node =
        VBox(
            FontIcon(MaterialDesignL.LOCK_OUTLINE).apply { styleClass += "encrypted-lock-icon" },
            title,
            field,
            unlockButton,
            message,
        ).apply {
            styleClass += "encrypted-locked"
            alignment = Pos.CENTER
            isFillWidth = false
        }

    /** Shows the pane for [name]: [busy] while unlocking, with [text] below (or none), the field only if [canUnlock]. */
    fun show(
        name: String,
        canUnlock: Boolean,
        busy: Boolean,
        text: String?,
    ) {
        title.text = encryptedTitle(name)
        for (control in listOf(field, unlockButton)) {
            control.isVisible = canUnlock
            control.isManaged = canUnlock
            control.isDisable = busy
        }
        message.text = text.orEmpty()
        message.isVisible = !text.isNullOrEmpty()
        message.isManaged = message.isVisible
    }
}

/**
 * The strip above an unlocked tab's view: the open lock, "Encrypted · unlocked", a message (a warning or why a save
 * failed), and Lock at the right.
 */
internal class UnlockedStrip(
    onLock: () -> Unit,
) {
    val message = Label().apply { styleClass += "encrypted-strip-message" }

    val lockButton =
        Button("Lock", FontIcon(MaterialDesignL.LOCK_OUTLINE)).apply {
            styleClass += "encrypted-strip-button"
            tooltip = Tooltip(LOCK_TOOLTIP)
            isFocusTraversable = false
            setOnAction { onLock() }
        }

    val node =
        WrappingToolbar().apply {
            styleClass += "encrypted-strip"
            leading +=
                HBox(
                    FontIcon(MaterialDesignL.LOCK_OPEN_VARIANT_OUTLINE).apply { styleClass += "encrypted-strip-icon" },
                    Label(UNLOCKED_TEXT),
                ).apply {
                    styleClass += "encrypted-strip-state"
                    alignment = Pos.CENTER_LEFT
                }
            leading += message
            // P3b: Change Password… goes here, before Lock.
            trailing += lockButton
        }

    /** Shows [text] beside the state, or nothing. */
    fun show(text: String?) {
        message.text = text.orEmpty()
        message.isVisible = !text.isNullOrEmpty()
        message.isManaged = message.isVisible
    }
}

package dev.sluide.editorarea

import javafx.beans.value.ObservableBooleanValue
import javafx.scene.Node
import javafx.scene.control.Tooltip
import javafx.scene.layout.HBox
import javafx.util.Subscription
import org.kordamp.ikonli.javafx.FontIcon
import org.kordamp.ikonli.materialdesign2.MaterialDesignL

internal const val TAB_LOCK_STYLE_CLASS = "tab-lock"

internal const val TAB_LOCK_TEXT = "Library code, read-only"

/**
 * Shows a lock icon in [header] right before [title] while [locked] is true (a view's
 * [dev.sluide.fileviewers.FileView.readOnlyMarked]), and removes it when it turns false. The returned subscription
 * ends the following and removes the icon.
 */
internal fun bindTabLock(
    header: HBox,
    title: Node,
    locked: ObservableBooleanValue,
    relayout: () -> Unit,
): Subscription {
    var icon: Node? = null

    fun update(isLocked: Boolean) {
        if (isLocked == (icon != null)) return
        if (isLocked) {
            val lock =
                FontIcon(MaterialDesignL.LOCK).apply {
                    styleClass += TAB_LOCK_STYLE_CLASS
                    Tooltip.install(this, Tooltip(TAB_LOCK_TEXT))
                }
            header.children.add(header.children.indexOf(title).coerceAtLeast(0), lock)
            icon = lock
        } else {
            header.children.remove(icon)
            icon = null
        }
        relayout()
    }
    val subscription = locked.subscribe { value -> update(value) }
    return Subscription {
        subscription.unsubscribe()
        update(false)
    }
}

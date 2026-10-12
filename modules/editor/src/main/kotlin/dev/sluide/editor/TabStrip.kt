package dev.sluide.editor

import javafx.css.PseudoClass
import javafx.event.EventTarget
import javafx.scene.Node
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.MenuButton
import javafx.scene.control.MenuItem
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.Region
import javafx.scene.shape.Rectangle

private val SELECTED: PseudoClass = PseudoClass.getPseudoClass("selected")

/**
 * The row of tab headers above the editor content. When the headers do not fit, arrow buttons scroll the row
 * one tab at a time and a dropdown lists the tabs that are not completely visible.
 */
internal class TabStrip(
    private val openFiles: OpenFiles,
) : Region() {
    private val viewport = Pane().apply { styleClass += "tab-viewport" }
    private val viewportClip = Rectangle()

    val scrollLeftButton = arrowButton("‹", "scroll-left") { scrollBy(-1) }
    val scrollRightButton = arrowButton("›", "scroll-right") { scrollBy(1) }
    val overflowMenu =
        MenuButton().apply {
            styleClass += "overflow-menu"
            isFocusTraversable = false
            setOnShowing { items.setAll(hiddenFiles().map(::menuItem)) }
        }

    private var headers: List<TabHeader> = emptyList()
    private var first = 0
    private var revealSelected = false
    private var overflow = TabOverflow(emptyList(), 0.0)

    init {
        styleClass += "tab-strip"
        viewport.clip = viewportClip
        children.addAll(viewport, scrollLeftButton, scrollRightButton, overflowMenu)
        openFiles.files.subscribe(::rebuildHeaders)
        openFiles.selected.subscribe { selected ->
            headers.forEach { it.pseudoClassStateChanged(SELECTED, it.file === selected) }
            revealSelected = true
            requestLayout()
        }
    }

    /** Tabs that are cut off or scrolled out of view, in tab order. */
    fun hiddenFiles(): List<OpenFile> = overflow.hidden(first).mapNotNull { headers.getOrNull(it)?.file }

    /** Tabs that are completely visible, in tab order. */
    fun visibleFiles(): List<OpenFile> = overflow.visible(first).mapNotNull { headers.getOrNull(it)?.file }

    private fun rebuildHeaders() {
        val old = headers.associateBy { it.file }
        headers = openFiles.files.map { old[it] ?: TabHeader(it) }
        headers.forEach { it.pseudoClassStateChanged(SELECTED, it.file === openFiles.selected.get()) }
        viewport.children.setAll(headers)
        revealSelected = true
        requestLayout()
    }

    private fun scrollBy(tabs: Int) {
        first = overflow.clamp(first + tabs)
        requestLayout()
    }

    private fun menuItem(file: OpenFile) =
        MenuItem(file.name).apply {
            setOnAction { openFiles.select(file) }
        }

    override fun computePrefHeight(width: Double): Double {
        val controls = listOf<Region>(scrollLeftButton, scrollRightButton, overflowMenu) + headers
        return insets.top + controls.maxOf { it.prefHeight(-1.0) } + insets.bottom
    }

    override fun computePrefWidth(height: Double): Double =
        insets.left + headers.sumOf { it.prefWidth(-1.0) } + insets.right

    override fun computeMinWidth(height: Double): Double = insets.left + insets.right

    override fun layoutChildren() {
        val top = insets.top
        val height = this.height - top - insets.bottom
        var left = insets.left
        var right = width - insets.right
        val widths = headers.map { snapSizeX(it.prefWidth(-1.0)) }

        val overflows = widths.sum() > right - left
        for (control in listOf(scrollLeftButton, scrollRightButton, overflowMenu)) {
            control.isVisible = overflows
        }
        if (overflows) {
            val leftWidth = snapSizeX(scrollLeftButton.prefWidth(-1.0))
            scrollLeftButton.resizeRelocate(left, top, leftWidth, height)
            left += leftWidth
            val menuWidth = snapSizeX(overflowMenu.prefWidth(-1.0))
            right -= menuWidth
            overflowMenu.resizeRelocate(right, top, menuWidth, height)
            val rightWidth = snapSizeX(scrollRightButton.prefWidth(-1.0))
            right -= rightWidth
            scrollRightButton.resizeRelocate(right, top, rightWidth, height)
        }

        val viewportWidth = (right - left).coerceAtLeast(0.0)
        overflow = TabOverflow(widths, viewportWidth)
        first = overflow.clamp(first)
        if (revealSelected) {
            revealSelected = false
            val selected = headers.indexOfFirst { it.file === openFiles.selected.get() }
            if (selected >= 0) first = overflow.reveal(selected, first)
        }
        scrollLeftButton.isDisable = first == 0
        scrollRightButton.isDisable = first >= overflow.maxFirst

        viewport.resizeRelocate(left, top, viewportWidth, height)
        viewportClip.width = viewportWidth
        viewportClip.height = height
        var x = -overflow.offset(first)
        headers.forEachIndexed { index, header ->
            header.resizeRelocate(x, 0.0, widths[index], height)
            x += widths[index]
        }
    }

    private fun arrowButton(
        text: String,
        styleClass: String,
        action: () -> Unit,
    ) = Button(text).apply {
        this.styleClass += styleClass
        isFocusTraversable = false
        setOnAction { action() }
    }

    private fun Node.contains(target: EventTarget): Boolean =
        generateSequence(target as? Node) { it.parent }.any { it === this }

    /** One tab: file name (full path as tooltip) and a close button. Clicking it selects the file. */
    private inner class TabHeader(
        val file: OpenFile,
    ) : HBox() {
        init {
            styleClass += "editor-tab"
            // Positioned and sized by TabStrip.layoutChildren; the viewport Pane must not resize it to its pref size.
            isManaged = false
            val close =
                Button("×").apply {
                    styleClass += "close-button"
                    isFocusTraversable = false
                    setOnAction { openFiles.close(file) }
                }
            children.addAll(Label(file.name), close)
            Tooltip.install(this, Tooltip(file.path.toString()))
            // Pressing the close button must not select the tab first. Consuming the press on the button instead
            // would keep the button from arming, so it would never fire.
            setOnMousePressed { event ->
                if (event.button == MouseButton.PRIMARY && !close.contains(event.target)) openFiles.select(file)
            }
            setOnMouseClicked { if (it.button == MouseButton.MIDDLE) openFiles.close(file) }
        }
    }
}

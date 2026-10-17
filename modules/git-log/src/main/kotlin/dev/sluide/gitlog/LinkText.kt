package dev.sluide.gitlog

import javafx.css.PseudoClass
import javafx.event.Event
import javafx.scene.control.ContextMenu
import javafx.scene.control.MenuItem
import javafx.scene.control.Tooltip
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import org.fxmisc.richtext.StyleClassedTextArea
import org.fxmisc.richtext.util.UndoUtils
import kotlin.math.ceil

/** A link in a [LinkText]: the characters from [start] to [end] (exclusive), with a [tooltip]; [open] follows it. */
internal class TextLink(
    val start: Int,
    val end: Int,
    val tooltip: String,
    val open: () -> Unit,
)

/**
 * Read-only text with links (ADR 0045), styled flat like [SelectableText] (`git-log.css`), wrapped and always as high
 * as its text, so that only the details pane scrolls: a RichTextFX area, as a text area cannot colour or underline a
 * part of its text. The text can be selected and copied with the mouse, Shortcut+C and its context menu; it takes the
 * focus only when clicked. Links are in the accent colour and underlined, with a hand cursor and a tooltip; a single
 * click with the primary button that selects nothing follows one.
 */
internal class LinkText(
    styleClass: String,
) : StyleClassedTextArea(false) {
    private var links: List<TextLink> = emptyList()

    /** The link the primary button was pressed on, followed if released there without selecting. */
    private var pressed: TextLink? = null

    /** The link the mouse is over. */
    private var hovered: TextLink? = null

    private val linkTooltip = Tooltip()

    init {
        this.styleClass += "git-details-text"
        this.styleClass += "git-details-rich"
        this.styleClass += styleClass
        // Read-only: no history of the texts shown.
        undoManager = UndoUtils.noOpUndoManager()
        isEditable = false
        isWrapText = true
        isFocusTraversable = false
        isAutoHeight = true
        minWidth = 0.0
        // The auto height is measured at the current width: a new one (the first, once shown) needs another layout.
        widthProperty().subscribe { _, _ -> requestLayout() }
        contextMenu =
            ContextMenu(
                MenuItem("Copy").apply { setOnAction { copy() } },
                MenuItem("Select All").apply { setOnAction { selectAll() } },
            )
        addEventFilter(MouseEvent.MOUSE_MOVED) { event -> hover(linkAt(event)) }
        addEventFilter(MouseEvent.MOUSE_EXITED) { hover(null) }
        addEventFilter(MouseEvent.MOUSE_PRESSED) { event ->
            pressed = if (event.button == MouseButton.PRIMARY && event.clickCount == 1) linkAt(event) else null
        }
        addEventFilter(MouseEvent.MOUSE_CLICKED) { event ->
            val link = pressed
            pressed = null
            if (link != null &&
                event.button == MouseButton.PRIMARY &&
                event.clickCount == 1 &&
                selectedText.isEmpty() &&
                linkAt(event) === link
            ) {
                link.open()
            }
        }
        // The area would scroll itself (by nothing, as high as its text): the details pane scrolls instead.
        addEventFilter(ScrollEvent.ANY) { event ->
            val parent = parent ?: return@addEventFilter
            event.consume()
            Event.fireEvent(parent, event.copyFor(parent, parent))
        }
    }

    /** Shows [text] with [links], none selected. */
    fun show(
        text: String,
        links: List<TextLink> = emptyList(),
    ) {
        if (text != this.text) replaceText(text) else clearStyle(0, length)
        this.links = links
        for (link in links) setStyleClass(link.start, link.end, LINK)
        // Also for the same text again: what was selected belongs to what was shown before.
        moveTo(0)
        hover(null)
        pressed = null
    }

    // As high as its paragraphs (auto height), rounded up and with a pixel to spare, so that no line is cut off.
    override fun computePrefHeight(width: Double) = ceil(super.computePrefHeight(width)) + 1

    override fun computeMinHeight(width: Double) = computePrefHeight(width)

    override fun computeMaxHeight(width: Double) = computePrefHeight(width)

    /** The link at the character under the mouse of [event], if any. */
    private fun linkAt(event: MouseEvent): TextLink? {
        if (links.isEmpty()) return null
        val index = hit(event.x, event.y).characterIndex
        if (!index.isPresent) return null
        return links.firstOrNull { index.asInt >= it.start && index.asInt < it.end }
    }

    private fun hover(link: TextLink?) {
        if (link === hovered) return
        hovered = link
        pseudoClassStateChanged(OVER_LINK, link != null)
        if (link == null) {
            Tooltip.uninstall(this, linkTooltip)
        } else {
            linkTooltip.text = link.tooltip
            Tooltip.install(this, linkTooltip)
        }
    }

    private companion object {
        const val LINK = "git-details-link"

        /** The mouse is over a link: a hand cursor (`git-log.css`), which the area's own style sheet would override. */
        val OVER_LINK: PseudoClass = PseudoClass.getPseudoClass("over-link")
    }
}

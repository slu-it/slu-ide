package dev.sluide.codeintelligence.ui

import dev.sluide.codeintelligence.binding.BindingFeature
import dev.sluide.codeintelligence.binding.DocumentBinding
import dev.sluide.fileviewers.ExtensibleCodeEditor
import javafx.animation.PauseTransition
import javafx.scene.Node
import javafx.scene.control.ScrollPane
import javafx.scene.control.Separator
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyCodeCombination
import javafx.scene.input.MouseEvent
import javafx.scene.input.ScrollEvent
import javafx.scene.layout.VBox
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The hover popup of an editor (ADR 0145): the sections of the hover providers stacked, separated by lines, below the
 * character they are for (above when there is no room), scrolling when they are long. [hiddenByItself] is told when
 * it hides on its own (a click outside, the window moved, the mouse left it). FX thread.
 */
internal class HoverPopup(
    private val editor: ExtensibleCodeEditor,
    private val hiddenByItself: () -> Unit,
) : HoverView,
    AutoCloseable {
    private val window = EditorPopupWindow(editor) { if (!hiding) hiddenByItself() }
    private var hiding = false

    // Created on first use: the binding may live in a test without a toolkit.
    private val sections by lazy(LazyThreadSafetyMode.NONE) { VBox().apply { styleClass += "hover-sections" } }

    private val content by lazy(LazyThreadSafetyMode.NONE) {
        ScrollPane(sections).apply {
            styleClass += "hover-popup"
            isFitToWidth = true
            hbarPolicy = ScrollPane.ScrollBarPolicy.NEVER
            isFocusTraversable = false
            addEventHandler(MouseEvent.MOUSE_EXITED) { window.hide() }
        }
    }

    /** Whether it shows. */
    val isShowing: Boolean get() = window.isShowing

    /** The sections shown, for tests. */
    val shownSections: List<Node> get() = sections.children.filter { it !is Separator }

    override fun show(
        offset: Int,
        sections: List<Node>,
    ): Boolean {
        this.sections.children.setAll(
            sections.flatMapIndexed { index, section ->
                if (index ==
                    0
                ) {
                    listOf(section)
                } else {
                    listOf(Separator(), section)
                }
            },
        )
        val end = if (insideLine(offset)) offset + 1 else offset
        val shown = window.show(content, offset, end)
        if (shown) window.relayout()
        return shown
    }

    override fun hide() {
        hiding = true
        try {
            window.hide()
        } finally {
            hiding = false
        }
    }

    /** Whether the screen point keeps the popup: on its anchor or on the popup. */
    fun keeps(
        screenX: Double,
        screenY: Double,
    ): Boolean = keepsHover(screenX, screenY, window.anchor, window.screenBounds)

    override fun close() = window.close()

    /** Whether [offset] is a character of its line, not the line's end: then the popup is for that character. */
    private fun insideLine(offset: Int): Boolean {
        val position = editor.positionOf(offset)
        return position.line < editor.lineCount && position.column < editor.lineText(position.line).length
    }
}

/**
 * The hover popup of a binding (ADR 0145): asks [DocumentBinding.hovers] when the mouse rests on a character for
 * [restDelay], or at the caret on F1 (only while there are providers), and hides on another character, typing,
 * scrolling, a click and Escape.
 */
internal class HoverFeature(
    private val restDelay: Duration = 500.milliseconds,
    private val schedule: (Duration, () -> Unit) -> AutoCloseable = ::fxDelay,
) : BindingFeature {
    override fun attach(binding: DocumentBinding): AutoCloseable {
        val editor = binding.editor
        lateinit var model: HoverModel
        val popup = HoverPopup(editor) { model.hiddenByView() }
        model = HoverModel(binding.hovers::providers, popup, restDelay, schedule)
        val handles =
            listOf(
                popup,
                AutoCloseable { model.dismiss() },
                editor
                    .addEventFilter(MouseEvent.MOUSE_MOVED) { event ->
                        model.mouseMoved(editor.offsetAt(event), popup.keeps(event.screenX, event.screenY))
                    }.asCloseable(),
                editor.addEventFilter(MouseEvent.MOUSE_EXITED) { model.mouseExited() }.asCloseable(),
                editor.addEventFilter(MouseEvent.MOUSE_PRESSED) { model.dismiss() }.asCloseable(),
                editor.addEventFilter(ScrollEvent.SCROLL) { model.dismiss() }.asCloseable(),
                editor.onTextChange { model.dismiss() },
                editor.addKeyBinding(KeyCodeCombination(KeyCode.ESCAPE)) {
                    if (model.isShowing) {
                        model.dismiss()
                        true
                    } else {
                        false
                    }
                },
                editor.addKeyBinding(KeyCodeCombination(KeyCode.F1)) { model.showAt(editor.caretOffset) },
            )
        return AutoCloseable { handles.asReversed().forEach { it.close() } }
    }
}

private fun javafx.util.Subscription.asCloseable() = AutoCloseable { unsubscribe() }

/** Runs [action] on the FX thread after [delay], unless the handle is closed first. */
internal fun fxDelay(
    delay: Duration,
    action: () -> Unit,
): AutoCloseable {
    val pause = PauseTransition(javafx.util.Duration.millis(delay.inWholeMilliseconds.toDouble()))
    pause.setOnFinished { action() }
    pause.play()
    return AutoCloseable { pause.stop() }
}

package dev.sluide.codeintelligence.ui

import javafx.scene.Node
import kotlin.time.Duration

private val log = System.getLogger(HoverModel::class.java.name)

/** What a [HoverModel] shows on: the popup. */
internal interface HoverView {
    /** Shows [sections] (stacked, in order) for the character at [offset], or replaces what shows; `false` if not. */
    fun show(
        offset: Int,
        sections: List<Node>,
    ): Boolean

    fun hide()
}

/**
 * One question to all hover providers for [offset]: their answers are shown as they arrive, each time as the sections of
 * every provider that answered so far, in the providers' order; nothing while none has anything. [close] tells the
 * providers to stop and ignores late answers. FX thread.
 */
internal class HoverQuery(
    providers: List<HoverProvider>,
    offset: Int,
    private val shown: (List<Node>) -> Unit,
) : AutoCloseable {
    private val answers = arrayOfNulls<List<Node>>(providers.size)
    private val handles = mutableListOf<AutoCloseable>()
    private var closed = false

    init {
        providers.forEachIndexed { index, provider ->
            try {
                handles += provider.hover(offset) { sections -> answered(index, sections) }
            } catch (e: RuntimeException) {
                log.log(System.Logger.Level.ERROR, "A hover provider failed", e)
            }
        }
    }

    private fun answered(
        index: Int,
        sections: List<Node>?,
    ) {
        if (closed || answers[index] != null) return
        answers[index] = sections.orEmpty()
        if (sections.isNullOrEmpty()) return
        shown(answers.filterNotNull().flatten())
    }

    override fun close() {
        if (closed) return
        closed = true
        handles.forEach { handle ->
            try {
                handle.close()
            } catch (e: Exception) {
                log.log(System.Logger.Level.ERROR, "A hover provider failed to stop", e)
            }
        }
    }
}

/**
 * When the hover popup asks its providers and when it goes (ADR 0145), without the popup: the mouse resting on a
 * character for [restDelay] asks all providers for it ([HoverQuery]); F1 asks at once for the caret ([showAt]). Moving
 * to another character (unless on the way to the popup), typing, scrolling, a click and Escape hide it ([dismiss]).
 * FX thread.
 *
 * @param schedule runs the action after the delay unless the handle is closed first.
 */
internal class HoverModel(
    private val providers: () -> List<HoverProvider>,
    private val view: HoverView,
    private val restDelay: Duration,
    private val schedule: (Duration, () -> Unit) -> AutoCloseable,
) {
    private var pendingOffset: Int? = null
    private var timer: AutoCloseable? = null
    private var query: HoverQuery? = null

    /** The offset the popup shows for, `null` while hidden. */
    var shownOffset: Int? = null
        private set

    /** The offset the last question was for, while it may still be answered or shows. */
    private var askedOffset: Int? = null

    /** Whether the popup shows. */
    val isShowing: Boolean get() = shownOffset != null

    /**
     * The mouse moved to the character at [offset] (`null`: no character, e.g. past a line's end). [keeps] says whether
     * the position keeps a shown popup anyway (on its anchor or the popup itself, see [keepsHover]).
     */
    fun mouseMoved(
        offset: Int?,
        keeps: Boolean,
    ) {
        if (askedOffset != null && (offset == askedOffset || keeps)) return
        if (askedOffset != null) dismiss()
        if (offset == pendingOffset) return
        cancelTimer()
        if (offset == null) return
        pendingOffset = offset
        timer =
            schedule(restDelay) {
                timer = null
                pendingOffset = null
                ask(offset)
            }
    }

    /** The mouse left the text: a rest that has not shown yet is off. */
    fun mouseExited() = cancelTimer()

    /** Asks the providers for [offset] at once (F1 at the caret); `false` if there are none. */
    fun showAt(offset: Int): Boolean {
        if (providers().isEmpty()) return false
        dismiss()
        ask(offset)
        return true
    }

    /** Hides the popup and drops the questions on their way. */
    fun dismiss() {
        cancelTimer()
        query?.close()
        query = null
        askedOffset = null
        if (shownOffset != null) {
            shownOffset = null
            view.hide()
        }
    }

    /** The popup hid by itself (a click outside, the window moved): forget it. */
    fun hiddenByView() {
        query?.close()
        query = null
        askedOffset = null
        shownOffset = null
    }

    private fun ask(offset: Int) {
        query?.close()
        askedOffset = offset
        query =
            HoverQuery(providers(), offset) { sections ->
                if (view.show(offset, sections)) shownOffset = offset
            }
    }

    private fun cancelTimer() {
        timer?.close()
        timer = null
        pendingOffset = null
    }
}

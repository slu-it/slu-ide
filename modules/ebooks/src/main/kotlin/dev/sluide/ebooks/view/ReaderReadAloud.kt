package dev.sluide.ebooks.view

import dev.sluide.ebooks.BookPage
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Button
import javafx.scene.control.ComboBoxBase
import javafx.scene.control.TextInputControl
import javafx.scene.input.KeyEvent
import javafx.scene.input.MouseEvent
import javafx.scene.layout.StackPane

/** Wires Read Aloud into an [EpubFileView]: toolbar, notice, highlight, follow button, keys, double-click. */
internal object ReaderReadAloud {
    /**
     * Attaches Read Aloud to [view]; the controls come from [factory] once the first page is loaded (the host is set
     * by then), none leaves the slot empty. Returns the surface.
     */
    fun attach(
        view: EpubFileView,
        factory: ControlsFactory = windowControls(view),
    ): BookReadingSurface {
        val backButton =
            Button("Back to reading").apply {
                isFocusTraversable = false
                styleClass += "ebook-back-to-reading"
                isVisible = false
                isManaged = false
            }
        val surface = BookReadingSurface(ViewAdapter(view, backButton))
        backButton.setOnAction { surface.backToReading() }
        StackPane.setAlignment(backButton, Pos.BOTTOM_RIGHT)
        StackPane.setMargin(backButton, Insets(0.0, 24.0, 12.0, 0.0))
        view.addPageOverlay(backButton)
        view.onUserScroll += { surface.userScrolled() }
        view.onUserNavigate += { surface.userNavigated() }
        view.onPageLoaded += { surface.pageLoaded() }
        keys(view, surface)
        doubleClicks(view, surface)
        view.onPageLoaded +=
            {
                if (surface.controls == null) {
                    factory(surface)?.let { controls ->
                        surface.controls = controls
                        view.readAloudSlot.children.setAll(controls.toolbar)
                        view.bannerSlot.children.setAll(controls.notice)
                        view.activity.bind(controls.activity)
                        view.onDispose += {
                            view.activity.unbind()
                            controls.dispose()
                        }
                    }
                }
            }
        return surface
    }

    /** Handles the key press only (not in text inputs): a handled press also swallows its typed and released events. */
    private fun keys(
        view: EpubFileView,
        surface: BookReadingSurface,
    ) {
        var swallow = false
        view.addKeyFilter { event ->
            // Keys typed into a text field or a combo box (the search, the voice list) are theirs.
            if (event.target is TextInputControl || event.target is ComboBoxBase<*>) return@addKeyFilter false
            when (event.eventType) {
                KeyEvent.KEY_PRESSED -> (surface.controls?.handleKey(event) ?: false).also { swallow = it }
                KeyEvent.KEY_TYPED -> swallow
                KeyEvent.KEY_RELEASED -> swallow.also { swallow = false }
                else -> false
            }
        }
    }

    private fun doubleClicks(
        view: EpubFileView,
        surface: BookReadingSurface,
    ) {
        view.webView.addEventFilter(MouseEvent.MOUSE_CLICKED) { event ->
            if (event.clickCount == 2 && surface.controls?.reading == true) {
                val local = view.marks?.offsetAt(event.x, event.y)
                if (surface.doubleClicked(local?.let(view::toWhole))) event.consume()
            }
        }
    }

    private class ViewAdapter(
        private val view: EpubFileView,
        private val backButton: Button,
    ) : ReaderView {
        override val page: BookPage? get() = view.loadedBook

        override fun startOffset() = view.startOffset()

        override fun markRange(
            name: String,
            start: Int,
            end: Int,
        ) = view.markRange(name, start, end)

        override fun clearMark(name: String) = view.clearMark(name)

        override fun showPosition(offset: Int) = view.showPosition(offset)

        override fun jumpTo(offset: Int) = view.jumpTo(offset)

        override fun toLocal(offset: Int) = view.toLocal(offset)

        override fun isVisible(
            localStart: Int,
            localEnd: Int,
        ) = view.marks?.isVisible(localStart, localEnd) ?: false

        override fun revealLocal(local: Int) {
            view.marks?.reveal(local)
        }

        override var startClicks: Boolean
            get() = view.startClicks
            set(value) {
                view.startClicks = value
            }

        override fun showBackButton(show: Boolean) {
            backButton.isVisible = show
            backButton.isManaged = show
        }
    }
}

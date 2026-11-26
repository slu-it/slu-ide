package dev.sluide.pdf.view

import dev.sluide.fileviewers.FileViewHost
import dev.sluide.pdf.TestPdfs
import dev.sluide.pdf.engine.PageRect
import dev.sluide.pdf.engine.PageText
import javafx.event.Event
import javafx.geometry.Bounds
import javafx.geometry.Point2D
import javafx.scene.Cursor
import javafx.scene.Node
import javafx.scene.input.Clipboard
import javafx.scene.input.ClipboardContent
import javafx.scene.input.KeyCode
import javafx.scene.input.MouseButton
import javafx.scene.input.MouseEvent
import org.junit.jupiter.api.io.TempDir
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CountDownLatch

/** What the selection's UI tests share: events fired at the pages, the clipboard, the cursor wait, reloads. */
internal abstract class PdfSelectionUiBase : PdfViewUiFixture() {
    @TempDir
    lateinit var dir: Path

    protected class FakeHost : FileViewHost {
        val webPages = mutableListOf<URI>()

        override fun openFile(file: Path) = false

        override fun openWebPage(uri: URI) {
            webPages += uri
        }
    }

    protected fun textOf(
        view: PdfFileView,
        page: Int = 0,
    ): PageText {
        var text: PageText? = null
        fx { view.texts.textOf(page) { text = it } }
        await { text != null }
        return checkNotNull(text)
    }

    protected fun boxOf(
        text: PageText,
        word: String,
    ): PageRect {
        val start = text.text.indexOf(word)
        val boxes = (start until start + word.length).mapNotNull { text.boxes[it] }
        val left = boxes.minOf { it.x }
        val top = boxes.minOf { it.y }
        return PageRect(left, top, boxes.maxOf { it.x + it.width } - left, boxes.maxOf { it.y + it.height } - top)
    }

    /** A view of the text page, its texts loaded, and the scene boxes of the words [words]. */
    protected fun textView(
        file: Path = TestPdfs.text,
        configure: (PdfFileView) -> Unit = {},
    ): PdfFileView {
        val view = open(file, configure = configure)
        awaitRendered(view, 0)
        textOf(view)
        return view
    }

    protected fun scene(
        view: PdfFileView,
        word: String,
    ): Bounds = sceneRect(view, 0, boxOf(textOf(view), word))

    protected fun mouse(
        type: javafx.event.EventType<MouseEvent>,
        at: Point2D,
        clicks: Int = 1,
        button: MouseButton = MouseButton.PRIMARY,
        popupTrigger: Boolean = false,
        control: Boolean = false,
    ) = MouseEvent(
        type,
        at.x,
        at.y,
        at.x,
        at.y,
        button,
        clicks,
        false,
        control,
        false,
        false,
        button == MouseButton.PRIMARY,
        false,
        button == MouseButton.SECONDARY,
        false,
        popupTrigger,
        true,
        null,
    )

    /** The node a press went to: the later drags and the release of that press go to it too, as with a real pointer. */
    private var pressTarget: Node? = null

    /**
     * Fires [event]. A mouse event goes to the node a real pointer would hit (the page slot under the point, or the
     * scroll pane's viewport beside the pages; a drag or release to the node of its press), so that it bubbles through
     * the scroll pane's skin like a real one. Anything else goes to the pages.
     */
    protected fun fire(
        view: PdfFileView,
        event: Event,
    ) = fx { Event.fireEvent(targetOf(view, event), event) }

    private fun targetOf(
        view: PdfFileView,
        event: Event,
    ): Node {
        if (event !is MouseEvent) return view.pages
        if (event.eventType != MouseEvent.MOUSE_PRESSED) {
            val target = pressTarget
            if (target != null) {
                if (event.eventType == MouseEvent.MOUSE_RELEASED) pressTarget = null
                return target
            }
        }
        val slot = view.pagePointAt(event.sceneX, event.sceneY)?.let { view.pageColumn.pageSlots[it.page] }
        val target = slot ?: view.pages.lookup(".viewport") ?: view.pages
        if (event.eventType == MouseEvent.MOUSE_PRESSED) pressTarget = target
        return target
    }

    protected fun click(
        view: PdfFileView,
        at: Point2D,
        clicks: Int = 1,
    ) {
        fire(view, mouse(MouseEvent.MOUSE_PRESSED, at, clicks))
        fire(view, mouse(MouseEvent.MOUSE_RELEASED, at, clicks))
    }

    protected fun drag(
        view: PdfFileView,
        from: Point2D,
        to: Point2D,
    ) {
        fire(view, mouse(MouseEvent.MOUSE_PRESSED, from))
        fire(view, mouse(MouseEvent.MOUSE_DRAGGED, to))
        fire(view, mouse(MouseEvent.MOUSE_RELEASED, to))
    }

    protected fun key(
        view: PdfFileView,
        code: KeyCode,
        shortcut: Boolean = false,
    ) = fire(view, keyPress(code, shortcut))

    protected fun Bounds.left() = Point2D(minX + 1, centerY)

    protected fun Bounds.right() = Point2D(maxX - 1, centerY)

    protected fun Bounds.centre() = Point2D(centerX, centerY)

    protected fun clearClipboard() =
        fx {
            Clipboard.getSystemClipboard().setContent(ClipboardContent().also { it.putString(UNTOUCHED) })
        }

    protected fun awaitClipboard(expected: String) = await { Clipboard.getSystemClipboard().string == expected }

    protected fun clipboard(): String? = fx { Clipboard.getSystemClipboard().string }

    protected fun selected(
        view: PdfFileView,
        page: Int = 0,
    ) = fx { view.overlays.on(page).any { it.first == OverlayLayer.SELECTION } }

    protected fun Collection<javafx.scene.paint.Color>.hasSelectionBlue() = any { it.blue - it.red > 0.1 }

    /** Moves the pointer to [at] until the pages show [cursor]: the links load in the background. */
    protected fun awaitCursor(
        view: PdfFileView,
        at: Point2D,
        cursor: Cursor?,
    ) {
        await {
            Event.fireEvent(view.pages, mouse(MouseEvent.MOUSE_MOVED, at))
            view.pageCursor == cursor
        }
    }

    /** Reloads [view] of [file], changed on disk, and waits for it; its texts and links are gone afterwards. */
    protected fun reload(
        view: PdfFileView,
        file: Path,
        reloads: () -> Int,
    ) {
        val before = reloads()
        Files.setLastModifiedTime(file, FileTime.fromMillis(Files.getLastModifiedTime(file).toMillis() + 10_000))
        fx { view.reload() }
        await { reloads() == before + 1 }
        awaitRendered(view, 0)
    }

    /**
     * The text page, reloaded (its texts are gone), with the text worker held while [act] runs on it: whatever [act]
     * asks of the texts arrives after it returned. [act] gets the scene boxes of "second" and "line".
     */
    protected fun heldView(act: (PdfFileView, Bounds, Bounds) -> Unit): PdfFileView {
        val file = dir.resolve("held.pdf")
        Files.copy(TestPdfs.text, file)
        var reloads = 0
        val view = textView(file) { it.onReload += { reloads++ } }
        val second = scene(view, "second")
        val line = scene(view, "line")
        reload(view, file) { reloads }
        val latch = CountDownLatch(1)
        fx { view.textWorker.submit(WorkPriority.VISIBLE, { latch.await() }, {}) }
        try {
            clearClipboard()
            act(view, second, line)
        } finally {
            latch.countDown()
        }
        return view
    }

    protected companion object {
        const val UNTOUCHED = "untouched"
    }
}

package dev.sluide.fileviewers

import javafx.application.ColorScheme
import javafx.concurrent.Worker
import javafx.geometry.Pos
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.web.WebView
import netscape.javascript.JSObject
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.extension
import kotlin.io.path.fileSize
import kotlin.io.path.isRegularFile
import kotlin.io.path.readBytes

/** Shows image files (png, jpg, gif, webp, bmp, ico) up to 50 MB in an [ImageFileView]. */
@Component
class ImageFileViewer : FileViewer {
    override fun canOpen(file: Path): Boolean {
        if (!isImageName(file)) return false
        return try {
            file.isRegularFile() && Files.isReadable(file) && file.fileSize() <= MAX_IMAGE_BYTES
        } catch (_: IOException) {
            false
        }
    }

    override fun open(file: Path): FileView = ImageFileView(file)

    internal companion object {
        const val MAX_IMAGE_BYTES = 50L * 1024 * 1024

        /** Whether the name of [file] is one of an image this viewer shows (SVG is shown as text); not read. */
        fun isImageName(file: Path): Boolean = file.extension.lowercase().let { it != "svg" && it in imageTypes }
    }
}

/**
 * An image on a checkered background ([ImagePage]) with the zoom controls ([zoom]) above and its pixel size and file
 * size below. It opens fitted to the view; a click switches between fitted and 100%, and the zoom is kept when the
 * image is shown again. Follows the window's colour scheme; read again when the file changes.
 */
internal class ImageFileView(
    file: Path,
) : FileView {
    private var file = file
    val webView = WebView().apply { isContextMenuEnabled = false }
    val status = Label().apply { styleClass += "image-status" }
    private val engine = webView.engine

    /** The image's zoom, in the toolbar above it and on the keys, wheel and pinch in the web view. */
    internal val zoom = ZoomControls(engine) { isLoaded }

    /** Receives the page's zoom changes; held here because the WebView only keeps a weak reference. */
    private val bridge = ZoomBridge()
    private val pane =
        BorderPane(webView).apply {
            top =
                HBox(zoom.node).apply {
                    styleClass += "preview-toolbar"
                    alignment = Pos.CENTER_LEFT
                }
            bottom = status
            styleClass += "image-view"
            stylesheets +=
                checkNotNull(
                    ImageFileView::class.java.getResource("/dev/sluide/fileviewers/preview-view.css"),
                ).toExternalForm()
        }
    private var bytes = file.readBytes()
    private var dark = false
    private var disposed = false
    private val pendingFocus = FocusWhenShown()

    init {
        zoom.install(webView)
        engine.loadWorker.stateProperty().subscribe { state -> if (state == Worker.State.SUCCEEDED) onLoaded() }
        // Nothing on the page navigates; something dropped onto it would. Show the image again.
        engine.locationProperty().subscribe { location -> if (!disposed && !isPageFromString(location)) render() }
        pane
            .sceneProperty()
            .flatMap { it.preferences.colorSchemeProperty() }
            .orElse(ColorScheme.LIGHT)
            .subscribe { scheme ->
                dark = scheme == ColorScheme.DARK
                if (isLoaded) engine.executeScript("sluide.setDark($dark)")
            }
        render()
    }

    override val node: Node get() = pane

    override fun reload() {
        if (disposed) return
        if (file.fileSize() > ImageFileViewer.MAX_IMAGE_BYTES) throw IOException("$file is too large to show")
        val newBytes = file.readBytes()
        if (newBytes.contentEquals(bytes)) return
        bytes = newBytes
        render()
    }

    /**
     * Follows the file to [to] without rendering again: the bytes are the same. Only to a name of the same image
     * type; for any other the editor replaces the view.
     */
    override fun moved(to: Path): Boolean {
        val extension = to.extension.lowercase()
        // Another image type is another page (the type goes into it), so the editor replaces the view.
        if (disposed || imageTypes[extension] != imageTypes[this.file.extension.lowercase()]) {
            return false
        }
        file = to
        return true
    }

    /** Focuses the image, so that the zoom keys work right away. */
    override fun focusText() = webView.requestFocus()

    /** Focuses the image once it is shown, so that the zoom keys work right away. */
    override fun focus() = pendingFocus.request(webView) { if (!disposed) webView.requestFocus() }

    /** Stops showing the image for good: the blank page left is no page of ours, and nothing renders again. */
    override fun dispose() {
        disposed = true
        pendingFocus.cancel()
        engine.load("")
    }

    /** Whether the view shows its image page, completely loaded. */
    internal val isLoaded
        get() = !disposed && engine.loadWorker.state == Worker.State.SUCCEEDED && isPageFromString(engine.location)

    private fun render() {
        engine.loadContent(ImagePage.render(bytes, imageTypes.getValue(file.extension.lowercase()), dark))
    }

    private fun onLoaded() {
        if (!isLoaded) return
        val size =
            engine.executeScript(
                "var i = document.querySelector('img'); i.naturalWidth + ' × ' + i.naturalHeight",
            )
        status.text = "$size px · ${humanSize(bytes.size.toLong())}"
        (engine.executeScript("window") as JSObject).setMember("sluideBridge", bridge)
        zoom.applyTo()
    }

    /** Called by the page's script (`sluideBridge.zoomed`) when the zoom changed. */
    inner class ZoomBridge {
        fun zoomed(
            scale: Double,
            fitted: Boolean,
        ) = zoom.zoomed(scale, fitted)
    }
}

/** [bytes] for people: `512 B`, `1.5 KB`, `3.2 MB`. */
internal fun humanSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(Locale.ROOT, bytes / 1024.0)
        else -> "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024.0))
    }

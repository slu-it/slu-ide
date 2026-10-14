package dev.sluide.fileviewers

import javafx.application.ColorScheme
import javafx.concurrent.Worker
import javafx.scene.Node
import javafx.scene.control.Label
import javafx.scene.layout.BorderPane
import javafx.scene.web.WebView
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
        val extension = file.extension.lowercase()
        if (extension == "svg" || extension !in imageTypes) return false
        return try {
            file.isRegularFile() && Files.isReadable(file) && file.fileSize() <= MAX_IMAGE_BYTES
        } catch (_: IOException) {
            false
        }
    }

    override fun open(file: Path): FileView = ImageFileView(file)

    internal companion object {
        const val MAX_IMAGE_BYTES = 50L * 1024 * 1024
    }
}

/**
 * An image on a checkered background ([ImagePage]), fitted to the view or at its actual size (click to switch), with
 * its pixel size and file size below. Follows the window's colour scheme; read again when the file changes.
 */
internal class ImageFileView(
    private val file: Path,
) : FileView {
    val webView = WebView().apply { isContextMenuEnabled = false }
    val status = Label().apply { styleClass += "image-status" }
    private val engine = webView.engine
    private val pane =
        BorderPane(webView).apply {
            bottom = status
            styleClass += "image-view"
            stylesheets += checkNotNull(ImageFileView::class.java.getResource("preview-view.css")).toExternalForm()
        }
    private var bytes = file.readBytes()
    private var dark = false
    private var disposed = false

    init {
        engine.loadWorker.stateProperty().subscribe { state -> if (state == Worker.State.SUCCEEDED) onLoaded() }
        // Nothing on the page navigates; something dropped onto it would. Show the image again.
        engine.locationProperty().subscribe { location -> if (!isPageFromString(location)) render() }
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
        if (file.fileSize() > ImageFileViewer.MAX_IMAGE_BYTES) throw IOException("$file is too large to show")
        val newBytes = file.readBytes()
        if (newBytes.contentEquals(bytes)) return
        bytes = newBytes
        render()
    }

    /** Stops showing the image; the blank page left is no page of ours. */
    override fun dispose() {
        disposed = true
        engine.load("")
    }

    private val isLoaded
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
    }
}

/** [bytes] for people: `512 B`, `1.5 KB`, `3.2 MB`. */
internal fun humanSize(bytes: Long): String =
    when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(Locale.ROOT, bytes / 1024.0)
        else -> "%.1f MB".format(Locale.ROOT, bytes / (1024.0 * 1024.0))
    }

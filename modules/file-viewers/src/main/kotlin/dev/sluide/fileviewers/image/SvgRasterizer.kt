package dev.sluide.fileviewers.image

import dev.sluide.fileviewers.preview.HtmlPage
import javafx.animation.AnimationTimer
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.concurrent.Worker
import javafx.scene.Group
import javafx.scene.SnapshotParameters
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.paint.Color
import javafx.scene.text.FontSmoothingType
import javafx.scene.web.WebView
import javafx.stage.Popup
import javafx.stage.PopupWindow
import javafx.stage.Window
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Draws an SVG as pixels at [scale] times its own size; blocking, never on the FX thread. */
internal fun interface SvgRasterizer {
    @Throws(IOException::class)
    fun rasterize(
        svg: ByteArray,
        scale: Int,
    ): BufferedImage
}

/**
 * Draws with the preview's WebKit: the SVG is an `<img>` (so its scripts never run) in an offscreen [WebView], laid out
 * at [scale] times its own size (`naturalWidth` × `naturalHeight`, the preview's 100%), so that the vector stays sharp,
 * and snapshotted on a transparent background. A WebView only paints while its window shows, so it sits in a popup of
 * the app's window, far off screen and shown during the call only; a popup takes no focus and has no taskbar entry.
 * Fails if no window shows, if the text is not an SVG image, if a side would exceed [MAX_SIDE] pixels or the image
 * [MAX_PIXELS] pixels, or if the drawing is not done within [timeout].
 */
internal class WebViewSvgRasterizer(
    private val timeout: Duration = Duration.ofSeconds(30),
) : SvgRasterizer {
    override fun rasterize(
        svg: ByteArray,
        scale: Int,
    ): BufferedImage {
        check(!Platform.isFxApplicationThread()) { "Rasterising blocks; not on the FX thread" }
        require(scale >= 1) { "Scale $scale" }
        val session = Session(page(svg), scale)
        Platform.runLater { session.failOnError(session::start) }
        try {
            return session.result.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            throw IOException("Timed out")
        } catch (e: ExecutionException) {
            val cause = e.cause
            throw cause as? IOException ?: IOException(cause?.message ?: "Failed", cause)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted")
        } finally {
            // Here, so that a start that is still to come shows nothing, and a late snapshot is not taken.
            session.result.completeExceptionally(IllegalStateException("Ended"))
            Platform.runLater(session::close)
        }
    }

    /**
     * One call's view, on the FX thread: loads the [page], sizes the image and the view at [scale] once it is loaded,
     * and snapshots it from the [MIN_PULSES]th pulse on until two snapshots agree (a paint can take more than one
     * pulse), at the [MAX_PULSES]th at the latest (an animated SVG never settles). A blank snapshot is not taken as
     * settled, since the view is blank until its first paint: a blank SVG takes [MAX_PULSES] pulses. The [result] is
     * the last snapshot.
     */
    private class Session(
        private val page: String,
        private val scale: Int,
    ) {
        val result = CompletableFuture<BufferedImage>()
        private var webView: WebView? = null
        private var popup: Popup? = null
        private var timer: AnimationTimer? = null
        private val loaded =
            ChangeListener<Worker.State> { _, _, state ->
                when (state) {
                    Worker.State.SUCCEEDED -> failOnError(::draw)
                    Worker.State.FAILED, Worker.State.CANCELLED -> fail("Not loaded")
                    else -> Unit
                }
            }

        fun start() {
            if (result.isDone) return
            val windows = Window.getWindows()
            val owner =
                windows.firstOrNull { it.isFocused && it !is PopupWindow }
                    ?: windows.firstOrNull { it.isShowing && it !is PopupWindow }
                    ?: return fail("No window to draw in")
            val webView =
                WebView().apply {
                    pageFill = Color.TRANSPARENT
                    // Subpixel smoothing would fringe text with colour on a transparent background.
                    fontSmoothingType = FontSmoothingType.GRAY
                }
            this.webView = webView
            popup =
                Popup().apply {
                    isAutoFix = false
                    isAutoHide = false
                    content += Group(webView)
                    show(owner, OFF_SCREEN, OFF_SCREEN)
                }
            webView.engine.loadWorker
                .stateProperty()
                .addListener(loaded)
            webView.engine.loadContent(page)
        }

        private fun draw() {
            val webView = webView ?: return
            // Only the page's own load; emptying it on close is another.
            webView.engine.loadWorker
                .stateProperty()
                .removeListener(loaded)
            val engine = webView.engine
            val (naturalWidth, naturalHeight) =
                listOf("naturalWidth", "naturalHeight").map {
                    (engine.executeScript("document.getElementById('svg').$it") as? Number)?.toLong() ?: 0
                }
            // An image that WebKit could not decode has no size.
            if (naturalWidth <= 0 || naturalHeight <= 0) return fail("Not an SVG image")
            val (width, height) = naturalWidth * scale to naturalHeight * scale
            if (width > MAX_SIDE || height > MAX_SIDE || width * height > MAX_PIXELS) {
                return fail("Too large at ${scale * 100}%: $width × $height px")
            }
            engine.executeScript(
                "document.getElementById('svg').style.cssText = 'width: ${width}px; height: ${height}px'",
            )
            webView.setMinSize(width.toDouble(), height.toDouble())
            webView.setPrefSize(width.toDouble(), height.toDouble())
            webView.setMaxSize(width.toDouble(), height.toDouble())
            webView.resize(width.toDouble(), height.toDouble())
            val image = BufferedImage(width.toInt(), height.toInt(), BufferedImage.TYPE_INT_ARGB)
            timer = Snapshots(webView, image).also { it.start() }
        }

        /** Snapshots the [webView] into the [image]'s pixels on each pulse, as described at [Session]. */
        private inner class Snapshots(
            private val webView: WebView,
            private val image: BufferedImage,
        ) : AnimationTimer() {
            private val pixels = (image.raster.dataBuffer as DataBufferInt).data
            private val parameters = SnapshotParameters().apply { fill = Color.TRANSPARENT }
            private var snapshot: WritableImage? = null
            private var pulses = 0
            private var previous: Int? = null

            override fun handle(now: Long) {
                if (result.isDone) return stop()
                if (++pulses < MIN_PULSES) return
                failOnError(::snap)
            }

            private fun snap() {
                // A popup hides with its owner window, and then nothing is painted any more.
                if (popup?.isShowing != true) {
                    stop()
                    return fail("The window closed")
                }
                val snapshot = webView.snapshot(parameters, snapshot).also { snapshot = it }
                val (width, height) = image.width to image.height
                if (snapshot.width.toInt() != width || snapshot.height.toInt() != height) {
                    stop()
                    return fail("Drawn at ${snapshot.width} × ${snapshot.height} px instead of $width × $height px")
                }
                snapshot.pixelReader.getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), pixels, 0, width)
                val hash = pixels.contentHashCode()
                val blank = pixels.all { it ushr 24 == 0 }
                if ((hash == previous && !blank) || pulses >= MAX_PULSES) {
                    stop()
                    result.complete(image)
                } else {
                    previous = hash
                }
            }
        }

        /** Runs [step]; an error ends the call at once, with its message, instead of letting it time out. */
        fun failOnError(step: () -> Unit) {
            try {
                step()
            } catch (e: Throwable) {
                timer?.stop()
                fail(e.message ?: e.javaClass.simpleName)
            }
        }

        private fun fail(message: String) {
            result.completeExceptionally(IOException(message))
        }

        /** Hides the popup and frees the page's memory. */
        fun close() {
            timer?.stop()
            timer = null
            val webView = webView ?: return
            this.webView = null
            webView.engine.loadWorker
                .stateProperty()
                .removeListener(loaded)
            webView.engine.load("about:blank")
            popup?.hide()
            popup = null
        }
    }

    companion object {
        /** The largest side of a drawing, in pixels. */
        const val MAX_SIDE = 8192

        /** The most pixels of a drawing: 32 Mi, 128 MiB as the image and as many as the snapshot. */
        const val MAX_PIXELS = 1L shl 25

        /** Where the popup is shown: far off any screen. */
        private const val OFF_SCREEN = -20000.0

        /** The pulse of the first snapshot: the view shows the resized image from the third pulse on. */
        private const val MIN_PULSES = 3

        /** The pulse of the last snapshot. */
        private const val MAX_PULSES = 20

        private const val STYLE =
            "html, body { margin: 0; overflow: hidden; background: transparent; } img { display: block; }"

        /** The page with the SVG as a `data:` image. */
        private fun page(svg: ByteArray) =
            HtmlPage.render(dark = false, styles = listOf(STYLE), scripts = emptyList()) {
                append("<img id=\"svg\" alt=\"\" src=\"data:image/svg+xml;base64,")
                append(Base64.getEncoder().encodeToString(svg)).append("\">")
            }
    }
}

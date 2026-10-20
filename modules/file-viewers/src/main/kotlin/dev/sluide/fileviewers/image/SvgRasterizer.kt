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

private val log = System.getLogger(WebViewSvgRasterizer::class.java.name)

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
 * The view, its popup and the snapshot are at most [tile] × [tile] pixels: the image is shifted under the view and
 * drawn piece by piece, since JavaFX's Metal pipeline on macOS crashed natively creating very large render textures.
 * Fails if no window shows, if the text is not an SVG image, if a side would exceed [MAX_SIDE] pixels or the image
 * [MAX_PIXELS] pixels, or if the drawing is not done within [timeout].
 */
internal class WebViewSvgRasterizer(
    private val timeout: Duration = Duration.ofSeconds(30),
    private val tile: Int = TILE,
) : SvgRasterizer {
    init {
        require(tile >= 1) { "Tile $tile" }
    }

    override fun rasterize(
        svg: ByteArray,
        scale: Int,
    ): BufferedImage {
        check(!Platform.isFxApplicationThread()) { "Rasterising blocks; not on the FX thread" }
        require(scale >= 1) { "Scale $scale" }
        val session = Session(page(svg), scale, tile)
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
     * One call's view, on the FX thread: loads the [page], sizes the image at [scale] and the view at a [tile] of it
     * once it is loaded, and draws the image tile by tile, row by row: it shifts the image so that the tile is at the
     * view's top left and snapshots the view from the [MIN_PULSES]th pulse after the shift on until two snapshots of
     * the tile agree (a paint can take more than one pulse), at the [MAX_PULSES]th at the latest (an animated SVG
     * never settles). A blank snapshot is not taken as settled, since the view is blank until its first paint: a
     * fully transparent tile takes [MAX_PULSES] pulses. The [result] is the image of the tiles' last snapshots.
     */
    private class Session(
        private val page: String,
        private val scale: Int,
        private val tile: Int,
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
            val image = BufferedImage(width.toInt(), height.toInt(), BufferedImage.TYPE_INT_ARGB)
            val (viewWidth, viewHeight) = minOf(tile, image.width).toDouble() to minOf(tile, image.height).toDouble()
            webView.setMinSize(viewWidth, viewHeight)
            webView.setPrefSize(viewWidth, viewHeight)
            webView.setMaxSize(viewWidth, viewHeight)
            webView.resize(viewWidth, viewHeight)
            timer = Snapshots(webView, image).also { it.start() }
        }

        /** Snapshots the [webView] tile by tile into the [image]'s pixels on each pulse, as described at [Session]. */
        private inner class Snapshots(
            private val webView: WebView,
            private val image: BufferedImage,
        ) : AnimationTimer() {
            private val pixels = (image.raster.dataBuffer as DataBufferInt).data
            private val parameters = SnapshotParameters().apply { fill = Color.TRANSPARENT }
            private val viewWidth = minOf(tile, image.width)
            private val viewHeight = minOf(tile, image.height)
            private val tilePixels = IntArray(viewWidth * viewHeight)
            private val columns = (image.width + tile - 1) / tile
            private val tiles = columns * ((image.height + tile - 1) / tile)
            private var snapshot: WritableImage? = null
            private var index = 0
            private var pulses = 0
            private var previous: Int? = null

            init {
                shift()
            }

            override fun handle(now: Long) {
                if (result.isDone) return stop()
                if (++pulses < MIN_PULSES) return
                failOnError(::snap)
            }

            /** Lays the image out so that the tile at [index] is at the view's top left. */
            private fun shift() {
                val (x, y) = index % columns * tile to index / columns * tile
                webView.engine.executeScript(
                    "document.getElementById('svg').style.cssText = " +
                        "'width: ${image.width}px; height: ${image.height}px; left: -${x}px; top: -${y}px'",
                )
                pulses = 0
                previous = null
            }

            private fun snap() {
                // A popup hides with its owner window, and then nothing is painted any more.
                if (popup?.isShowing != true) {
                    stop()
                    return fail("The window closed")
                }
                val snapshot = webView.snapshot(parameters, snapshot).also { snapshot = it }
                if (snapshot.width.toInt() != viewWidth || snapshot.height.toInt() != viewHeight) {
                    stop()
                    return fail(
                        "Drawn at ${snapshot.width} × ${snapshot.height} px instead of $viewWidth × $viewHeight px",
                    )
                }
                val (x, y) = index % columns * tile to index / columns * tile
                val (width, height) = minOf(tile, image.width - x) to minOf(tile, image.height - y)
                val format = PixelFormat.getIntArgbInstance()
                snapshot.pixelReader.getPixels(0, 0, width, height, format, tilePixels, 0, width)
                var hash = 1
                var blank = true
                for (i in 0 until width * height) {
                    hash = 31 * hash + tilePixels[i]
                    blank = blank && tilePixels[i] ushr 24 == 0
                }
                if ((hash != previous || blank) && pulses < MAX_PULSES) {
                    previous = hash
                    return
                }
                for (row in 0 until height) {
                    System.arraycopy(tilePixels, row * width, pixels, (y + row) * image.width + x, width)
                }
                if (++index < tiles) {
                    shift()
                } else {
                    stop()
                    result.complete(image)
                }
            }
        }

        /** Runs [step]; an error ends the call at once, with its message, instead of letting it time out. */
        fun failOnError(step: () -> Unit) {
            try {
                step()
            } catch (e: Throwable) {
                log.log(System.Logger.Level.ERROR, "Rasterising the SVG failed", e)
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

        /** The most pixels of a drawing: 32 Mi, 128 MiB as the image. */
        const val MAX_PIXELS = 1L shl 25

        /** Where the popup is shown: far off any screen. */
        private const val OFF_SCREEN = -20000.0

        /** The pulse of the first snapshot of a tile: the view shows the image's new size or position from the third. */
        private const val MIN_PULSES = 3

        /** The largest side of the view, of its popup and of a snapshot, in pixels. */
        private const val TILE = 1024

        /** The pulse of the last snapshot. */
        private const val MAX_PULSES = 20

        private const val STYLE =
            "html, body { margin: 0; overflow: hidden; background: transparent; } " +
                "img { display: block; position: absolute; left: 0; top: 0; }"

        /** The page with the SVG as a `data:` image. */
        private fun page(svg: ByteArray) =
            HtmlPage.render(dark = false, styles = listOf(STYLE), scripts = emptyList()) {
                append("<img id=\"svg\" alt=\"\" src=\"data:image/svg+xml;base64,")
                append(Base64.getEncoder().encodeToString(svg)).append("\">")
            }
    }
}

package dev.sluide.previews

import dev.sluide.previews.preview.WebViewZoomTarget
import dev.sluide.previews.preview.ZoomControls
import javafx.scene.Node
import javafx.scene.web.WebView

/**
 * The document zoom of the HTML and Markdown previews (ADR 0083) for other modules' web views: [WebView.zoom] scales
 * the whole page. [node] is the toolbar group (zoom out, level, zoom in, 100%), [install] adds the keys
 * Shortcut+Plus/Minus/0 and Shortcut+wheel or pinch on the [webView].
 *
 * FX thread only.
 */
class DocumentZoom(
    private val webView: WebView,
) {
    private val controls = ZoomControls(WebViewZoomTarget(webView))

    val node: Node get() = controls.node

    fun install() = controls.install(webView)
}

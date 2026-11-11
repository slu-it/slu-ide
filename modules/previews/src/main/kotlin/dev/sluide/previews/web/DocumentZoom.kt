package dev.sluide.previews.web

import dev.sluide.previews.preview.ZoomControls
import javafx.scene.Node
import javafx.scene.web.WebView
import dev.sluide.previews.preview.DocumentZoom as WebViewZoom

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
    private val controls = ZoomControls(WebViewZoom(webView))

    val node: Node get() = controls.node

    fun install() = controls.install(webView)
}

package dev.sluide.fileviewers

import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.FileSystemNotFoundException
import java.nio.file.Path

/**
 * Shows HTML files (`.html`, `.htm`, `.xhtml`) as highlighted source beside the page itself, loaded from disk so that
 * its relative styles, images and scripts work. Its scripts only run when allowed in the toolbar (ADR 0017).
 */
@Component
class HtmlFileViewer : PreviewFileViewer(setOf("html", "htm", "xhtml"), RSyntaxHighlighter.html, HtmlPreview)

/** The HTML file itself, as the author styled it; links leave it and are opened elsewhere by the view. */
internal object HtmlPreview : Preview {
    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
    ) = engine.load(file.toUri().toString())

    override val hasOwnScript get() = false

    override val offersScripts get() = true

    /** The file itself, at any anchor (`#part`). */
    override fun isOwnPage(
        location: String?,
        file: Path,
    ): Boolean =
        try {
            location != null && Path.of(URI(location.substringBefore('#'))).normalize() == file
        } catch (_: URISyntaxException) {
            false
        } catch (_: IllegalArgumentException) {
            // Not a file URI: another scheme, or a file URI with authority or query.
            false
        } catch (_: FileSystemNotFoundException) {
            false
        }
}

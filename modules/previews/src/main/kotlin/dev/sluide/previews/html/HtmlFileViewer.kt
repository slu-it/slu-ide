package dev.sluide.previews.html

import dev.sluide.fileviewers.Highlighters
import dev.sluide.previews.preview.LinkTarget
import dev.sluide.previews.preview.Preview
import dev.sluide.previews.preview.PreviewFileViewer
import dev.sluide.previews.preview.resolveLink
import dev.sluide.textediting.CommentSyntax
import javafx.scene.web.WebEngine
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Shows HTML files (`.html`, `.htm`, `.xhtml`) as highlighted source beside the page itself, served with the files of
 * its project ([PreviewServer]) so that its relative styles, images and scripts work. Its scripts only run when allowed
 * in the toolbar, and then reach no files outside the project, nor hidden ones (ADRs 0017 and 0018).
 */
@Component
class HtmlFileViewer :
    PreviewFileViewer(setOf("html", "htm", "xhtml"), Highlighters.html, HtmlPreview, CommentSyntax.XML)

/** The HTML file itself, as the author styled it; links leave it and are opened elsewhere by the view. */
internal object HtmlPreview : Preview {
    override fun load(
        engine: WebEngine,
        file: Path,
        text: String,
        dark: Boolean,
        root: Path,
    ) = engine.load(PreviewServer.url(root, file).toString())

    override val usesRoot get() = true

    override fun root(
        file: Path,
        projectFolder: Path?,
    ) = PreviewServer.rootFor(projectFolder, file)

    override val hasOwnScript get() = false

    override val offersScripts get() = true

    /** The file itself, at any anchor (`#part`), but without a query: a page reloading itself with one would loop. */
    override fun isOwnPage(
        location: String?,
        file: Path,
    ): Boolean {
        if (location == null || location.substringBefore('#').contains('?')) return false
        val served = PreviewServer.fileFor(location) ?: return false
        return try {
            Files.isSameFile(served, file)
        } catch (_: IOException) {
            false
        }
    }

    /** Pages on the server stand for their files; other links lead where they would from the file on disk. */
    override fun linkTarget(
        href: String,
        file: Path,
    ): LinkTarget? = PreviewServer.fileFor(href)?.let(LinkTarget::File) ?: resolveLink(href, file.parent)
}

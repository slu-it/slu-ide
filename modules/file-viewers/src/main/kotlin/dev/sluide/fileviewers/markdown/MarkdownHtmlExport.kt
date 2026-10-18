package dev.sluide.fileviewers.markdown

import dev.sluide.fileviewers.preview.ExportedPage
import dev.sluide.fileviewers.preview.HtmlExport
import java.net.URI
import java.net.URISyntaxException
import java.nio.file.Path
import java.util.Base64
import kotlin.io.path.isRegularFile
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.readText

/**
 * Markdown as a self-contained HTML page `name.html` beside `name.md`: images (local and, via [images], remote) are
 * embedded, KaTeX and Mermaid are inlined, and a `name.css` beside the file is inlined after the built-in styles. The
 * page follows the reader's colour scheme.
 */
internal class MarkdownHtmlExport(
    private val images: ImageFetcher = HttpImageFetcher(),
) : HtmlExport {
    override fun target(file: Path): Path = file.resolveSibling(file.nameWithoutExtension + ".html")

    /** The stylesheet that [file]'s export includes if it exists. */
    fun stylesheet(file: Path): Path = file.resolveSibling(file.nameWithoutExtension + ".css")

    override fun render(
        file: Path,
        text: String,
    ): ExportedPage {
        val body = MarkdownRenderer.body(text, file.parent)
        // Each address is downloaded once, failures included.
        val downloaded = mutableMapOf<URI, String?>()
        body.select("img[src]").forEach { image ->
            val uri = remote(image.attr("src")) ?: return@forEach
            if (uri !in downloaded) {
                downloaded[uri] =
                    images.fetch(uri)?.let { "data:${it.type};base64," + Base64.getEncoder().encodeToString(it.bytes) }
            }
            downloaded[uri]?.let { image.attr("src", it) }
        }
        val notEmbedded =
            body
                .select("img[src]")
                .map { it.attr("src") }
                .filterNot { it.startsWith("data:") }
                .distinct()
        val css = stylesheet(file).takeIf { it.isRegularFile() }?.readText()?.replace(STYLE_END, "<\\\\/$1")
        val html =
            MarkdownRenderer.page(
                body,
                dark = false,
                standalone = true,
                title = file.fileName.toString(),
                extraStyles = listOfNotNull(css),
            )
        return ExportedPage(html, notEmbedded)
    }

    private companion object {
        /** A `</style` in the stylesheet would end its element. */
        val STYLE_END = Regex("</(style)", RegexOption.IGNORE_CASE)
    }

    private fun remote(src: String): URI? =
        try {
            URI(src.trim()).takeIf { it.scheme?.lowercase() in setOf("http", "https") }
        } catch (_: URISyntaxException) {
            null
        }
}

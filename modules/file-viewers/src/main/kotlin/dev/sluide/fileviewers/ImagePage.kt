package dev.sluide.fileviewers

import java.util.Base64

/** Media types of the image files the previews show, by lower-case extension. */
internal val imageTypes =
    mapOf(
        "png" to "image/png",
        "jpg" to "image/jpeg",
        "jpeg" to "image/jpeg",
        "gif" to "image/gif",
        "svg" to "image/svg+xml",
        "webp" to "image/webp",
        "bmp" to "image/bmp",
        "ico" to "image/x-icon",
    )

/**
 * A page showing one image on a checkered background, so that transparency shows: fitted to the view, or at its
 * actual size after a click. The image is an `<img>` with a `data:` URI, so an SVG cannot run scripts. The page brings
 * the previews' script (`sluide.setDark`).
 */
internal object ImagePage {
    private val stylesheet = HtmlPage.resource("image-page.css")
    private val script = HtmlPage.resource("preview.js")
    private const val SWITCH_SIZE =
        "document.body.addEventListener('click', function () { document.body.classList.toggle('actual'); });"

    fun render(
        bytes: ByteArray,
        type: String,
        dark: Boolean,
    ): String =
        HtmlPage.render(dark, listOf(stylesheet), listOf(script, SWITCH_SIZE)) {
            append("<img alt=\"\" src=\"data:").append(type).append(";base64,")
            append(Base64.getEncoder().encodeToString(bytes)).append("\">")
        }
}

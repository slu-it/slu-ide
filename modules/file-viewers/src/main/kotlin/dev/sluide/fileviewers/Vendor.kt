package dev.sluide.fileviewers

import java.util.Base64

/**
 * The JavaScript libraries bundled in `vendor/` for the previews (versions in `vendor/README.md`), read once when
 * first needed. Pages are loaded from strings and cannot reference files in the jar, so they get the libraries inline
 * and KaTeX's fonts as `data:` URIs.
 */
internal object Vendor {
    val katexJs: String by lazy { text("katex/katex.min.js") }

    val mermaidJs: String by lazy { text("mermaid/mermaid.min.js") }

    /**
     * KaTeX's stylesheet with its woff fonts inlined; the woff2 and ttf alternatives are dropped. JavaFX's WebKit fails
     * to load woff2 fonts from `data:` URIs.
     */
    val katexCss: String by lazy {
        FONT_SOURCES.replace(text("katex/katex.min.css")) { match ->
            val font = Base64.getEncoder().encodeToString(bytes("katex/fonts/${match.groupValues[1]}.woff"))
            "src:url(data:font/woff;base64,$font) format(\"woff\")"
        }
    }

    /** `src:url(fonts/X.woff2) format("woff2"),url(fonts/X.woff) …` up to the end of the declaration; X is group 1. */
    private val FONT_SOURCES = Regex("""src:url\(fonts/([\w-]+)\.woff2\)[^;}]*""")

    private fun bytes(path: String) =
        checkNotNull(
            Vendor::class.java.getResourceAsStream("vendor/$path"),
        ) { "missing vendor/$path" }.use { it.readBytes() }

    private fun text(path: String) = bytes(path).toString(Charsets.UTF_8)
}

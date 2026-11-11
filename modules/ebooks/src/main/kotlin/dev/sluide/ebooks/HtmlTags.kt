package dev.sluide.ebooks

private fun words(list: String): Set<String> = list.split(' ').filter { it.isNotEmpty() }.toSet()

/** The tags of the page: what is dropped with its content, what starts a block, what is kept; the rest is unwrapped. */
internal object HtmlTags {
    val dropped =
        words(
            "script style iframe object embed form input button select textarea link meta head title audio video " +
                "canvas noscript applet template frame frameset base source track param map",
        )

    val blocks =
        words(
            "p h1 h2 h3 h4 h5 h6 ul ol li dl dt dd blockquote pre table caption thead tbody tfoot tr td th figure " +
                "figcaption aside section article header footer nav main div address details summary",
        )

    val allowed =
        blocks +
            words(
                "em strong i b u s small sup sub cite abbr q mark del ins code kbd samp var dfn time span a ruby rt " +
                    "rp bdi bdo",
            )

    val headings = words("h1 h2 h3 h4 h5 h6")
}

internal fun escapeText(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

internal fun escapeAttr(text: String): String = escapeText(text).replace("\"", "&quot;")

/** Appends ` name="value"` with the value escaped: every attribute value of the page goes through here. */
internal fun StringBuilder.attr(
    name: String,
    value: String,
): StringBuilder =
    append(' ')
        .append(name)
        .append("=\"")
        .append(escapeAttr(value))
        .append('"')

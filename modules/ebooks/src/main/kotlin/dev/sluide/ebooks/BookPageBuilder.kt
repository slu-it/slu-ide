package dev.sluide.ebooks

import java.security.MessageDigest
import java.util.Base64

/** Builds the one HTML page of a book with its flat text (ADR 0165). */
internal object BookPageBuilder {
    /**
     * Nothing in the page may load from the network, and only the one script of the page runs, by its hash: inline
     * handlers, `eval` and other scripts are refused. Navigation is not covered; the view has to route it.
     */
    private fun policy(script: String): String {
        val hash = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(script.toByteArray()))
        return "default-src 'none'; img-src data:; font-src data:; style-src 'unsafe-inline'; script-src 'sha256-$hash'"
    }

    /**
     * [script] is put verbatim at the end of the body. With [chapterRange] only those spine indices are included and
     * the offsets start at 0 for the first of them. [ownStyle] keeps the book's CSS (sanitised) instead of Thoreau's.
     */
    fun build(
        book: EpubBook,
        ownStyle: Boolean,
        dark: Boolean,
        script: String,
        chapterRange: IntRange? = null,
    ): BookPage {
        val indices = (chapterRange ?: book.chapters.indices).filter { it in book.chapters.indices }
        val flow = PageText()
        val context =
            WalkContext(
                book,
                ownStyle,
                flow,
                spineIndex = book.chapters.withIndex().associate { it.value.href to it.index },
                styles = linkedMapOf(),
                usedIds = hashSetOf(),
            )
        val body = StringBuilder()
        val spans = mutableListOf<ChapterSpan>()
        for (i in indices) {
            val chapter = book.chapters[i]
            val walker = ChapterWalker(context, i, chapter)
            body.append(walker.write())
            val title =
                chapter.title ?: walker.firstHeading ?: "Chapter ${i + 1}"
            val start = flow.anchors["ch$i"] ?: flow.length
            spans += ChapterSpan(i, title, start, flow.length)
        }
        flow.resolveAnchorsAtEnd()

        // The HTML parser turns CR and CRLF into LF; the hash has to be of the text it sees.
        val html =
            document(
                book,
                ownStyle,
                dark,
                script.replace("\r\n", "\n").replace('\r', '\n'),
                context.styles.values.joinToString("\n"),
                body,
            )
        val toc = resolveToc(book.toc, context.spineIndex, indices.toSet(), flow.anchors)
        return BookPage(html, flow.text, flow.skip, spans, toc, flow.anchors.toMap())
    }

    private fun document(
        book: EpubBook,
        ownStyle: Boolean,
        dark: Boolean,
        script: String,
        bookCss: String,
        body: CharSequence,
    ): String {
        val css =
            if (ownStyle) {
                listOf(
                    ReadingCss.frame,
                    bookCss,
                    ReadingCss.marks,
                )
            } else {
                listOf(ReadingCss.reading, ReadingCss.marks)
            }
        val lang = book.language?.takeIf { Regex("[A-Za-z0-9-]{1,35}").matches(it) }
        return buildString {
            append("<!DOCTYPE html>\n<html")
            if (lang != null) append(" lang=\"$lang\"")
            append("><head><meta charset=\"utf-8\">")
            append("<meta http-equiv=\"Content-Security-Policy\" content=\"${policy(script)}\">")
            append("<title>").append(escapeText(book.title)).append("</title>")
            append("<style>").append(css.joinToString("\n")).append("</style></head>")
            append(if (dark) "<body class=\"dark\">" else "<body>")
            append(body)
            append("<script>").append(script).append("</script></body></html>")
        }
    }

    private fun resolveToc(
        entries: List<TocEntry>,
        spineIndex: Map<String, Int>,
        included: Set<Int>,
        anchors: Map<String, Int>,
    ): List<TocItem> =
        entries.flatMap { entry ->
            val children = resolveToc(entry.children, spineIndex, included, anchors)
            val chapter = spineIndex[entry.href.substringBefore('#')]?.takeIf { it in included }
            val fragment = entry.href.substringAfter('#', "")
            val offset =
                chapter?.let {
                    anchors["ch$it-$fragment"].takeIf { fragment.isNotEmpty() } ?: anchors["ch$it"]
                }
            if (offset != null) listOf(TocItem(entry.title, offset, children)) else children
        }
}

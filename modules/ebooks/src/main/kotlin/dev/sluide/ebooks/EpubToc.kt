package dev.sluide.ebooks

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.parser.Parser

/** The table of contents of an EPUB: nav document, else NCX, else the spine. Lenient: a broken file gives no entries. */
internal object EpubToc {
    private val headings = "h1, h2, h3, h4, h5, h6"

    /** EPUB 3 nav document; [navPath] is its zip path (hrefs are relative to its folder). */
    fun fromNav(
        navPath: String,
        bytes: ByteArray,
    ): List<TocEntry> {
        val doc = Jsoup.parse(EpubXml.decode(bytes), "", Parser.xmlParser())
        val navs = doc.getElementsByTag("nav")
        val nav =
            navs.firstOrNull { it.attr("epub:type").split(' ').contains("toc") } ?: navs.firstOrNull()
                ?: return emptyList()
        val list = nav.children().firstOrNull { it.normalName() == "ol" } ?: return emptyList()
        return navList(list, EpubPaths.dirOf(navPath))
    }

    private fun navList(
        ol: Element,
        dir: String,
    ): List<TocEntry> =
        ol.children().filter { it.normalName() == "li" }.flatMap { li ->
            val label = li.children().firstOrNull { it.normalName() == "a" || it.normalName() == "span" }
            val children =
                li
                    .children()
                    .firstOrNull { it.normalName() == "ol" }
                    ?.let { navList(it, dir) }
                    .orEmpty()
            val href = label?.takeIf { it.normalName() == "a" }?.attr("href").orEmpty()
            val title = label?.text()?.trim().orEmpty()
            if (label != null && href.isNotEmpty() && title.isNotEmpty()) {
                listOf(TocEntry(title, join(dir, href), children))
            } else {
                children
            }
        }

    /** EPUB 2 NCX; hrefs are relative to the NCX file's folder. */
    fun fromNcx(
        ncxPath: String,
        bytes: ByteArray,
    ): List<TocEntry> {
        val doc = Jsoup.parse(EpubXml.decode(bytes), "", Parser.xmlParser())
        val map = doc.getElementsByTag("navMap").firstOrNull() ?: return emptyList()
        return navPoints(map, EpubPaths.dirOf(ncxPath))
    }

    private fun navPoints(
        parent: Element,
        dir: String,
    ): List<TocEntry> =
        parent.children().filter { it.normalName() == "navpoint" }.flatMap { point ->
            val title =
                point
                    .children()
                    .firstOrNull { it.normalName() == "navlabel" }
                    ?.text()
                    ?.trim()
                    .orEmpty()
            val src =
                point
                    .children()
                    .firstOrNull { it.normalName() == "content" }
                    ?.attr("src")
                    .orEmpty()
            val children = navPoints(point, dir)
            if (title.isNotEmpty() && src.isNotEmpty()) listOf(TocEntry(title, join(dir, src), children)) else children
        }

    /** One entry per spine item: its first heading or `<title>`, else the file name. */
    fun fromSpine(chapters: List<Chapter>): List<TocEntry> =
        chapters.map { chapter ->
            val doc = Jsoup.parse(chapter.xhtml, "", Parser.xmlParser())
            val title =
                doc
                    .selectFirst(headings)
                    ?.text()
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?: doc
                        .getElementsByTag("title")
                        .firstOrNull()
                        ?.text()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                    ?: chapter.href.substringAfterLast('/').substringBeforeLast('.')
            TocEntry(title, chapter.href, emptyList())
        }

    /** The first entry (depth first) whose path, without fragment, is [href]. */
    fun titleFor(
        toc: List<TocEntry>,
        href: String,
    ): String? {
        for (entry in toc) {
            if (entry.href.substringBefore('#') == href) return entry.title
            titleFor(entry.children, href)?.let { return it }
        }
        return null
    }

    private fun join(
        dir: String,
        href: String,
    ): String {
        val ref = EpubPaths.resolve(dir, href)
        return if (ref.fragment != null) "${ref.path}#${ref.fragment}" else ref.path
    }
}

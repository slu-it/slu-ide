package dev.sluide.pdf.engine

import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSInteger
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSNumber
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.interactive.action.PDAction
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitHeightDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * The outline and the links of a document, with their destinations resolved to [PageTarget]s: explicit destinations
 * (a page or a page number), named ones (the name tree and the older `/Dests` dictionary) and GoTo actions. What
 * cannot be resolved has no target. Untrusted structures are walked with limits, never with PDFBox's own walks (which
 * loop forever on a `/Parent` loop and overflow the stack on a name tree that contains itself): a destination's page
 * is looked up in [pageIndex] (the page dictionaries of the page tree), the outline has at most [MAX_OUTLINE_DEPTH]
 * levels and [MAX_OUTLINE_ITEMS] items, the name tree at most [MAX_NAME_TREE_DEPTH] levels, and a node met a second
 * time (a loop) is skipped.
 */
internal class DocumentNavigation(
    private val document: PDDocument,
    private val pages: List<PDPage>,
    private val geometries: List<PageGeometry>,
    private val pageIndex: Map<COSDictionary, Int>,
) {
    fun outline(): List<OutlineItem> {
        val root = document.documentCatalog.documentOutline ?: return emptyList()
        return OutlineWalk().items(root, depth = 1)
    }

    private inner class OutlineWalk {
        private val visited = HashSet<COSDictionary>()

        fun items(
            node: PDOutlineNode,
            depth: Int,
        ): List<OutlineItem> {
            val items = ArrayList<OutlineItem>()
            for (item in node.children()) {
                if (visited.size == MAX_OUTLINE_ITEMS || !visited.add(item.cosObject)) break
                val target = target { resolve(item.destination, item.action) }
                val children = if (depth < MAX_OUTLINE_DEPTH) items(item, depth + 1) else emptyList()
                items += OutlineItem(item.title.orEmpty(), target, item.isNodeOpen, children)
            }
            return items
        }
    }

    fun links(page: Int): List<PageLink> {
        val geometry = geometries[page]
        return pages[page].annotations.filterIsInstance<PDAnnotationLink>().mapNotNull { link ->
            val rect = link.rectangle ?: return@mapNotNull null
            val linkTarget =
                webTarget(link.action) ?: target { resolve(link.destination, link.action) }?.let(LinkTarget::Page)
            linkTarget?.let {
                val area =
                    geometry.displayedUserRect(
                        rect.lowerLeftX.toDouble(),
                        rect.lowerLeftY.toDouble(),
                        rect.upperRightX.toDouble(),
                        rect.upperRightY.toDouble(),
                    )
                PageLink(area, it)
            }
        }
    }

    /** A web link with one of [WEB_SCHEMES]; others (`javascript:`, `file:`, …) and malformed URIs are dropped. */
    private fun webTarget(action: PDAction?): LinkTarget.Web? {
        val uri = (action as? PDActionURI)?.uri ?: return null
        return try {
            URI(uri).takeIf { it.scheme?.lowercase() in WEB_SCHEMES }?.let(LinkTarget::Web)
        } catch (_: URISyntaxException) {
            null
        }
    }

    /** A target, or `null` when reading the destination fails: PDFBox throws for a destination of an unknown type. */
    private fun target(resolve: () -> PageTarget?): PageTarget? =
        try {
            resolve()
        } catch (_: IOException) {
            null
        }

    private fun resolve(
        destination: PDDestination?,
        action: PDAction?,
    ): PageTarget? {
        val given = destination ?: (action as? PDActionGoTo)?.destination
        val pageDestination =
            when (given) {
                is PDNamedDestination -> named(given.namedDestination)
                else -> given as? PDPageDestination
            } ?: return null
        val page =
            when (val first = pageDestination.cosObject.getObject(0)) {
                is COSDictionary -> pageIndex[first]
                is COSInteger -> first.intValue()
                else -> null
            }
        if (page == null || page !in geometries.indices) return null
        return PageTarget(page, geometries[page].displayedTop(left(pageDestination), top(pageDestination)))
    }

    /** The destination a name stands for: in the name tree, or in the older `/Dests` dictionary. */
    private fun named(name: String): PDPageDestination? {
        val value =
            namesInTree[name]
                ?: (document.documentCatalog.cosObject.getDictionaryObject(COSName.DESTS) as? COSDictionary)
                    ?.getDictionaryObject(COSName.getPDFName(name))
        // A value is the destination, or a dictionary holding it under /D; PDFBox makes no destination of null.
        val destination = if (value is COSDictionary) value.getDictionaryObject(COSName.D) else value
        return PDDestination.create(destination) as? PDPageDestination
    }

    /** The name tree of destinations, read once, as name → value. */
    private val namesInTree: Map<String, COSBase?> by lazy {
        val names = HashMap<String, COSBase?>()
        val root =
            (document.documentCatalog.cosObject.getDictionaryObject(COSName.NAMES) as? COSDictionary)
                ?.getDictionaryObject(COSName.DESTS) as? COSDictionary
        root?.let { collectNames(it, 1, Collections.newSetFromMap(IdentityHashMap()), names) }
        names
    }

    private fun collectNames(
        node: COSDictionary,
        depth: Int,
        visited: MutableSet<COSDictionary>,
        names: MutableMap<String, COSBase?>,
    ) {
        if (depth > MAX_NAME_TREE_DEPTH || !visited.add(node)) return
        (node.getDictionaryObject(COSName.NAMES) as? COSArray)?.let { pairs ->
            // Pairs of a key and its value; an odd last key has no value.
            for (pair in 0 until pairs.size() / 2) {
                val key = pairs.getObject(2 * pair) as? COSString ?: continue
                // A null value is as good as none: the name is then looked up in /Dests.
                names.putIfAbsent(key.string, pairs.getObject(2 * pair + 1))
            }
        }
        (node.getDictionaryObject(COSName.KIDS) as? COSArray)?.let { kids ->
            for (index in 0 until kids.size()) {
                (kids.getObject(index) as? COSDictionary)?.let { collectNames(it, depth + 1, visited, names) }
            }
        }
    }

    /** The top a destination names, in user space: the 4th entry of an XYZ, the 3rd of a FitH or FitBH destination. */
    private fun top(destination: PDPageDestination): Double? =
        when (destination) {
            is PDPageXYZDestination -> number(destination, 3)
            is PDPageFitWidthDestination -> number(destination, 2)
            else -> null
        }

    /** The left a destination names, in user space: the 3rd entry of an XYZ, FitV or FitBV destination. */
    private fun left(destination: PDPageDestination): Double? =
        when (destination) {
            is PDPageXYZDestination, is PDPageFitHeightDestination -> number(destination, 2)
            else -> null
        }

    /** An entry of the destination array, `null` when it is missing or `null` (keep the current value). */
    private fun number(
        destination: PDPageDestination,
        index: Int,
    ): Double? = (destination.cosObject.getObject(index) as? COSNumber)?.floatValue()?.toDouble()

    companion object {
        const val MAX_OUTLINE_DEPTH = 100
        const val MAX_OUTLINE_ITEMS = 10_000
        const val MAX_NAME_TREE_DEPTH = 100
        private val WEB_SCHEMES = setOf("http", "https", "mailto")
    }
}

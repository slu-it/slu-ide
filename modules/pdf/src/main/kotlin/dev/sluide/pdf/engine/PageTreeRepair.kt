package dev.sluide.pdf.engine

import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import java.io.IOException
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Makes the page tree under [root] tell the truth, in memory only (the document is never saved): every node's
 * `/Count` becomes the number of pages below it, and a kid that is no dictionary or was met before (a loop, a page
 * listed twice) is removed from its `/Kids`. PDFBox walks the kids to list the pages but descends by `/Count` to fetch
 * page i (`PDPageTree.get`, which `PDFRenderer` uses); after this both give the same page for every index, and PDFBox's
 * walk meets no node twice, so it logs nothing about one. A node is what PDFBox takes for one: type `/Pages` or a
 * `/Kids` entry. A tree with a node deeper than [MAX_PAGE_TREE_DEPTH] levels (the root is level 1) throws an
 * `IOException`: PDFBox's walks are recursive and would overflow the stack on it.
 */
internal fun repairPageTree(root: COSDictionary) {
    countPages(root, 1, Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>()).also { it.add(root) })
}

/** The deepest level of a page-tree node [repairPageTree] accepts. */
internal const val MAX_PAGE_TREE_DEPTH = 1000

private fun countPages(
    node: COSDictionary,
    depth: Int,
    visited: MutableSet<COSDictionary>,
): Int {
    if (depth > MAX_PAGE_TREE_DEPTH) throw IOException("page tree too deep")
    var pages = 0
    node.getCOSArray(COSName.KIDS)?.let { kids ->
        var index = 0
        while (index < kids.size()) {
            val kid = kids.getObject(index) as? COSDictionary
            if (kid == null || !visited.add(kid)) {
                kids.remove(index)
                continue
            }
            val isNode = kid.getCOSName(COSName.TYPE) == COSName.PAGES || kid.containsKey(COSName.KIDS)
            pages += if (isNode) countPages(kid, depth + 1, visited) else 1
            index++
        }
    }
    node.setInt(COSName.COUNT, pages)
    return pages
}

package dev.sluide.pdf

import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSBase
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSInteger
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSNull
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * PDFs that are broken on purpose, built with PDFBox's COS objects where its API refuses (ADR 0192): what the engine
 * must survive without hanging, leaking or throwing anything but an `IOException`.
 */
object HostilePdfs {
    /**
     * An outline item and a link on page 1 whose destination names a page dictionary that is in no page tree and whose
     * `/Parent` chain is a loop (PDFBox's `retrievePageNumber` walks it forever).
     */
    val parentLoop: Path by lazy {
        TestPdfs.build("parent-loop.pdf") { document ->
            TestPdfs.numberedPages(document, 1)
            val first = COSDictionary()
            val second = COSDictionary()
            first.setItem(COSName.PARENT, second)
            second.setItem(COSName.PARENT, first)
            first.isDirect = false
            second.isDirect = false
            val destination =
                COSArray().also {
                    it.add(first)
                    it.add(COSName.getPDFName("Fit"))
                }
            val outline = PDDocumentOutline()
            document.documentCatalog.documentOutline = outline
            val item = TestPdfs.item("loop")
            item.cosObject.setItem(COSName.DEST, destination)
            outline.addLast(item)
            val link = PDAnnotationLink()
            link.rectangle = PDRectangle(10f, 10f, 10f, 10f)
            link.cosObject.setItem(COSName.DEST, destination)
            document.getPage(0).annotations.add(link)
        }
    }

    /**
     * One page below a chain of [levels] page-tree nodes under the root: the deepest node is at level [levels] + 1,
     * counting the root as level 1.
     */
    fun deepPageTree(levels: Int): Path =
        TestPdfs.build("deep-page-tree-$levels.pdf") { document ->
            TestPdfs.numberedPages(document, 1)
            val root = document.pages.cosObject
            var child = document.getPage(0).cosObject
            repeat(levels) {
                val node = dictionary(COSName.TYPE, COSName.PAGES)
                node.setItem(COSName.KIDS, array(child))
                node.setInt(COSName.COUNT, 1)
                // Indirect, so that PDFBox writes the chain one object at a time, not as one nested object.
                node.isDirect = false
                child.setItem(COSName.PARENT, node)
                child = node
            }
            root.setItem(COSName.KIDS, array(child))
            child.setItem(COSName.PARENT, root)
        }

    /** Two pages, but the page tree's `/Count` says 5. */
    val lyingCount: Path by lazy {
        TestPdfs.build("lying-count.pdf") { document ->
            TestPdfs.numberedPages(document, 2)
            document.pages.cosObject.setInt(COSName.COUNT, 5)
        }
    }

    /** How the page tree of [pageTree] lies. */
    enum class PageTreeFlaw {
        /** Node A says `/Count 1` though it has two pages. */
        MIDDLE_COUNT,

        /** The root says `/Count 2` though the tree has four pages. */
        ROOT_COUNT,

        /** Node B lists the root again as its last kid. */
        KIDS_LOOP,

        /** Node A lists the number 5 between its pages. */
        NUMBER_KID,

        /** The root lists, before A, a node without kids that says `/Count 3`. */
        EMPTY_NODE,

        /** Node A has no `/Type` (only its `/Kids` make it a node) and says `/Count 1`. */
        UNTYPED_NODE,
    }

    /** The point where page [index] of [pageTree] has its black mark, 30 points wide, in user space (its center). */
    fun pageMark(index: Int) = (40.0 + 40 * index) to 35.0

    /** The page sizes of [pageTree], in order: A4, letter, A5, A6. */
    val PAGE_TREE_SIZES = listOf(PDRectangle.A4, PDRectangle.LETTER, PDRectangle.A5, PDRectangle.A6)

    /**
     * A root with the nodes A (pages 1 and 2) and B (pages 3 and 4), each page of its own size and with a black mark
     * at [pageMark] of its index, and the flaw [flaw].
     */
    fun pageTree(flaw: PageTreeFlaw): Path =
        TestPdfs.build("page-tree-${flaw.name.lowercase()}.pdf") { document ->
            val root = document.pages.cosObject
            val pages =
                PAGE_TREE_SIZES.mapIndexed { index, size ->
                    PDPage(size).also { page ->
                        PDPageContentStream(document, page).use {
                            val (x, y) = pageMark(index)
                            it.addRect(x.toFloat() - 15, y.toFloat() - 15, 30f, 30f)
                            it.fill()
                        }
                    }
                }

            fun node(
                kids: List<PDPage>,
                count: Int,
            ) = COSDictionary().also { node ->
                node.setItem(COSName.TYPE, COSName.PAGES)
                node.setItem(COSName.PARENT, root)
                node.setItem(COSName.KIDS, array(*kids.map { it.cosObject }.toTypedArray()))
                node.setInt(COSName.COUNT, count)
                kids.forEach { it.cosObject.setItem(COSName.PARENT, node) }
            }
            val nodeA =
                node(
                    pages.subList(0, 2),
                    if (flaw in
                        setOf(PageTreeFlaw.MIDDLE_COUNT, PageTreeFlaw.UNTYPED_NODE)
                    ) {
                        1
                    } else {
                        2
                    },
                )
            val nodeB = node(pages.subList(2, 4), 2)
            if (flaw == PageTreeFlaw.KIDS_LOOP) nodeB.getCOSArray(COSName.KIDS).add(root)
            if (flaw == PageTreeFlaw.NUMBER_KID) nodeA.getCOSArray(COSName.KIDS).add(1, COSInteger.get(5))
            root.setItem(COSName.KIDS, array(nodeA, nodeB))
            if (flaw == PageTreeFlaw.EMPTY_NODE) {
                val empty = dictionary(COSName.TYPE, COSName.PAGES).also { it.setInt(COSName.COUNT, 3) }
                root.getCOSArray(COSName.KIDS).add(0, empty)
            }
            if (flaw == PageTreeFlaw.UNTYPED_NODE) nodeA.removeItem(COSName.TYPE)
            root.setInt(COSName.COUNT, if (flaw == PageTreeFlaw.ROOT_COUNT) 2 else 4)
        }

    /** A link without a `/Rect`, then a valid one. */
    val linkWithoutRect: Path by lazy {
        TestPdfs.build("link-without-rect.pdf") { document ->
            TestPdfs.numberedPages(document, 1)
            val annotations = document.getPage(0).annotations
            annotations.add(
                TestPdfs.uriLink(PDRectangle(), "https://example.org/no-rect").also {
                    it.cosObject.removeItem(COSName.RECT)
                },
            )
            annotations.add(TestPdfs.uriLink(PDRectangle(10f, 10f, 10f, 10f), "https://example.org/ok"))
        }
    }

    /** One page 1,000,000 points wide and 0.01 points high. */
    val widePage: Path by lazy {
        TestPdfs.build("wide.pdf") { it.addPage(PDPage(PDRectangle(1_000_000f, 0.01f))) }
    }

    /** One page 0.01 points wide and 1,000,000 points high. */
    val tallPage: Path by lazy {
        TestPdfs.build("tall.pdf") { it.addPage(PDPage(PDRectangle(0.01f, 1_000_000f))) }
    }

    /** One page 5,000 points wide and 0 points high. */
    val zeroHeightPage: Path by lazy {
        TestPdfs.build("zero-height.pdf") { it.addPage(PDPage(PDRectangle(5000f, 0f))) }
    }

    /** One page 4,000.5 by 4,000 points: at scale 1 PDFBox makes it 4,000 by 4,000 pixels, the cap exactly. */
    val atCapPage: Path by lazy {
        TestPdfs.build("at-cap.pdf") { it.addPage(PDPage(PDRectangle(4000.5f, 4000f))) }
    }

    /**
     * An outline item named "x" (and a link on page 1 to it) whose name tree lists itself twice as its `/Kids`: a walk
     * that does not skip a node it has seen takes 2 to the power of its depth steps.
     */
    val nameTreeLoop: Path by lazy {
        TestPdfs.build("name-tree-loop.pdf") { document ->
            TestPdfs.numberedPages(document, 1)
            val dests = COSDictionary()
            dests.isDirect = false
            dests.setItem(COSName.KIDS, array(dests, dests))
            namedDocument(
                document,
                listOf("x"),
                link = true,
            ) { it.setItem(COSName.NAMES, dictionary(COSName.DESTS, dests)) }
        }
    }

    /** An item named "x" whose leaf in the name tree is [levels] levels deep (the root is level 1). */
    fun deepNameTree(levels: Int): Path =
        TestPdfs.build("deep-name-tree-$levels.pdf") { document ->
            TestPdfs.numberedPages(document, 1)
            var node = dictionary(COSName.NAMES, array(COSString("x"), fitArray(document.getPage(0))))
            repeat(levels - 1) { node = dictionary(COSName.KIDS, array(node)) }
            namedDocument(document, listOf("x")) { it.setItem(COSName.NAMES, dictionary(COSName.DESTS, node)) }
        }

    /** A name tree whose value for "x" is a dictionary with the destination under `/D`, to page 1. */
    val nameTreeDictionaryValue: Path by lazy {
        TestPdfs.build("name-tree-d.pdf") { document ->
            TestPdfs.numberedPages(document, 1)
            val value = dictionary(COSName.D, fitArray(document.getPage(0)))
            val dests = dictionary(COSName.NAMES, array(COSString("x"), value))
            namedDocument(document, listOf("x")) { it.setItem(COSName.NAMES, dictionary(COSName.DESTS, dests)) }
        }
    }

    /** The items of [oddNameTree], in order. */
    val ODD_NAMES = listOf("a", "b", "c", "e", "notAString")

    /**
     * A name tree with every flaw at once: its root's `/Kids` hold a number, a leaf, a node whose `/Names` is a number
     * and one whose `/Kids` is a number; the leaf's `/Names` pair a name (not a string) with a destination, then "a"
     * with a destination to page 1, "b" with `null`, "e" with the name "a" (a name for a name), and "c" without a
     * value. The `/Dests` dictionary of the catalog is a number.
     */
    val oddNameTree: Path by lazy {
        TestPdfs.build("odd-name-tree.pdf") { document ->
            TestPdfs.numberedPages(document, 1)
            val fit = fitArray(document.getPage(0))
            val leaf =
                dictionary(
                    COSName.NAMES,
                    array(
                        COSName.getPDFName("notAString"),
                        fit,
                        COSString("a"),
                        fit,
                        COSString("b"),
                        COSNull.NULL,
                        COSString("e"),
                        COSString("a"),
                        COSString("c"),
                    ),
                )
            val dests =
                dictionary(
                    COSName.KIDS,
                    array(
                        COSInteger.get(5),
                        leaf,
                        dictionary(COSName.NAMES, COSInteger.get(6)),
                        dictionary(COSName.KIDS, COSInteger.get(7)),
                    ),
                )
            namedDocument(document, ODD_NAMES) { catalog ->
                catalog.setItem(COSName.NAMES, dictionary(COSName.DESTS, dests))
                catalog.setItem(COSName.DESTS, COSInteger.get(3))
            }
        }
    }

    /** The catalog's `/Names` is a number; its `/Dests` dictionary has "a", to page 1. */
    val namesNotDictionary: Path by lazy {
        TestPdfs.build("names-not-dictionary.pdf") { document ->
            TestPdfs.numberedPages(document, 1)
            namedDocument(document, listOf("a")) { catalog ->
                catalog.setItem(COSName.NAMES, COSInteger.get(4))
                catalog.setItem(COSName.DESTS, dictionary(COSName.getPDFName("a"), fitArray(document.getPage(0))))
            }
        }
    }

    /** The name tree of destinations is a number, and there is no `/Dests` dictionary. */
    val destsTreeNotDictionary: Path by lazy {
        TestPdfs.build("dests-tree-not-dictionary.pdf") { document ->
            TestPdfs.numberedPages(document, 1)
            namedDocument(
                document,
                listOf("a"),
            ) { it.setItem(COSName.NAMES, dictionary(COSName.DESTS, COSInteger.get(4))) }
        }
    }

    private fun array(vararg items: COSBase) = COSArray().also { array -> items.forEach(array::add) }

    private fun dictionary(
        key: COSName,
        value: COSBase,
    ) = COSDictionary().also { it.setItem(key, value) }

    private fun fitArray(page: PDPage) = array(page.cosObject, COSName.getPDFName("Fit"))

    /** An outline item per name of [names], its entries set by [catalog], and, with [link], a link to the first name. */
    private fun namedDocument(
        document: PDDocument,
        names: List<String>,
        link: Boolean = false,
        catalog: (COSDictionary) -> Unit,
    ) {
        catalog(document.documentCatalog.cosObject)
        val outline = PDDocumentOutline()
        document.documentCatalog.documentOutline = outline
        names.forEach { outline.addLast(TestPdfs.item(it, PDNamedDestination(it))) }
        if (link) {
            val annotation = PDAnnotationLink()
            annotation.rectangle = PDRectangle(10f, 10f, 10f, 10f)
            annotation.destination = PDNamedDestination(names.first())
            document.getPage(0).annotations.add(annotation)
        }
    }

    /**
     * Page 1: a content stream marked as Flate that is not; page 2: `/Contents` is the number 5; page 3: an inline
     * image claiming 99,999 by 99,999 pixels.
     */
    val brokenPages: Path by lazy {
        TestPdfs.build("broken-pages.pdf") { document ->
            TestPdfs.numberedPages(document, 3)
            val corrupt = document.document.createCOSStream()
            corrupt.createRawOutputStream().use { it.write(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9)) }
            corrupt.setItem(COSName.FILTER, COSName.FLATE_DECODE)
            document.getPage(0).cosObject.setItem(COSName.CONTENTS, corrupt)
            document.getPage(1).cosObject.setItem(COSName.CONTENTS, COSInteger.get(5))
            val image = document.document.createCOSStream()
            image.createRawOutputStream().use {
                it.write(
                    "q 100 0 0 100 50 50 cm BI /W 99999 /H 99999 /BPC 8 /CS /G ID abc EI Q".toByteArray(
                        StandardCharsets.US_ASCII,
                    ),
                )
            }
            document.getPage(2).cosObject.setItem(COSName.CONTENTS, image)
        }
    }
}

package dev.sluide.pdf

import dev.sluide.pdf.engine.PdfSetup
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSInteger
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSNull
import org.apache.pdfbox.pdmodel.PDDestinationNameTreeNode
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDDocumentNameDictionary
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDFont
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.interactive.action.PDActionGoTo
import org.apache.pdfbox.pdmodel.interactive.action.PDActionNamed
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitHeightDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.util.Matrix
import java.nio.file.Files
import java.nio.file.Path
import java.util.Random
import kotlin.io.path.readBytes
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText

/**
 * Sample PDFs for the engine tests, built with PDFBox itself (a port of the spike's generator, ADR 0192) into one temp
 * folder per test JVM, each on first use. Text whose boxes the tests check uses LiberationSans, embedded from the
 * PDFBox jar, so its metrics are the same everywhere; [std14] uses a standard-14 font, which needs a fallback font.
 */
object TestPdfs {
    init {
        // The test task sets the property; a pitest worker JVM does not, and its tests must not write to the home folder.
        PdfSetup.init(Path.of("build", "tmp", "pdfbox-fontcache"))
        // Likewise java.awt.headless, which the test tasks set: a pitest UI worker has a display, and AWT would load
        // its X11 toolkit for the first render, which the sandbox's headless JDK lacks. It is read when AWT first
        // asks, so it holds as long as nothing rendered before.
        System.getProperties().putIfAbsent("java.awt.headless", "true")
    }

    private val folder: Path =
        Files.createTempDirectory("sluide-pdf-test").also { created ->
            Runtime.getRuntime().addShutdownHook(Thread { runCatching { created.toFile().deleteRecursively() } })
        }

    /** The font size and baseline of the first line of [text] and of the [rotated] pages, drawn from [TEXT_X]. */
    const val TEXT_SIZE = 20f
    const val TEXT_X = 72f
    const val TEXT_BASELINE = 700f
    const val FIRST_LINE = "Alpha needle omega"

    val text: Path by lazy { build("text.pdf") { textPage(it, rotation = 0) } }
    val std14: Path by lazy { build("std14.pdf", ::std14Page) }
    val rotated90: Path by lazy { build("rot90.pdf") { textPage(it, rotation = 90) } }
    val rotated180: Path by lazy { build("rot180.pdf") { textPage(it, rotation = 180) } }
    val rotated270: Path by lazy { build("rot270.pdf") { textPage(it, rotation = 270) } }
    val cropBox: Path by lazy { build("cropbox.pdf", ::cropBoxPage) }
    val cropEdges: Path by lazy { build("crop-edges.pdf", ::cropEdgesPage) }
    val rotatedOutline: Path by lazy { build("rotated-outline.pdf", ::rotatedOutlineDocument) }
    val ligature: Path by lazy { build("ligature.pdf", ::ligaturePage) }
    val directions: Path by lazy { build("directions.pdf", ::directionsPage) }
    val outline: Path by lazy { build("outline.pdf", ::outlineDocument) }
    val deepOutline: Path by lazy { build("deep-outline.pdf", ::deepOutlineDocument) }
    val loopOutline: Path by lazy { build("loop-outline.pdf", ::loopOutlineDocument) }
    val wideOutline: Path by lazy { build("wide-outline.pdf", ::wideOutlineDocument) }
    val links: Path by lazy { build("links.pdf", ::linksDocument) }
    val userPassword: Path by lazy { build("user-password.pdf") { encrypted(it, user = USER_PASSWORD) } }
    val ownerOnly: Path by lazy { build("owner-only.pdf") { encrypted(it, user = "") } }
    val zeroPages: Path by lazy { build("zero-pages.pdf") { } }
    val blank: Path by lazy { build("blank.pdf") { page(it) } }
    val huge: Path by lazy { build("huge.pdf", ::hugePage) }
    val manyPages: Path by lazy { build("many-pages.pdf", ::manyPages) }

    val truncated: Path by lazy {
        val good = text.readBytes()
        folder.resolve("truncated.pdf").also { it.writeBytes(good.copyOf(good.size * 6 / 10)) }
    }
    val notPdf: Path by lazy { folder.resolve("not-pdf.pdf").also { it.writeText("This is not a PDF, just text.\n") } }
    val empty: Path by lazy { folder.resolve("empty.pdf").also { it.writeBytes(ByteArray(0)) } }
    val missing: Path get() = folder.resolve("missing.pdf")

    const val USER_PASSWORD = "user"
    const val OWNER_PASSWORD = "owner"

    /** The fixtures every text test runs over. */
    val all: List<Path>
        get() =
            listOf(
                text,
                std14,
                rotated90,
                rotated180,
                rotated270,
                cropBox,
                ligature,
                directions,
                outline,
                links,
                ownerOnly,
                huge,
            )

    /** The width of [text] in LiberationSans at [size], in points. */
    fun liberationWidth(
        text: String,
        size: Float = TEXT_SIZE,
    ): Double = PDDocument().use { document -> liberation(document).getStringWidth(text).toDouble() / 1000 * size }

    internal fun build(
        name: String,
        content: (PDDocument) -> Unit,
    ): Path {
        val file = folder.resolve(name)
        PDDocument().use { document ->
            content(document)
            document.save(file.toFile())
        }
        return file
    }

    internal fun liberation(document: PDDocument): PDFont =
        PDDocument::class.java
            .getResourceAsStream(
                "/org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf",
            )!!
            .use {
                PDType0Font.load(document, it)
            }

    internal fun page(
        document: PDDocument,
        box: PDRectangle = PDRectangle.LETTER,
    ): PDPage = PDPage(box).also(document::addPage)

    internal fun PDPageContentStream.lines(
        font: PDFont,
        size: Float,
        x: Float,
        y: Float,
        vararg lines: String,
    ) {
        beginText()
        setFont(font, size)
        setLeading(size * 1.3f)
        newLineAtOffset(x, y)
        lines.forEach { line ->
            showText(line)
            newLine()
        }
        endText()
    }

    /** [FIRST_LINE] at [TEXT_X], [TEXT_BASELINE], a second line, and a link over the first line. */
    private fun textPage(
        document: PDDocument,
        rotation: Int,
    ) {
        val page = page(document)
        page.rotation = rotation
        val font = liberation(document)
        PDPageContentStream(
            document,
            page,
        ).use { it.lines(font, TEXT_SIZE, TEXT_X, TEXT_BASELINE, FIRST_LINE, "second line") }
        page.annotations.add(uriLink(PDRectangle(TEXT_X, 690f, 200f, 30f), "https://example.org/rot$rotation"))
    }

    private fun std14Page(document: PDDocument) {
        val page = page(document, PDRectangle.A4)
        PDPageContentStream(document, page).use {
            it.lines(
                PDType1Font(Standard14Fonts.FontName.HELVETICA),
                14f,
                72f,
                770f,
                "Helvetica: needle in a haystack",
                "two  spaces",
            )
            it.lines(PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN), 12f, 72f, 690f, "Times: second needle")
            it.beginText()
            it.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 12f)
            it.newLineAtOffset(72f, 560f)
            it.showText("LeftColumn")
            it.newLineAtOffset(250f, 0f)
            it.showText("RightColumn")
            it.endText()
        }
    }

    /** The crop box: x 100..400, y 150..500 of a letter page. */
    val CROP_BOX = PDRectangle(100f, 150f, 300f, 350f)

    private fun cropBoxPage(document: PDDocument) {
        val page = page(document)
        page.cropBox = CROP_BOX
        val font = liberation(document)
        PDPageContentStream(document, page).use {
            it.lines(font, 14f, 110f, 480f, "needle in cropbox")
            it.lines(font, 14f, 20f, 700f, "Outside cropbox")
            // Starts inside the crop box and runs past its right edge.
            it.lines(font, 14f, 380f, 300f, "Straddling")
        }
        page.annotations.add(uriLink(PDRectangle(110f, 475f, 100f, 20f), "https://example.org/crop"))
    }

    /**
     * "K" inside the [CROP_BOX], "S" across its left edge, "M" across its top edge, and four letters whose boxes only touch it from outside: "R" starts at its right edge,
     * "T" (drawn downwards) at its bottom edge, "B" (drawn upwards) ends at its top edge and "L" (drawn leftwards) at
     * its left edge.
     */
    private fun cropEdgesPage(document: PDDocument) {
        val page = page(document)
        page.cropBox = CROP_BOX
        val font = liberation(document)
        PDPageContentStream(document, page).use {
            for (
            (letter, angle, start) in
            listOf(
                Triple("K", 0, 200f to 300f),
                Triple("S", 0, 95f to 300f),
                Triple("M", 0, 250f to 495f),
                Triple("R", 0, 400f to 300f),
                Triple("T", 270, 250f to 150f),
                Triple("B", 90, 250f to 500f),
                Triple("L", 180, 100f to 300f),
            )
            ) {
                it.beginText()
                it.setFont(font, 14f)
                it.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(angle.toDouble()), start.first, start.second))
                it.showText(letter)
                it.endText()
            }
        }
    }

    private fun ligaturePage(document: PDDocument) {
        val page = page(document)
        val font = liberation(document)
        PDPageContentStream(document, page).use { it.lines(font, TEXT_SIZE, TEXT_X, TEXT_BASELINE, "\uFB01nd it") }
    }

    /** The point each word of [directionsPage] starts at, and the angle of its text, counter-clockwise. */
    val DIRECTION_WORDS =
        listOf(
            Triple("north", 0, 100f to 600f),
            Triple("west", 90, 400f to 300f),
            Triple(
                "south",
                180,
                500f to 600f,
            ),
            Triple("east", 270, 200f to 300f),
        )

    private fun directionsPage(document: PDDocument) {
        val page = page(document)
        val font = liberation(document)
        PDPageContentStream(document, page).use {
            for ((word, angle, start) in DIRECTION_WORDS) {
                it.beginText()
                it.setFont(font, TEXT_SIZE)
                it.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(angle.toDouble()), start.first, start.second))
                it.showText(word)
                it.endText()
            }
        }
    }

    internal fun fit(page: PDPage): PDPageDestination = PDPageFitDestination().also { it.page = page }

    internal fun goTo(destination: PDDestination) = PDActionGoTo().also { it.destination = destination }

    internal fun item(
        title: String,
        destination: PDDestination? = null,
        action: PDActionGoTo? = null,
    ) = PDOutlineItem().also { item ->
        item.title = title
        destination?.let { item.destination = it }
        action?.let { item.action = it }
    }

    internal fun numberedPages(
        document: PDDocument,
        count: Int,
    ) {
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        repeat(count) { index ->
            PDPageContentStream(document, page(document)).use { it.lines(font, 24f, 72f, 700f, "Page ${index + 1}") }
        }
    }

    /** The top of the XYZ destination of "1.1" (page 2) and of the FitH destination of "7" (page 2). */
    const val XYZ_TOP = 400
    const val FIT_WIDTH_TOP = 600

    private fun outlineDocument(document: PDDocument) {
        numberedPages(document, 6)
        val chapter3 =
            PDPageXYZDestination().also {
                it.page = document.getPage(2)
                it.top = 500
            }
        val names = PDDocumentNameDictionary(document.documentCatalog)
        names.dests = PDDestinationNameTreeNode().also { it.names = mapOf("chapter3" to chapter3) }
        document.documentCatalog.names = names
        // Named destinations in the old style: a /Dests dictionary with name keys.
        document.documentCatalog.cosObject.setItem(
            COSName.DESTS,
            COSDictionary().also { it.setItem(COSName.getPDFName("chapter5"), fit(document.getPage(4))) },
        )
        val outline = PDDocumentOutline()
        document.documentCatalog.documentOutline = outline
        val first = item("1 Explicit", fit(document.getPage(0)))
        val nested =
            item(
                "1.1 XYZ",
                PDPageXYZDestination().also {
                    it.page = document.getPage(1)
                    it.top = XYZ_TOP
                },
            )
        nested.addLast(item("1.1.1 GoTo", action = goTo(fit(document.getPage(3)))))
        first.addLast(nested)
        val oldStyle = item("3 Named in Dests")
        oldStyle.cosObject.setName(COSName.DEST, "chapter5")
        val byNumber = item("5 Page number")
        byNumber.cosObject.setItem(
            COSName.DEST,
            COSArray().also {
                it.add(COSInteger.get(5))
                it.add(COSName.getPDFName("Fit"))
            },
        )
        val untitled = item("")
        untitled.cosObject.removeItem(COSName.TITLE)
        untitled.destination = fit(document.getPage(0))
        val badDestination = item("10 Bad destination")
        badDestination.cosObject.setItem(COSName.DEST, COSInteger.get(5))
        val outOfRange = item("8 Page number out of range")
        outOfRange.cosObject.setItem(
            COSName.DEST,
            COSArray().also {
                it.add(COSInteger.get(6))
                it.add(COSName.getPDFName("Fit"))
            },
        )
        listOf(
            first,
            item("2 Named in tree", PDNamedDestination("chapter3")),
            oldStyle,
            item("4 GoTo named", action = goTo(PDNamedDestination("chapter3"))),
            byNumber,
            item("6 Missing", PDNamedDestination("nowhere")),
            item(
                "7 FitH",
                PDPageFitWidthDestination().also {
                    it.page = document.getPage(1)
                    it.top = FIT_WIDTH_TOP
                },
            ),
            outOfRange,
            item(
                "9 XYZ above the page",
                PDPageXYZDestination().also {
                    it.page = document.getPage(0)
                    it.top = 2000
                },
            ),
            badDestination,
            untitled,
        ).forEach(outline::addLast)
        first.openNode()
        outline.openNode()
    }

    /** The XYZ destination of the first item of [rotatedOutline], on its page rotated by 90 degrees. */
    const val ROTATED_LEFT = 100
    const val ROTATED_TOP = 500

    /**
     * A page rotated by 90 degrees: an XYZ destination with a left and a top, one that keeps both (`null`), one with a
     * name where its top should be, and a FitV destination (a left only). Then a page rotated by 270 degrees: a FitH
     * destination (a top only) and an XYZ destination with a left and a top. Then a page upside down: a FitH
     * destination.
     */
    private fun rotatedOutlineDocument(document: PDDocument) {
        numberedPages(document, 3)
        document.getPage(0).rotation = 90
        document.getPage(1).rotation = 270
        document.getPage(2).rotation = 180
        val outline = PDDocumentOutline()
        document.documentCatalog.documentOutline = outline
        val xyz =
            PDPageXYZDestination().also {
                it.page = document.getPage(0)
                it.left = ROTATED_LEFT
                it.top = ROTATED_TOP
            }
        outline.addLast(item("XYZ", xyz))
        val keeping = item("XYZ null")
        keeping.cosObject.setItem(
            COSName.DEST,
            COSArray().also { array ->
                array.add(document.getPage(0).cosObject)
                array.add(COSName.getPDFName("XYZ"))
                repeat(3) { array.add(COSNull.NULL) }
            },
        )
        outline.addLast(keeping)
        val named = item("XYZ with a name as top")
        named.cosObject.setItem(
            COSName.DEST,
            COSArray().also { array ->
                array.add(document.getPage(0).cosObject)
                array.add(COSName.getPDFName("XYZ"))
                array.add(COSNull.NULL)
                array.add(COSName.getPDFName("Top"))
                array.add(COSNull.NULL)
            },
        )
        outline.addLast(named)
        outline.addLast(
            item(
                "FitV on 90",
                PDPageFitHeightDestination().also {
                    it.page = document.getPage(0)
                    it.left = ROTATED_LEFT
                },
            ),
        )
        outline.addLast(
            item(
                "FitH on 270",
                PDPageFitWidthDestination().also {
                    it.page = document.getPage(1)
                    it.top = ROTATED_TOP
                },
            ),
        )
        outline.addLast(
            item(
                "XYZ on 270",
                PDPageXYZDestination().also {
                    it.page = document.getPage(1)
                    it.left = ROTATED_LEFT
                    it.top = ROTATED_TOP
                },
            ),
        )
        outline.addLast(
            item(
                "FitH on 180",
                PDPageFitWidthDestination().also {
                    it.page = document.getPage(2)
                    it.top = ROTATED_TOP
                },
            ),
        )
    }

    /** Items nested [DEEP_OUTLINE_LEVELS] deep. */
    const val DEEP_OUTLINE_LEVELS = 300

    private fun deepOutlineDocument(document: PDDocument) {
        numberedPages(document, 1)
        val outline = PDDocumentOutline()
        document.documentCatalog.documentOutline = outline
        var parent: PDOutlineItem? = null
        repeat(DEEP_OUTLINE_LEVELS) { level ->
            val item = item("Level $level", fit(document.getPage(0)))
            parent?.addLast(item) ?: outline.addLast(item)
            parent = item
        }
    }

    /** "Child" has "Parent" as its own first child: a loop. */
    private fun loopOutlineDocument(document: PDDocument) {
        numberedPages(document, 1)
        val outline = PDDocumentOutline()
        document.documentCatalog.documentOutline = outline
        val parent = item("Parent", fit(document.getPage(0)))
        val child = item("Child", fit(document.getPage(0)))
        parent.addLast(child)
        outline.addLast(parent)
        outline.addLast(item("Sibling", fit(document.getPage(0))))
        child.cosObject.setItem(COSName.FIRST, parent.cosObject)
        child.cosObject.setItem(COSName.LAST, parent.cosObject)
    }

    const val WIDE_OUTLINE_ITEMS = 12_000

    private fun wideOutlineDocument(document: PDDocument) {
        numberedPages(document, 1)
        val outline = PDDocumentOutline()
        document.documentCatalog.documentOutline = outline
        repeat(WIDE_OUTLINE_ITEMS) { outline.addLast(item("Item $it", fit(document.getPage(0)))) }
    }

    /** The URIs of the URI links of [links], in order; only the first three are web links the engine keeps. */
    val LINK_URIS =
        listOf(
            "https://example.org/a",
            "mailto:someone@example.org",
            "HTTP://EXAMPLE.ORG/upper",
            "javascript:alert(1)",
            "file:///etc/passwd",
            "ftp://example.org/file",
            "relative/page.html",
            "not a uri",
        )

    /** The rectangle (user space) of link [index] on the first page of [links]. */
    fun linkRect(index: Int) = PDRectangle(68f, 740f - index * 30f, 220f, 20f)

    internal fun uriLink(
        rect: PDRectangle,
        uri: String,
    ) = PDAnnotationLink().also { link ->
        link.rectangle = rect
        link.action = PDActionURI().also { it.uri = uri }
    }

    /**
     * Page 1 of 3: the [LINK_URIS], then a GoTo action to page 3, an explicit destination to page 2, a named one to
     * page 3, a named one that is missing, a Named action (NextPage), a link without a target, one whose destination
     * is a number (no valid destination), a URI action without a URI and another explicit destination to page 2.
     */
    private fun linksDocument(document: PDDocument) {
        numberedPages(document, 3)
        val names = PDDocumentNameDictionary(document.documentCatalog)
        names.dests = PDDestinationNameTreeNode().also { it.names = mapOf("target" to fit(document.getPage(2))) }
        document.documentCatalog.names = names
        val first = document.getPage(0)
        LINK_URIS.forEachIndexed { index, uri -> first.annotations.add(uriLink(linkRect(index), uri)) }
        val others =
            listOf<(PDAnnotationLink) -> Unit>(
                { it.action = goTo(fit(document.getPage(2))) },
                { it.destination = fit(document.getPage(1)) },
                { it.destination = PDNamedDestination("target") },
                { it.destination = PDNamedDestination("nowhere") },
                { it.action = PDActionNamed().also { named -> named.n = "NextPage" } },
                { },
                { it.cosObject.setItem(COSName.DEST, COSInteger.get(5)) },
                { it.action = PDActionURI() },
                { it.destination = fit(document.getPage(1)) },
            )
        others.forEachIndexed { offset, setUp ->
            first.annotations.add(
                PDAnnotationLink().also {
                    it.rectangle = linkRect(LINK_URIS.size + offset)
                    setUp(it)
                },
            )
        }
    }

    private fun encrypted(
        document: PDDocument,
        user: String,
    ) {
        val page = page(document, PDRectangle.A4)
        PDPageContentStream(
            document,
            page,
        ).use { it.lines(liberation(document), 14f, 72f, 770f, "Secret text: needle") }
        val permission =
            AccessPermission().also {
                it.setCanExtractContent(false)
                it.setCanPrint(false)
            }
        document.protect(
            StandardProtectionPolicy(OWNER_PASSWORD, user, permission).also { it.encryptionKeyLength = 256 },
        )
    }

    /** 200 by 200 inches. */
    const val HUGE_SIDE = 14400f

    private fun hugePage(document: PDDocument) {
        val page = page(document, PDRectangle(HUGE_SIDE, HUGE_SIDE))
        PDPageContentStream(document, page).use {
            it.lines(liberation(document), 400f, 500f, 13000f, "Huge page")
            it.setLineWidth(50f)
            it.addRect(100f, 100f, 14200f, 14200f)
            it.stroke()
        }
    }

    const val MANY_PAGES = 500

    private fun manyPages(document: PDDocument) {
        val random = Random(1)
        val words = "alpha beta gamma delta epsilon zeta eta theta iota kappa lambda needle omicron".split(" ")
        val font = PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN)
        repeat(MANY_PAGES) { index ->
            PDPageContentStream(document, page(document, PDRectangle.A4)).use { stream ->
                stream.beginText()
                stream.setFont(font, 10f)
                stream.setLeading(13f)
                stream.newLineAtOffset(50f, 800f)
                stream.showText("Page ${index + 1}")
                stream.newLine()
                repeat(58) {
                    stream.showText((1..14).joinToString(" ") { words[random.nextInt(words.size)] })
                    stream.newLine()
                }
                stream.endText()
            }
        }
    }
}

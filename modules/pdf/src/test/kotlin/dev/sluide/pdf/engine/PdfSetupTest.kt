package dev.sluide.pdf.engine

import dev.sluide.pdf.TestPdfs
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PdfSetupTest {
    // Set by the test task (and by TestPdfs for pitest workers); every test leaves it as it found it.
    private val property: String? = System.getProperty(PdfSetup.FONT_CACHE_PROPERTY)

    @TempDir
    lateinit var temp: Path

    @AfterEach
    fun restore() {
        if (property == null) {
            System.clearProperty(PdfSetup.FONT_CACHE_PROPERTY)
        } else {
            System.setProperty(PdfSetup.FONT_CACHE_PROPERTY, property)
        }
    }

    @Test
    fun `init points PDFBox's font cache at the folder and creates it`() {
        System.clearProperty(PdfSetup.FONT_CACHE_PROPERTY)
        val folder = temp.resolve("cache").resolve("pdfbox")
        PdfSetup.init(folder)
        assertEquals(folder.toString(), System.getProperty(PdfSetup.FONT_CACHE_PROPERTY))
        assertTrue(folder.isDirectory())
    }

    @Test
    fun `init keeps a font cache folder already set, and creates that one`() {
        val set = temp.resolve("set")
        System.setProperty(PdfSetup.FONT_CACHE_PROPERTY, set.toString())
        PdfSetup.init(temp.resolve("other"))
        assertEquals(set.toString(), System.getProperty(PdfSetup.FONT_CACHE_PROPERTY))
        assertTrue(set.isDirectory())
    }

    @Test
    fun `a folder that cannot be created leaves init without an error`() {
        System.clearProperty(PdfSetup.FONT_CACHE_PROPERTY)
        val file = temp.resolve("file").also { it.toFile().writeText("") }
        PdfSetup.init(file.resolve("cache"))
        assertEquals(file.resolve("cache").toString(), System.getProperty(PdfSetup.FONT_CACHE_PROPERTY))
    }

    @Test
    fun `the default font cache folder is in Thoreau's cache folder`() {
        assertEquals(
            Path.of(System.getProperty("user.home"), ".sluide", "cache", "pdfbox"),
            PdfSetup.defaultCacheFolder(),
        )
    }

    @Test
    fun `PDFBox logs only errors`() {
        Logger.getLogger("org.apache.pdfbox").level = Level.ALL
        PdfSetup.init(temp)
        assertEquals(Level.SEVERE, Logger.getLogger("org.apache.pdfbox").level)
    }

    @Test
    fun `PDFBox writes its font cache into the configured folder`() {
        // A standard-14 font is not embedded, so drawing it makes PDFBox look up the system fonts through its cache.
        (PdfDocument.open(TestPdfs.std14) as PdfOpenResult.Opened).document.use { assertNotNull(it.render(0, 0.5)) }
        val folder = Path.of(assertNotNull(System.getProperty(PdfSetup.FONT_CACHE_PROPERTY)))
        assertTrue(folder.resolve(".pdfbox.cache").isRegularFile(), "font cache in $folder")
    }
}

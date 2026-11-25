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
    fun `PDFBox and FontBox log only errors`() {
        val names = listOf("org.apache.pdfbox", "org.apache.fontbox")
        names.forEach { Logger.getLogger(it).level = Level.ALL }
        PdfSetup.init(temp)
        assertEquals(listOf(Level.SEVERE, Level.SEVERE), names.map { Logger.getLogger(it).level })
    }

    @Test
    fun `after init PDFBox writes its font cache into the folder, not into the home folder`() {
        // PDFBox reads the property once per JVM, when it first maps a font: a fresh JVM shows what the app does.
        val cache = temp.resolve("cache")
        val home = temp.resolve("home").also { it.toFile().mkdirs() }
        val java = Path.of(System.getProperty("java.home"), "bin", "java").toString()
        val process =
            ProcessBuilder(
                java,
                "-Djava.awt.headless=true",
                "-Duser.home=$home",
                "-cp",
                System.getProperty("java.class.path"),
                FontCacheMain::class.java.name,
                cache.toString(),
                TestPdfs.std14.toString(),
            ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)
        assertTrue(cache.resolve(".pdfbox.cache").isRegularFile(), "font cache in $cache: $output")
        assertEquals(emptyList(), home.toFile().list()!!.toList())
    }
}

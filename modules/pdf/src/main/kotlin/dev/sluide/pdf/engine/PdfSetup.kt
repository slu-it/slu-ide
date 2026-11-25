package dev.sluide.pdf.engine

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Prepares PDFBox before its first use (ADR 0192): call [init] before a [PdfDocument] is opened, once or more.
 *
 * PDFBox scans the system fonts once and keeps what it found in `.pdfbox.cache`, by default in the home folder. The
 * system property `pdfbox.fontcache` moves it; PDFBox reads the property when it first maps a font and falls back to
 * the home folder when the folder is missing, so [init] creates it.
 */
object PdfSetup {
    /** The system property PDFBox reads for the folder of its font cache. */
    const val FONT_CACHE_PROPERTY = "pdfbox.fontcache"

    // JUL keeps loggers weakly: without these references the levels set below could be collected with their loggers.
    private val loggers: List<Logger> = listOf("org.apache.pdfbox", "org.apache.fontbox").map(Logger::getLogger)

    /** `~/.sluide/cache/pdfbox`, beside the other caches of Thoreau. */
    fun defaultCacheFolder(): Path = Path.of(System.getProperty("user.home"), ".sluide", "cache", "pdfbox")

    /**
     * Sets [FONT_CACHE_PROPERTY] to [cacheFolder] unless it is set already (tests set it), creates the folder the
     * property names, and lets the loggers of PDFBox and FontBox through only at ERROR: PDFBox warns once per font it
     * replaces with a fallback font. Under Logback (the app) the level comes from `logback-spring.xml`; this sets it for
     * `java.util.logging`, which PDFBox's commons-logging uses when no SLF4J is present (tests).
     */
    fun init(cacheFolder: Path) {
        val set = System.getProperty(FONT_CACHE_PROPERTY)
        val folder =
            if (set != null) {
                Path.of(set)
            } else {
                cacheFolder.also { System.setProperty(FONT_CACHE_PROPERTY, it.toString()) }
            }
        try {
            Files.createDirectories(folder)
        } catch (_: IOException) {
            // PDFBox then keeps its cache in the home folder or the temp folder, and works as before.
        }
        loggers.forEach { it.level = Level.SEVERE }
    }
}

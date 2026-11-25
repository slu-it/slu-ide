package dev.sluide.pdf.engine

import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Run in a JVM of its own by `PdfSetupTest`: calls [PdfSetup.init] with the folder in `args[0]`, then renders the
 * first page of the PDF in `args[1]`, whose standard-14 font makes PDFBox build its font cache.
 */
object FontCacheMain {
    @JvmStatic
    fun main(args: Array<String>) {
        System.clearProperty(PdfSetup.FONT_CACHE_PROPERTY)
        PdfSetup.init(Path.of(args[0]))
        val result = PdfDocument.open(Path.of(args[1]))
        if (result !is PdfOpenResult.Opened) exitProcess(2)
        result.document.use { if (it.render(0, 0.5) == null) exitProcess(3) }
    }
}

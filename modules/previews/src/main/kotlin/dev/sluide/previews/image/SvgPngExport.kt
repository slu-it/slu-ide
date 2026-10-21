package dev.sluide.previews.image

import dev.sluide.previews.preview.FileExport
import dev.sluide.previews.preview.writeReplacing
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.nameWithoutExtension

/**
 * The SVG as a PNG beside it, drawn by [rasterizer] at [scale] times its own size (the preview's 100%) on a transparent
 * background: `logo.svg` gives `logo.png` at scale 1, otherwise `logo@2x.png`, `logo@3x.png` and so on.
 * It draws the saved file's bytes rather than the saved text: they are the same content, but in the file's own encoding,
 * which the XML declaration may name (`encoding="ISO-8859-1"`).
 */
internal class SvgPngExport(
    val scale: Int,
    private val rasterizer: SvgRasterizer = WebViewSvgRasterizer(),
) : FileExport {
    override val label get() = "PNG ${scale * 100}%"

    override fun target(file: Path): Path =
        file.resolveSibling(file.nameWithoutExtension + (if (scale == 1) "" else "@${scale}x") + ".png")

    override fun write(
        file: Path,
        text: String,
        target: Path,
    ): List<String> {
        val image = rasterizer.rasterize(Files.readAllBytes(file), scale)
        writeReplacing(target) { if (!ImageIO.write(image, "png", it.toFile())) throw IOException("No PNG writer") }
        return emptyList()
    }
}

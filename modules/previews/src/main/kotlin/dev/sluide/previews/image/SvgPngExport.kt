package dev.sluide.previews.image

import dev.sluide.previews.preview.FileExport
import dev.sluide.previews.preview.writeReplacing
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.io.path.nameWithoutExtension

/**
 * The SVG as a PNG beside it, drawn by [rasterizer] at [size] on a transparent background. A [PngSize.Scale] is a
 * multiple of its own size (the preview's 100%): `logo.svg` gives `logo.png` at 1, otherwise `logo@2x.png`, `logo@3x.png` and so on. A
 * [PngSize.Pixels] gives `logo-32x32.png`, so that several sizes coexist.
 * It draws the saved file's bytes rather than the saved text: they are the same content, but in the file's own encoding,
 * which the XML declaration may name (`encoding="ISO-8859-1"`).
 */
internal class SvgPngExport(
    val size: PngSize,
    private val rasterizer: SvgRasterizer = WebViewSvgRasterizer(),
) : FileExport {
    override val label get() =
        when (size) {
            is PngSize.Scale -> "PNG ${size.factor * 100}%"
            is PngSize.Pixels -> "PNG ${size.width} × ${size.height} px"
        }

    override fun target(file: Path): Path =
        file.resolveSibling(
            file.nameWithoutExtension +
                when (size) {
                    is PngSize.Scale -> if (size.factor == 1) "" else "@${size.factor}x"
                    is PngSize.Pixels -> "-${size.width}x${size.height}"
                } + ".png",
        )

    override fun write(
        file: Path,
        text: String,
        target: Path,
    ): List<String> {
        val image = rasterizer.rasterize(Files.readAllBytes(file), size)
        writeReplacing(target) { if (!ImageIO.write(image, "png", it.toFile())) throw IOException("No PNG writer") }
        return emptyList()
    }
}

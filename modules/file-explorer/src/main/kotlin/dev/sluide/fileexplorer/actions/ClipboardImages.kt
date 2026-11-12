package dev.sluide.fileexplorer.actions

import dev.sluide.fileexplorer.ClipboardImage
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.Clock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

/** [image] as PNG, lossless and with its transparency (ADR 0167); fails with an [IOException] if it cannot be encoded. */
internal fun pngBytes(image: ClipboardImage): ByteArray {
    val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    buffered.setRGB(0, 0, image.width, image.height, image.argb, 0, image.width)
    val out = ByteArrayOutputStream()
    if (!ImageIO.write(buffered, "png", out)) throw IOException("No PNG writer is available.")
    return out.toByteArray()
}

private val STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss")

/** The name offered for a pasted image: `image-` and the local time of [clock], then `.png`. */
internal fun defaultImageName(clock: Clock): String = "image-${STAMP.format(LocalDateTime.now(clock))}.png"

/** [name] with `.png` appended unless it ends with it already, in any case (`shot.jpg` becomes `shot.jpg.png`). */
internal fun withPngExtension(name: String): String =
    if (name.endsWith(".png", ignoreCase = true)) name else "$name.png"

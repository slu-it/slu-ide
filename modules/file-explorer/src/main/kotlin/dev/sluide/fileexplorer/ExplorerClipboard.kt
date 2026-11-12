package dev.sluide.fileexplorer

import javafx.beans.property.ReadOnlyIntegerProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.scene.image.PixelFormat
import javafx.scene.input.Clipboard
import javafx.scene.input.DataFormat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.file.Path

/** A raw image from the clipboard: [argb] holds [width] x [height] non-premultiplied ARGB pixels, row by row. */
internal class ClipboardImage(
    val width: Int,
    val height: Int,
    val argb: IntArray,
)

/** What a paste would take from the clipboard: its [files], or, without files, an image if [pastesImage]. */
internal class PasteContent(
    val files: List<Path>,
    val pastesImage: Boolean,
)

/** The image on the clipboard could not be read; the message is meant for the user. */
internal class UnreadableClipboardImage : IOException("The clipboard image could not be read.")

/** The files and the image on a clipboard; used on the JavaFX thread. */
internal interface ClipboardFiles {
    /** The files on the clipboard; none if it holds something else. */
    fun files(): List<Path>

    /** Whether the clipboard holds an image (which may still turn out unreadable), whatever else it holds. */
    fun hasImage(): Boolean

    /** The pixels of the image on the clipboard; `null` if there is none; fails with [UnreadableClipboardImage]. */
    fun image(): ClipboardImage?

    /** Puts [files] on the clipboard, replacing what it held. */
    fun put(files: List<Path>)

    fun clear()
}

/**
 * The system clipboard: files as [DataFormat.FILES], which file managers (Finder and others) paste and copy, and their
 * absolute paths as text, one per line, for anything that takes text.
 */
internal object SystemClipboardFiles : ClipboardFiles {
    override fun files(): List<Path> {
        val clipboard = Clipboard.getSystemClipboard()
        return if (clipboard.hasFiles()) clipboard.files.map { it.toPath() } else emptyList()
    }

    override fun hasImage(): Boolean = Clipboard.getSystemClipboard().hasImage()

    override fun image(): ClipboardImage? {
        val clipboard = Clipboard.getSystemClipboard()
        if (!clipboard.hasImage()) return null
        val image = clipboard.image
        val width = image?.width?.toInt() ?: 0
        val height = image?.height?.toInt() ?: 0
        val reader = image?.pixelReader
        if (image == null || image.isError || width <= 0 || height <= 0 || reader == null) {
            throw UnreadableClipboardImage()
        }
        val argb = IntArray(width * height)
        reader.getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width)
        return ClipboardImage(width, height, argb)
    }

    override fun put(files: List<Path>) {
        Clipboard.getSystemClipboard().setContent(
            mapOf(
                DataFormat.FILES to files.map(Path::toFile),
                DataFormat.PLAIN_TEXT to files.joinToString("\n"),
            ),
        )
    }

    override fun clear() = Clipboard.getSystemClipboard().clear()
}

/**
 * Copy, cut and paste of explorer entries over a clipboard (ADR 0092). Copy and Cut put the same files on it; Cut also
 * marks them as cut, so a paste moves them instead of copying. The mark holds only while the clipboard holds exactly
 * these files: anything else put there (no change event exists) drops it when it is next checked ([checkCut]). One
 * instance (a singleton bean) is shared by the explorers of all windows, so entries cut in one window move when
 * pasted in another. Used on the JavaFX thread.
 */
@Component
class ExplorerClipboard internal constructor(
    private val clipboard: ClipboardFiles,
) {
    /** On the system clipboard. */
    @Autowired
    constructor() : this(SystemClipboardFiles)

    private var cut: Set<Path> = emptySet()
    private val changes = SimpleIntegerProperty()

    /** Counts changes of the cut entries, e.g. to dim their rows. */
    internal val cutChanges: ReadOnlyIntegerProperty get() = changes

    /** The files on the clipboard, normalised; none if it holds something else. */
    internal fun files(): List<Path> = clipboard.files().map(::normal)

    /**
     * What a paste would take, from one read of the clipboard: its files, normalised, and, only when there are none,
     * whether it holds an image (files win, ADR 0167).
     */
    internal fun pasteContent(): PasteContent {
        val files = files()
        return PasteContent(files, files.isEmpty() && clipboard.hasImage())
    }

    /** The image on the clipboard, `null` if none; fails with [UnreadableClipboardImage]. */
    internal fun image(): ClipboardImage? = clipboard.image()

    /** Puts [paths] on the clipboard to be copied; ends a cut. */
    internal fun copy(paths: List<Path>) {
        clipboard.put(paths.map(::normal))
        mark(emptySet())
    }

    /** Puts [paths] on the clipboard and marks them as cut, so that a paste moves them. */
    internal fun cut(paths: List<Path>) {
        val normal = paths.map(::normal)
        clipboard.put(normal)
        mark(normal.toSet())
    }

    /** Whether [path] is marked as cut; call [checkCut] first to see a clipboard changed elsewhere. */
    internal fun isCut(path: Path): Boolean = cut.isNotEmpty() && path in cut

    /**
     * The cut entries if the clipboard still holds exactly them, else none, dropping the mark; [files] are the
     * clipboard's files when they were just read ([pasteContent]), so that it is not asked again.
     */
    internal fun checkCut(files: List<Path>? = null): Set<Path> {
        if (cut.isNotEmpty() && (files ?: files()).toSet() != cut) mark(emptySet())
        return cut
    }

    /** Ends a cut: drops the mark, and clears the clipboard if it still holds the cut entries; whether there was one. */
    internal fun cancelCut(): Boolean {
        if (cut.isEmpty()) return false
        if (files().toSet() == cut) clipboard.clear()
        mark(emptySet())
        return true
    }

    /** Ends a cut whose entries were pasted: the mark and the clipboard are cleared. */
    internal fun cutPasted() {
        clipboard.clear()
        mark(emptySet())
    }

    private fun mark(paths: Set<Path>) {
        if (paths == cut) return
        cut = paths
        changes.set(changes.get() + 1)
    }

    private fun normal(path: Path): Path = path.toAbsolutePath().normalize()
}

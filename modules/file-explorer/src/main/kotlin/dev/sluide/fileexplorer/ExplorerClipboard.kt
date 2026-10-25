package dev.sluide.fileexplorer

import javafx.beans.property.ReadOnlyIntegerProperty
import javafx.beans.property.SimpleIntegerProperty
import javafx.scene.input.Clipboard
import javafx.scene.input.DataFormat
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.nio.file.Path

/** The files on a clipboard; used on the JavaFX thread. */
internal interface ClipboardFiles {
    /** The files on the clipboard; none if it holds something else. */
    fun files(): List<Path>

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

    /** The cut entries if the clipboard still holds exactly them, else none, dropping the mark. */
    internal fun checkCut(): Set<Path> {
        if (cut.isNotEmpty() && files().toSet() != cut) mark(emptySet())
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

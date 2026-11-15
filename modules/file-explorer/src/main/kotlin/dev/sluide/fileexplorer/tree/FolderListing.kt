package dev.sluide.fileexplorer.tree

import dev.sluide.fileexplorer.generated.GeneratedFolderFilter
import dev.sluide.fileexplorer.generated.GeneratedReason
import java.nio.file.Path

/** What the explorer lists in a folder ([listFolder]). */
internal class FolderListing(
    /** The entries shown, in the tree's order ([listDirectory]), each with its effective generated reason. */
    val shown: List<Pair<Path, GeneratedReason?>>,
    /** The generated folders and files among all entries, shown or hidden, with their structural reason. */
    val candidates: Map<Path, GeneratedReason>,
)

/**
 * The entries of [folder] the explorer lists, the one rule of the tree and of everything that must agree with it: all
 * entries of [listDirectory], without the generated ones [generated] hides (ADR 0158). Without [generated] (no hiding,
 * or a folder inside a generated folder, where nothing is classified) every entry is shown without a reason.
 */
internal fun listFolder(
    folder: Path,
    generated: GeneratedFolderFilter?,
): FolderListing {
    val paths = listDirectory(folder)
    if (generated == null) return FolderListing(paths.map { it to null }, emptyMap())
    val names = paths.mapTo(HashSet()) { it.fileName.toString() }
    val candidates = paths.mapNotNull { path -> generated.structural(path, names)?.let { path to it } }.toMap()
    val shown =
        paths.mapNotNull { path ->
            val why = candidates[path]?.let { generated.effective(path, it) }
            if (generated.isHidden(path, why)) null else path to why
        }
    return FolderListing(shown, candidates)
}

package dev.sluide.fileexplorer.tree

import java.nio.file.Path

/**
 * Which entries the explorer shows while a search narrows its tree to [matches], absolute paths as the tree holds them,
 * below the top-level folders [roots]. A matched folder (a folder hit) shows its whole content. Entries the explorer
 * selects itself while searching are [keep]t as extras, with the folders holding them, until [resetExtras]; a kept folder
 * shows its whole content too (a folder hit renamed to a name that no longer matches stays full). FX thread.
 */
internal class TreeSearchFilter(
    matches: Collection<Path>,
    roots: Collection<Path>,
) {
    private val roots: Set<Path> = roots.toSet()
    private val matches: Set<Path> = matches.toSet()

    /** The folders holding a match, up to its top-level folder. */
    private val holders: Set<Path> = matches.flatMapTo(HashSet()) { ancestors(it) }

    private val extras = LinkedHashSet<Path>()

    /** The [extras] and the folders holding them. */
    private val shownExtras = HashSet<Path>()

    /** The entries passed to [keep] since the last [resetExtras], in order. */
    val kept: Set<Path> get() = extras

    /**
     * Whether [entry] shows: a root, a match, an ancestor of a match, an extra (or ancestor of one), or below a folder hit
     * or a kept folder.
     */
    fun shows(entry: Path): Boolean =
        entry in roots ||
            entry in matches ||
            entry in holders ||
            entry in shownExtras ||
            ancestors(entry).any { it in matches || it in extras }

    /** Whether [row] is a folder hit: any folder of its chain is a match. */
    fun isFolderHit(row: FileTreeItem): Boolean = row.isDirectory && row.chain.any { it in matches }

    /** Adds [path] as an extra, with its ancestors up to its root. */
    fun keep(path: Path) {
        extras.add(path)
        shownExtras.add(path)
        shownExtras.addAll(ancestors(path))
    }

    fun resetExtras() {
        extras.clear()
        shownExtras.clear()
    }

    /** The folders holding [path] up to the outermost root holding it; none outside every root. */
    private fun ancestors(path: Path): Sequence<Path> =
        generateSequence(path.parent) { it.parent }.takeWhile { folder -> roots.any { folder.startsWith(it) } }
}

package dev.sluide.fileexplorer.tree

import java.nio.file.Path

/**
 * Which entries the explorer shows while a search narrows its tree to [matches], absolute paths as the tree holds them,
 * below the top-level folders [roots]. A matched folder holding no further match (an open hit) shows its whole content;
 * one holding further matches is like any folder holding one: it shows only those, their folders and extras. Entries the
 * explorer selects itself while searching are [keep]t as extras, with the folders holding them, until [resetExtras]; a
 * kept folder follows the same rule (a folder hit renamed to a name that no longer matches stays full). FX thread.
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
     * Whether [entry] shows: a root, a match, an ancestor of a match, an extra (or ancestor of one), or below a match or
     * a kept folder that holds no match (an open hit).
     */
    fun shows(entry: Path): Boolean =
        entry in roots ||
            entry in matches ||
            entry in holders ||
            entry in shownExtras ||
            ancestors(entry).any { (it in matches || it in extras) && it !in holders }

    /**
     * Whether [row] is an open hit, shown collapsed with its whole content: any folder of its chain is a match and no
     * match lies below its last folder.
     */
    fun isFolderHit(row: FileTreeItem): Boolean = row.isDirectory && row.chain.any { it in matches } && !holdsMatch(row)

    /** Whether a match lies below the last folder of [row], so it shows expanded with only the matches inside. */
    fun holdsMatch(row: FileTreeItem): Boolean = row.isDirectory && row.value in holders

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

package dev.sluide.fileexplorer.tree

import dev.sluide.fileexplorer.FolderIcons
import dev.sluide.fileexplorer.generated.GeneratedFolderFilter
import dev.sluide.fileexplorer.generated.GeneratedReason
import dev.sluide.librarycode.LibraryKind
import javafx.beans.property.ReadOnlyObjectProperty
import javafx.beans.property.ReadOnlyObjectWrapper
import javafx.beans.property.SimpleObjectProperty
import javafx.collections.ObservableList
import javafx.scene.control.TreeItem
import java.nio.file.Files
import java.nio.file.Path

/**
 * A tree node for a file or folder. The children of a folder are read from disk the first
 * time they are requested, so deep trees and symlink cycles are never traversed eagerly.
 * [onChildrenLoaded] is called when that happens, for this item and every descendant.
 *
 * While [compact] says so, a child folder whose only entry is a folder is shown as one row with it (ADR 0096): its
 * [chain] holds the folders from the first one, the entry in this folder, down to the deepest one, its value.
 */
internal class FileTreeItem(
    path: Path,
    /** Shown instead of the name of the folder, for a top-level folder with a given name; `null` for the others. */
    val label: String? = null,
    /** Icons for this folder and the folders below it, if they differ from the normal ones; inherited by children. */
    val folderIcons: FolderIcons? = null,
    /** The folders this row stands for, from the entry in its parent folder down to [path]; just [path] by default. */
    val chain: List<Path> = listOf(path),
    /** Whether middle folders below this item are compacted; read whenever its children are listed, inherited. */
    private val compact: () -> Boolean = { false },
    /** Called after [refresh] added rows to this folder, e.g. to watch their middle folders; inherited. */
    private val onChildrenAdded: (FileTreeItem) -> Unit = {},
    /** Tells whether a folder is a library root (one file lookup); asked once for each folder below a top-level one; inherited. */
    private val libraryRoots: (Path) -> LibraryKind? = { null },
    /** Whether [libraryKind] is looked up: false for top-level folders, the project and extra roots (ADR 0150). */
    private val detectLibrary: Boolean = false,
    /** Leaves generated folders out of the children and gives their rows a reason (ADR 0158); inherited. */
    private val generated: GeneratedFolderFilter? = null,
    private val onChildrenLoaded: (FileTreeItem) -> Unit = {},
) : TreeItem<Path>(path) {
    private val directory: Boolean = Files.isDirectory(path)

    /**
     * The kind of library root this row is, `null` for files, top-level folders and others; the cells listen to it. Found
     * when the item is created and when [refresh] of this folder or its parent runs, over the whole [chain] (a
     * compacted `__pypackages__/3.12` row is a library root).
     */
    val libraryKindProperty = SimpleObjectProperty<LibraryKind?>(readLibraryKind())

    val libraryKind: LibraryKind? get() = libraryKindProperty.value

    private fun readLibraryKind(): LibraryKind? {
        if (!directory || !detectLibrary) return null
        return chain.firstNotNullOfOrNull { libraryRoots(it) }
    }

    /** Looks the library kind up again, e.g. after a `pyvenv.cfg` was created or deleted. */
    private fun recheckLibrary() {
        libraryKindProperty.value = readLibraryKind()
    }

    private val reason = ReadOnlyObjectWrapper<GeneratedReason?>(null)

    /**
     * Why this row is a generated folder or file, shown although it is one (the setting is off, or it was revealed);
     * `null` for top-level rows and entries that are not generated. Updated when the parent lists again and on
     * [gitIgnoresChanged].
     */
    val generatedReasonProperty: ReadOnlyObjectProperty<GeneratedReason?> get() = reason.readOnlyProperty

    val generatedReason: GeneratedReason? get() = reason.value

    /** The generated folders and files among the entries of this folder at its last listing, with their structural reason. */
    private var candidates: Map<Path, GeneratedReason> = emptyMap()

    private var childrenLoaded = false

    init {
        require(chain.last() == path) { "The chain $chain must end with $path" }
    }

    /** Whether this row stands for several folders (`src/main/kotlin`). */
    val isCompacted: Boolean get() = chain.size > 1

    /**
     * The entry this row stands for as the source of a delete, move, cut or copy: the first folder of its [chain], the
     * entry in its parent folder; its value for a row that is not compacted.
     */
    val sourcePath: Path get() = chain.first()

    /** The name shown: the [label], the names of the [chain] joined by `/`, or the name of the entry. */
    val displayName: String
        get() = label ?: chain.joinToString("/") { (it.fileName ?: it).toString() }

    /** Whether [path] is one of the folders of this row (its value, or a middle folder of a compacted row). */
    fun represents(path: Path): Boolean = path in chain

    /** Whether this is a folder; decided when the item is created. */
    val isDirectory: Boolean get() = directory

    /**
     * Cached result of the explorer's check whether this file can be opened (it may read the file); `null` until it
     * is checked, and again after the file changed. The cells listen to it to update their icon.
     */
    internal val readable = SimpleObjectProperty<Boolean?>(null)

    override fun isLeaf(): Boolean = !directory

    /** Whether the children of this folder have been read. */
    val isLoaded: Boolean get() = childrenLoaded

    override fun getChildren(): ObservableList<TreeItem<Path>> {
        val children = super.getChildren()
        if (directory && !childrenLoaded) {
            childrenLoaded = true
            val compacting = compact()
            children.setAll(listShown().map { (path, why) -> child(chainOf(path, compacting), why) })
            onChildrenLoaded(this)
        }
        return children
    }

    /**
     * Reads the folder again if its children were loaded. Unchanged children are kept as they are (with their
     * expansion, loaded children and selection); new entries are inserted in sort order and gone ones removed. A child
     * is matched by the entry in this folder ([sourcePath]); one whose chain changed (a new entry in a middle folder
     * splits a compacted row, a folder left with one subfolder joins it) is replaced, with the folders that were
     * expanded expanded again ([expandedFolders]).
     */
    fun refresh() {
        if (!childrenLoaded) return
        recheckLibrary()
        val children = super.getChildren()
        val current = children.filterIsInstance<FileTreeItem>().associateBy { it.sourcePath }
        val compacting = compact()
        val replaced = mutableListOf<Pair<Set<Path>, FileTreeItem>>()
        val listed =
            listShown().map { (path, why) ->
                val existing = current[path]
                val chain = chainOf(path, compacting)
                when {
                    existing == null -> {
                        child(chain, why)
                    }

                    // A file that became a folder (or the other way round) needs a new item, as does another chain.
                    existing.directory != Files.isDirectory(chain.last()) || existing.chain != chain -> {
                        child(chain, why).also { replaced += existing.expandedFolders() to it }
                    }

                    else -> {
                        existing.also {
                            it.recheckLibrary()
                            it.setReason(why)
                        }
                    }
                }
            }
        val keep = listed.toSet()
        children.removeIf { it !in keep }
        var added = false
        listed.forEachIndexed { index, item ->
            if (children.getOrNull(index) !== item) {
                children.add(index, item)
                added = true
            }
        }
        // Only now, in the tree, so that the folders loaded on the way are watched.
        replaced.forEach { (expanded, item) -> item.expandAgain(expanded) }
        if (added) onChildrenAdded(this)
    }

    /** [refresh] for this folder and every loaded folder below it, e.g. after compacting was turned on or off. */
    fun refreshTree() {
        refresh()
        if (childrenLoaded) super.getChildren().filterIsInstance<FileTreeItem>().forEach(FileTreeItem::refreshTree)
    }

    /**
     * Whether this row no longer stands for the folders it would be built of now (compacting turned on, a middle folder
     * got another entry, the deepest one now holds only a folder); never for a top-level folder.
     */
    fun isChainStale(): Boolean = parent is FileTreeItem && chainOf(sourcePath, compact()) != chain

    /**
     * The folders of this row and of the rows shown below it that are expanded, every folder of their chains; empty if
     * this row is collapsed.
     */
    private fun expandedFolders(): Set<Path> =
        if (!isExpanded) {
            emptySet()
        } else {
            chain.toSet() +
                if (childrenLoaded) {
                    super.getChildren().filterIsInstance<FileTreeItem>().flatMap { it.expandedFolders() }
                } else {
                    emptySet()
                }
        }

    /** Expands this row if all folders of its chain are in [expanded], and the same for its children then. */
    private fun expandAgain(expanded: Set<Path>) {
        if (!directory || !expanded.containsAll(chain)) return
        isExpanded = true
        children.filterIsInstance<FileTreeItem>().forEach { it.expandAgain(expanded) }
    }

    /**
     * Forgets [readable] of the files among the children that are in [changed], or of all of them if this folder is
     * (events were lost): they may have become readable or binary.
     */
    fun forgetReadability(changed: Collection<Path>) {
        val all = value in changed
        super
            .getChildren()
            .filterIsInstance<FileTreeItem>()
            .filter { !it.directory && (all || it.value in changed) }
            .forEach { it.readable.value = null }
    }

    /** This item and all descendants whose children are loaded, parents before their children. */
    fun loadedFolders(): List<FileTreeItem> =
        if (!childrenLoaded) {
            emptyList()
        } else {
            listOf(this) + super.getChildren().filterIsInstance<FileTreeItem>().flatMap { it.loadedFolders() }
        }

    /** Whether this folder is generated or inside a generated folder: nothing below it is classified. */
    private fun insideGenerated(): Boolean =
        generatedReason != null || (parent as? FileTreeItem)?.insideGenerated() == true

    /** The entries of this folder to show with their reason; remembers the generated ones as [candidates]. */
    private fun listShown(): List<Pair<Path, GeneratedReason?>> {
        val paths = listDirectory(value)
        val filter = generated
        if (filter == null || insideGenerated()) {
            candidates = emptyMap()
            return paths.map { it to null }
        }
        val names = paths.mapTo(HashSet()) { it.fileName.toString() }
        candidates = paths.mapNotNull { path -> filter.structural(path, names)?.let { path to it } }.toMap()
        return paths.mapNotNull { path ->
            val why = candidates[path]?.let { filter.effective(path, it) }
            if (filter.isHidden(path, why)) null else path to why
        }
    }

    /** Whether [entry], an entry of this folder, was a generated folder or file at the last listing (shown or hidden). */
    fun isGeneratedCandidate(entry: Path): Boolean = entry in candidates

    /**
     * Whether [entry] is a catalogue file directly inside a middle folder of this compacted row (not its last folder):
     * hidden files do not stop compacting, so the row has no child for it until it is revealed (ADR 0163).
     */
    fun hasHiddenFileInMiddle(entry: Path): Boolean =
        isCompacted && generated?.isCatalogueFile(entry) == true && entry.parent in chain.dropLast(1)

    /** Sets the reason; a folder that became or stopped being generated classifies its loaded content again. */
    private fun setReason(why: GeneratedReason?) {
        val changed = (reason.value == null) != (why == null)
        reason.value = why
        if (changed) refreshTree()
    }

    /**
     * Checks the generated folders and files of this folder and the loaded folders below it against git again, after
     * its status changed: lists a folder again if one of its generated entries becomes shown or hidden, else only
     * updates the reasons of the shown ones.
     */
    fun gitIgnoresChanged() {
        if (!childrenLoaded) return
        val filter = generated
        if (filter != null && candidates.isNotEmpty()) {
            val rows = super.getChildren().filterIsInstance<FileTreeItem>().associateBy { it.sourcePath }
            val reasons = candidates.mapValues { (path, why) -> filter.effective(path, why) }
            if (reasons.any { (path, why) -> (path in rows) == filter.isHidden(path, why) }) {
                refresh()
            } else {
                reasons.forEach { (path, why) -> rows[path]?.setReason(why) }
            }
        }
        super.getChildren().filterIsInstance<FileTreeItem>().forEach(FileTreeItem::gitIgnoresChanged)
    }

    /** Lists again every loaded folder below this one that has one of [entries] as a row or candidate, to hide them. */
    fun refreshParentsOf(entries: Set<Path>) {
        if (!childrenLoaded || entries.isEmpty()) return
        val rows = super.getChildren().filterIsInstance<FileTreeItem>()
        // A compacted row that was split to show a revealed file joins again.
        if (rows.any { it.sourcePath in entries || it.isChainStale() } ||
            candidates.keys.any { it in entries }
        ) {
            refresh()
        }
        super.getChildren().filterIsInstance<FileTreeItem>().forEach { it.refreshParentsOf(entries) }
    }

    private fun chainOf(
        path: Path,
        compacting: Boolean,
    ): List<Path> =
        if (compacting) compactChain(path) { generated?.hidesFileForCompacting(it) == true } else listOf(path)

    private fun child(
        chain: List<Path>,
        why: GeneratedReason?,
    ) = FileTreeItem(
        chain.last(),
        folderIcons = folderIcons,
        chain = chain,
        compact = compact,
        onChildrenAdded = onChildrenAdded,
        onChildrenLoaded = onChildrenLoaded,
        libraryRoots = libraryRoots,
        detectLibrary = true,
        generated = generated,
    ).also { it.reason.value = why }
}

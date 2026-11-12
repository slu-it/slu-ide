package dev.sluide.codereview.model

import dev.sluide.codereview.ReviewFile

/**
 * A node of the files tree: a folder ([file] `null`, [path] the folder's path, [name] perhaps a compacted chain like
 * `src/main/kotlin`) or a file ([path] its path, [name] its last segment).
 */
internal data class ReviewTreeNode(
    val name: String,
    val path: String,
    val children: List<ReviewTreeNode>,
    val file: ReviewFile?,
)

/** Whether [path] passes the files filter: it contains [filter], ignoring case. */
internal fun matchesReviewFilter(
    path: String,
    filter: String,
): Boolean = path.contains(filter, ignoreCase = true)

/**
 * The top-level nodes of the tree of [files] that pass [filter] ([matchesReviewFilter]): on each level folders before
 * files, both by name ignoring case (then by case), and chains of folders with a single folder child compacted into
 * one node.
 */
internal fun reviewTree(
    files: List<ReviewFile>,
    filter: String = "",
): List<ReviewTreeNode> {
    val root = Folder("", "")
    for (file in files) {
        if (!matchesReviewFilter(file.path, filter)) continue
        val segments = file.path.split('/')
        var folder = root
        for (segment in segments.dropLast(1)) {
            val path = if (folder.path.isEmpty()) segment else "${folder.path}/$segment"
            folder = folder.folders.getOrPut(segment) { Folder(segment, path) }
        }
        folder.files += ReviewTreeNode(segments.last(), file.path, emptyList(), file)
    }
    return root.children()
}

/** The files of [nodes] in tree order, depth first. */
internal fun treeFiles(nodes: List<ReviewTreeNode>): List<ReviewFile> =
    nodes.flatMap { node -> listOfNotNull(node.file) + treeFiles(node.children) }

/** [files] in the order of their tree. */
internal fun treeOrder(files: List<ReviewFile>): List<ReviewFile> = treeFiles(reviewTree(files))

private val byName: Comparator<String> = String.CASE_INSENSITIVE_ORDER.thenComparing(naturalOrder())

/** A folder while the tree is built. */
private class Folder(
    val name: String,
    val path: String,
) {
    val folders = HashMap<String, Folder>()
    val files = ArrayList<ReviewTreeNode>()

    /** The sorted child nodes, folders first. */
    fun children(): List<ReviewTreeNode> =
        folders.values.sortedWith(compareBy(byName) { it.name }).map { it.node() } +
            files.sortedWith(compareBy(byName) { it.name })

    /** This folder as a node, compacted with its single child folder. */
    fun node(): ReviewTreeNode {
        var folder = this
        var name = this.name
        while (folder.files.isEmpty() && folder.folders.size == 1) {
            folder = folder.folders.values.single()
            name += "/${folder.name}"
        }
        return ReviewTreeNode(name, folder.path, folder.children(), null)
    }
}

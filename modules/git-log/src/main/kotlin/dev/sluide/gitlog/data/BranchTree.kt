package dev.sluide.gitlog.data

import dev.sluide.git.GitRef
import dev.sluide.git.GitRefKind
import dev.sluide.git.GitRefs
import dev.sluide.git.GitStash

/**
 * A node of the branches pane's tree (ADR 0040). [path] identifies it across rebuilds, e.g. to keep it collapsed:
 * `Local`, `Local/feature`, `Remote/origin`, `Remote/origin/fix/a`; `HEAD` for the `HEAD` node.
 */
internal sealed interface BranchNode {
    val name: String
    val path: String
}

/** A node that holds others: a group, a remote or a folder. */
internal sealed interface BranchParent : BranchNode {
    val children: List<BranchNode>
}

/** One of the top groups: Local, Remote or Tags. */
internal data class BranchGroup(
    override val name: String,
    override val children: List<BranchNode>,
) : BranchParent {
    override val path: String get() = name
}

/** A remote below Remote, holding its branches. */
internal data class BranchRemote(
    override val name: String,
    override val path: String,
    override val children: List<BranchNode>,
) : BranchParent

/** A folder of branch names (`feature` of `feature/a`), possibly several compacted into one (`team/feature`). */
internal data class BranchFolder(
    override val name: String,
    override val path: String,
    override val children: List<BranchNode>,
) : BranchParent

/**
 * A branch, remote branch, tag or `HEAD` itself. [current] marks the checked-out local branch (and `HEAD` when it is
 * attached to it).
 */
internal data class BranchLeaf(
    override val name: String,
    override val path: String,
    val ref: GitRef,
    val current: Boolean = false,
) : BranchNode

/**
 * A stash below Stashes (ADR 0107), named by its message as git lists it; no ref, so it neither navigates nor filters
 * the log (stash commits are not in it).
 */
internal data class BranchStash(
    val stash: GitStash,
) : BranchNode {
    override val name: String get() = stash.message
    override val path: String get() = "$STASHES/${stash.commit}"
}

internal const val LOCAL = "Local"
internal const val REMOTE = "Remote"
internal const val TAGS = "Tags"
internal const val STASHES = "Stashes"

/**
 * The tree of the branches pane for [refs], as IntelliJ shows it (ADR 0040): `HEAD (Current Branch)` (or `HEAD
 * (detached at <short hash>)`), then the groups Local, Remote (one node per remote) and Tags, each left out when it
 * would be empty, and the [stashes] in their order (newest first) in Stashes (ADR 0107), left out without any. Names
 * with `/` are grouped into folders; a folder holding only one folder and no branches is one node (`team/feature`). In
 * every node its folders come first, then its branches, each by name, ignoring case first.
 *
 * A remote is the first segment of a remote branch's name, so remote names with a `/` are split wrongly; a remote
 * branch without a `/` (git-svn's `refs/remotes/trunk`) is a leaf directly below Remote.
 */
internal fun branchNodes(
    refs: GitRefs,
    stashes: List<GitStash> = emptyList(),
): List<BranchNode> {
    val head =
        refs.refs.firstOrNull { it.kind == GitRefKind.HEAD }?.let { ref ->
            val name =
                if (refs.currentBranch != null) {
                    "HEAD (Current Branch)"
                } else {
                    "HEAD (detached at ${ref.commit.take(SHORT_HASH)})"
                }
            BranchLeaf(name, "HEAD", ref, current = refs.currentBranch != null)
        }
    val locals = refs.refs.filter { it.kind == GitRefKind.LOCAL_BRANCH }
    val remotes = refs.refs.filter { it.kind == GitRefKind.REMOTE_BRANCH }
    val tags = refs.refs.filter { it.kind == GitRefKind.TAG }

    val local = BranchGroup(LOCAL, folderTree(LOCAL, locals.map { it.shortName to it }, refs.currentBranch))
    val (plain, named) = remotes.partition { '/' !in it.shortName }
    val remoteNodes =
        named
            .groupBy { it.shortName.substringBefore('/') }
            .map { (remote, branches) ->
                val path = "$REMOTE/$remote"
                BranchRemote(remote, path, folderTree(path, branches.map { it.shortName.substringAfter('/') to it }))
            }.sortedWith(BY_NAME) +
            plain.map { BranchLeaf(it.shortName, "$REMOTE/${it.shortName}", it) }.sortedWith(BY_NAME)
    val remote = BranchGroup(REMOTE, remoteNodes)
    val tag = BranchGroup(TAGS, folderTree(TAGS, tags.map { it.shortName to it }))
    val stash = BranchGroup(STASHES, stashes.map(::BranchStash))
    return listOfNotNull(head) + listOf(local, remote, tag, stash).filter { it.children.isNotEmpty() }
}

/**
 * The nodes of [nodes] whose leaves' short names (`feature/a`, `origin/main`, `v1.0`, `HEAD`) or stashes' messages
 * contain [text], ignoring case, with the parents they are in; parents without such leaves are left out. A blank text
 * keeps everything.
 */
internal fun filterBranches(
    nodes: List<BranchNode>,
    text: String,
): List<BranchNode> {
    val wanted = text.trim()
    if (wanted.isEmpty()) return nodes
    return nodes.mapNotNull { it.filtered(wanted) }
}

private fun BranchNode.filtered(text: String): BranchNode? =
    when (this) {
        is BranchLeaf -> {
            takeIf { ref.shortName.contains(text, ignoreCase = true) }
        }

        is BranchStash -> {
            takeIf { stash.message.contains(text, ignoreCase = true) }
        }

        is BranchParent -> {
            val kept = children.mapNotNull { it.filtered(text) }
            if (kept.isEmpty()) {
                null
            } else {
                when (this) {
                    is BranchGroup -> copy(children = kept)
                    is BranchRemote -> copy(children = kept)
                    is BranchFolder -> copy(children = kept)
                }
            }
        }
    }

/** The leaves below (and including) [nodes], in tree order; not the stashes. */
internal fun branchLeaves(nodes: List<BranchNode>): List<BranchLeaf> =
    nodes.flatMap { node ->
        when (node) {
            is BranchLeaf -> listOf(node)
            is BranchStash -> emptyList()
            is BranchParent -> branchLeaves(node.children)
        }
    }

/** The [refs] by their [names][Pair.first] as folders and leaves below the node at [path]. */
private fun folderTree(
    path: String,
    refs: List<Pair<String, GitRef>>,
    currentBranch: String? = null,
): List<BranchNode> {
    val root = NameFolder()
    for ((name, ref) in refs) {
        val segments = name.split('/')
        var folder = root
        for (segment in segments.dropLast(1)) folder = folder.folders.getOrPut(segment, ::NameFolder)
        folder.leaves += segments.last() to ref
    }
    return root.nodes(path, currentBranch)
}

private class NameFolder {
    val folders = LinkedHashMap<String, NameFolder>()
    val leaves = mutableListOf<Pair<String, GitRef>>()

    fun nodes(
        path: String,
        currentBranch: String?,
    ): List<BranchNode> =
        folders.map { (name, folder) -> folder.compacted(name, path, currentBranch) }.sortedWith(BY_NAME) +
            leaves
                .map { (name, ref) -> BranchLeaf(name, "$path/$name", ref, ref.name == currentBranch) }
                .sortedWith(BY_NAME)

    /** This folder as a node named [name] below [parent], with the folders below it that hold only one joined in. */
    fun compacted(
        name: String,
        parent: String,
        currentBranch: String?,
    ): BranchFolder {
        var compacted = name
        var folder = this
        while (folder.leaves.isEmpty() && folder.folders.size == 1) {
            val (child, below) = folder.folders.entries.single()
            compacted = "$compacted/$child"
            folder = below
        }
        val path = "$parent/$compacted"
        return BranchFolder(compacted, path, folder.nodes(path, currentBranch))
    }
}

private val BY_NAME = compareBy<BranchNode, String>(String.CASE_INSENSITIVE_ORDER) { it.name }.thenBy { it.name }

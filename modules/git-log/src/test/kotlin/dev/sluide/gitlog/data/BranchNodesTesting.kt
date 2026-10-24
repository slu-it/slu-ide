package dev.sluide.gitlog.data

/** The node with [path] anywhere in the tree of [nodes], `null` if there is none. */
internal fun findNode(
    nodes: List<BranchNode>,
    path: String,
): BranchNode? =
    nodes.firstNotNullOfOrNull { node ->
        if (node.path == path) node else (node as? BranchParent)?.children?.let { findNode(it, path) }
    }

/** The node with [path] anywhere in the tree of [nodes]; fails if there is none. */
internal fun nodeAt(
    nodes: List<BranchNode>,
    path: String,
): BranchNode = checkNotNull(findNode(nodes, path)) { path }

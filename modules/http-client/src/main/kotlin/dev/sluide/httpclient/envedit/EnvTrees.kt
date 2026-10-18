package dev.sluide.httpclient.envedit

import dev.sluide.httpclient.env.Environments
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * Where the value at a path of one environment comes from, following the merge of the files ([Environments.load]):
 * objects merge key by key, anything else is taken whole from one file (the private one when both have it).
 *
 * [public] and [private] are the nodes of each file that make up the value. [owner] is the file the value is taken
 * from whole, set at segment [ownerDepth] of the path (the first non-object on the way); it is null while the path
 * runs through merged objects. [overridesPublic] when the private file replaced a public value there.
 */
internal data class Location(
    val public: JsonNode?,
    val private: JsonNode?,
    val owner: FileSide?,
    val ownerDepth: Int,
    val overridesPublic: Boolean,
) {
    val defined: Boolean get() = public != null || private != null

    /** The merged value, null if not defined. */
    val value: JsonNode?
        get() =
            if (public is ObjectNode && private is ObjectNode) {
                public.deepCopy().also { Environments.merge(it, private) }
            } else {
                private ?: public
            }

    fun node(side: FileSide): JsonNode? = if (side == FileSide.PUBLIC) public else private

    companion object {
        val NONE = Location(null, null, null, -1, false)
    }
}

/** The parsed public and private files (objects of environments), with navigation and the edits of the model. */
internal class EnvTrees(
    val public: ObjectNode,
    val private: ObjectNode,
) {
    fun copy() = EnvTrees(public.deepCopy(), private.deepCopy())

    fun file(side: FileSide): ObjectNode = if (side == FileSide.PUBLIC) public else private

    /** The top-level keys of both files, public first, `$` keys included. */
    val topLevelKeys: List<String> get() = (public.propertyNames() + private.propertyNames()).distinct()

    val environments: List<String> get() = topLevelKeys.filterNot { it.startsWith("$") }

    /** Environments that are not an object in one of the files. */
    val readOnlyEnvironments: Set<String>
        get() =
            environments
                .filter { name ->
                    FileSide.entries.any { file(it).get(name)?.let { node -> node !is ObjectNode } == true }
                }.toSet()

    private fun envObject(
        side: FileSide,
        env: String,
    ): ObjectNode? = file(side).get(env) as? ObjectNode

    /** The node at [path] in one file, walking that file only. */
    fun nodeAt(
        side: FileSide,
        env: String,
        path: ValuePath,
    ): JsonNode? = path.segments.fold(envObject(side, env) as JsonNode?) { node, segment -> child(node, segment) }

    fun locate(
        env: String,
        path: ValuePath,
    ): Location {
        var public: JsonNode? = envObject(FileSide.PUBLIC, env)
        var private: JsonNode? = envObject(FileSide.PRIVATE, env)
        var owner: FileSide? = null
        var ownerDepth = -1
        var overrides = false
        for ((depth, segment) in path.segments.withIndex()) {
            if (owner == FileSide.PUBLIC) {
                public = child(public, segment)
                continue
            }
            if (owner == FileSide.PRIVATE) {
                private = child(private, segment)
                continue
            }
            if (segment !is Segment.Key) return Location.NONE
            public = child(public, segment)
            private = child(private, segment)
            when {
                public is ObjectNode && private is ObjectNode -> {}

                private != null && public != null -> {
                    owner = FileSide.PRIVATE
                    overrides = true
                    public = null
                }

                private != null && private !is ObjectNode -> {
                    owner = FileSide.PRIVATE
                }

                public != null && public !is ObjectNode -> {
                    owner = FileSide.PUBLIC
                }
            }
            if (owner != null) ownerDepth = depth
        }
        return Location(public, private, owner, ownerDepth, overrides)
    }

    /**
     * Writes [value] at [path] in one file, creating the environment and missing objects on the way. Throws
     * [IllegalStateException] if something on the way is not an object (or a list for an index), or an index is
     * beyond the end of its list (the index just past the end appends).
     */
    fun put(
        side: FileSide,
        env: String,
        path: ValuePath,
        value: JsonNode,
    ) {
        val root = file(side)
        if (path.isRoot) {
            root.set(env, value)
            return
        }
        var node: JsonNode =
            root.get(env) ?: EnvJson.nodes.objectNode().also { root.set(env, it) }
        for ((depth, segment) in path.segments.withIndex()) {
            val last = depth == path.size - 1
            when (segment) {
                is Segment.Key -> {
                    val container =
                        node as? ObjectNode ?: error("${describe(side, env, path.take(depth))} is not an object")
                    if (last) {
                        container.set(segment.name, value)
                    } else {
                        node =
                            container.get(segment.name)
                                ?: EnvJson.nodes.objectNode().also { container.set(segment.name, it) }
                    }
                }

                is Segment.Index -> {
                    val list = node as? ArrayNode ?: error("${describe(side, env, path.take(depth))} is not a list")
                    check(
                        segment.index in 0..list.size(),
                    ) { "${describe(side, env, path)} is beyond the end of its list" }
                    if (last) {
                        if (segment.index == list.size()) list.add(value) else list.set(segment.index, value)
                    } else {
                        node = list.get(segment.index) ?: error("${describe(side, env, path)} does not exist")
                    }
                }
            }
        }
    }

    /**
     * Removes the value at [path] (or the whole environment for the root) from one file, then the objects on the way
     * that became empty as long as the other file still has them ([prune]). False if there was nothing to remove.
     */
    fun delete(
        side: FileSide,
        env: String,
        path: ValuePath,
    ): Boolean {
        if (path.isRoot) return file(side).remove(env) != null
        val parent = nodeAt(side, env, path.parent)
        val last = path.last
        val removed =
            when {
                parent is ObjectNode && last is Segment.Key -> parent.remove(last.name)
                parent is ArrayNode && last is Segment.Index && last.index < parent.size() -> parent.remove(last.index)
                else -> null
            }
        if (removed == null) return false
        prune(side, env, path.parent)
        return true
    }

    /**
     * [delete] from the private file, then from the public one: objects that became empty in both stay in the public
     * file, where they were defined first.
     */
    fun deleteFromBoth(
        env: String,
        path: ValuePath,
    ) {
        delete(FileSide.PRIVATE, env, path)
        delete(FileSide.PUBLIC, env, path)
    }

    /**
     * Removes the empty object at [path] from one file, and so on upwards, while the other file has an object at the
     * same place, so that nothing disappears from the merged view, or while it is `Security`, `Security.Auth` or
     * `SSLConfiguration`, which mean nothing empty. Environments are pruned from the private file only (and only by the
     * first rule); nothing inside lists is pruned.
     */
    private fun prune(
        side: FileSide,
        env: String,
        path: ValuePath,
    ) {
        if (path.segments.any { it is Segment.Index }) return
        var current = path
        while (true) {
            val node = nodeAt(side, env, current)
            if (node !is ObjectNode || !node.isEmpty) return
            if (current !in EnvSchema.STRUCTURE && nodeAt(side.other, env, current) !is ObjectNode) return
            if (current.isRoot) {
                if (side == FileSide.PRIVATE) file(side).remove(env)
                return
            }
            (nodeAt(side, env, current.parent) as ObjectNode).remove((current.last as Segment.Key).name)
            current = current.parent
        }
    }

    private fun child(
        node: JsonNode?,
        segment: Segment,
    ): JsonNode? =
        when (segment) {
            is Segment.Key -> (node as? ObjectNode)?.get(segment.name)
            is Segment.Index -> (node as? ArrayNode)?.get(segment.index)
        }

    private fun describe(
        side: FileSide,
        env: String,
        path: ValuePath,
    ): String {
        val file = side.fileName
        return if (path.isRoot) "$file: environment \"$env\"" else "$file: \"$path\" of environment \"$env\""
    }
}

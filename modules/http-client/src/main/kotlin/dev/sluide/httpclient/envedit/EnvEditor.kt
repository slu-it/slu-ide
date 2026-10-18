package dev.sluide.httpclient.envedit

import dev.sluide.httpclient.env.EnvKeys
import dev.sluide.httpclient.env.EnvKeys.Auth
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ArrayNode
import tools.jackson.databind.node.ObjectNode

/**
 * The edits of an [EnvDocument], applied to a copy of its [trees]. Arguments are checked by the document; what cannot
 * be written without losing something (an object on the way is not one in the file) throws [IllegalStateException].
 */
internal class EnvEditor(
    private val trees: EnvTrees,
) {
    fun setValue(
        env: String,
        path: ValuePath,
        value: JsonNode,
        side: FileSide?,
    ) {
        require(!path.isRoot) { "Set the values of an environment, not the environment" }
        EnvSchema.certificatePart(path)?.let { return setCertificatePath(env, it, value) }
        EnvSchema.formatPart(path)?.let { return setCertificateFormat(env, it, formatOf(value)) }
        if (EnvSchema.isCertificate(path)) return trees.put(FileSide.PRIVATE, env, path, value)
        val location = trees.locate(env, path)
        val target =
            when {
                location.owner != null -> location.owner
                location.defined -> throw IllegalArgumentException("\"$path\" is an object; set its entries")
                else -> side ?: newValueSide(env, path)
            }
        trees.put(target, env, path, value)
    }

    /**
     * The file a new value at [path] goes to: the one the schema says ([EnvSchema.schemaSide]), else the file of the
     * nearest object on its way that either file defines (the environment object at the latest): the list's file
     * inside a list, the public file if it has the object (or neither has the environment), else the private file.
     * So new values of an environment only the private file has stay there.
     */
    private fun newValueSide(
        env: String,
        path: ValuePath,
    ): FileSide {
        EnvSchema.schemaSide(path)?.let { return it }
        var ancestor = path.parent
        while (true) {
            val location = trees.locate(env, ancestor)
            location.owner?.let { return it }
            if (location.defined || ancestor.isRoot) {
                return if (location.public == null && location.private != null) FileSide.PRIVATE else FileSide.PUBLIC
            }
            ancestor = ancestor.parent
        }
    }

    fun remove(
        env: String,
        path: ValuePath,
    ) {
        require(!path.isRoot) { "Remove an environment with removeEnvironment" }
        EnvSchema.formatPart(path)?.let { return setCertificateFormat(env, it, null) }
        if (EnvSchema.isCertificate(path)) {
            trees.delete(FileSide.PRIVATE, env, path)
            return
        }
        val location = trees.locate(env, path)
        if (!location.defined) return
        val owner = location.owner
        if (owner != null && location.ownerDepth < path.size - 1) {
            trees.delete(owner, env, path)
        } else {
            trees.deleteFromBoth(env, path)
        }
    }

    fun setSide(
        env: String,
        path: ValuePath,
        side: FileSide,
    ) {
        require(!path.isRoot) { "An environment has no side" }
        if (EnvSchema.isCertificate(path)) {
            require(side == FileSide.PRIVATE) { "Certificate settings are read from the private file only" }
            return
        }
        val location = trees.locate(env, path)
        require(location.defined) { "\"$path\" is not defined in environment \"$env\"" }
        val owner = requireNotNull(location.owner) { "Objects have no file of their own; move their entries" }
        if (owner == side) return
        val ownerPath = path.take(location.ownerDepth + 1)
        val value = checkNotNull(trees.nodeAt(owner, env, ownerPath))
        trees.put(side, env, ownerPath, value.deepCopy())
        trees.delete(owner, env, ownerPath)
    }

    fun setType(
        env: String,
        path: ValuePath,
        type: ValueType,
    ) {
        val location = trees.locate(env, path)
        val old = location.value ?: return
        if (EnvValues.typeOf(old) == type) return
        val new = EnvValues.convert(old, type).value
        val holder =
            location.owner
                ?: FileSide.PUBLIC.takeIf { location.private == null }
                ?: FileSide.PRIVATE.takeIf { location.public == null }
        if (holder != null) {
            trees.put(holder, env, path, new)
        } else {
            val target = EnvSchema.defaultSide(path)
            trees.put(target, env, path, new)
            trees.delete(target.other, env, path)
        }
    }

    fun isLossy(
        env: String,
        path: ValuePath,
        type: ValueType,
    ): Boolean = trees.locate(env, path).value?.let { EnvValues.convert(it, type).lossy } == true

    /** Adds [name] to the object at [parent] if it exists in [env], in the file a new value goes to there. */
    fun addKey(
        env: String,
        parent: ValuePath,
        name: String,
        type: ValueType,
    ) {
        if (trees.locate(env, parent).value !is ObjectNode) return
        val path = parent.key(name)
        trees.put(newValueSide(env, path), env, path, EnvValues.emptyValue(type))
    }

    fun hasKey(
        env: String,
        parent: ValuePath,
        name: String,
    ): Boolean = (trees.locate(env, parent).value as? ObjectNode)?.has(name) == true

    fun appendListEntry(
        env: String,
        list: ValuePath,
        type: ValueType,
    ) {
        val location = trees.locate(env, list)
        val value = location.value as? ArrayNode ?: return
        trees.put(checkNotNull(location.owner), env, list.index(value.size()), EnvValues.emptyValue(type))
    }

    fun addEnvironment(name: String) {
        trees.public.set(name, EnvJson.nodes.objectNode())
    }

    fun renameEnvironment(
        old: String,
        new: String,
    ) {
        for (file in listOf(trees.public, trees.private)) {
            if (!file.has(old)) continue
            val entries = file.properties().map { it.key to it.value }
            file.removeAll()
            entries.forEach { (key, value) -> file.set(if (key == old) new else key, value) }
        }
    }

    fun removeEnvironment(name: String) {
        trees.deleteFromBoth(name, ValuePath.ROOT)
    }

    /** Whether either file has the auth configuration [id] in [env]. */
    fun hasAuth(
        env: String,
        id: String,
    ): Boolean =
        FileSide.entries.any { (trees.nodeAt(it, env, EnvSchema.SECURITY_AUTH) as? ObjectNode)?.has(id) == true }

    fun addAuth(
        env: String,
        id: String,
        grantType: String,
    ) {
        val base = EnvSchema.authPath(id)
        val password = grantType.equals(Auth.GRANT_PASSWORD, ignoreCase = true)

        fun put(
            side: FileSide,
            key: String,
            value: String,
        ) = trees.put(side, env, base.key(key), EnvJson.nodes.stringNode(value))
        put(FileSide.PUBLIC, Auth.TYPE, Auth.TYPE_OAUTH2)
        put(FileSide.PUBLIC, Auth.GRANT_TYPE, grantType)
        put(FileSide.PUBLIC, Auth.TOKEN_URL, "")
        put(FileSide.PUBLIC, Auth.CLIENT_ID, "")
        if (password) put(FileSide.PUBLIC, Auth.USERNAME, "")
        put(FileSide.PUBLIC, Auth.SCOPE, "")
        put(FileSide.PRIVATE, Auth.CLIENT_SECRET, "")
        if (password) put(FileSide.PRIVATE, Auth.PASSWORD, "")
    }

    fun removeAuth(
        env: String,
        id: String,
    ) {
        trees.deleteFromBoth(env, EnvSchema.authPath(id))
    }

    fun addCertificate(env: String) {
        if (trees.nodeAt(FileSide.PRIVATE, env, EnvSchema.SSL) != null) return
        val config =
            EnvJson.nodes
                .objectNode()
                .set(EnvKeys.Ssl.CLIENT_CERTIFICATE, EnvJson.nodes.stringNode(""))
                .set(EnvKeys.Ssl.VERIFY_HOST_CERTIFICATE, EnvJson.nodes.booleanNode(true))
        trees.put(FileSide.PRIVATE, env, EnvSchema.SSL, config)
    }

    /** Removes `SSLConfiguration` of [env] from both files. */
    fun removeCertificate(env: String) {
        trees.deleteFromBoth(env, EnvSchema.SSL)
    }

    /** Writes the path of [part], in the object form (`path`) if the entry has it, else as the entry itself. */
    fun setCertificatePath(
        env: String,
        part: CertificatePart,
        value: JsonNode,
    ) {
        val path = EnvSchema.certificatePath(part)
        if (trees.nodeAt(FileSide.PRIVATE, env, path) is ObjectNode) {
            trees.put(FileSide.PRIVATE, env, path.key(EnvKeys.Ssl.PATH), value)
        } else {
            trees.put(FileSide.PRIVATE, env, path, value)
        }
    }

    /**
     * Gives [part] a [format] by turning a path text into the object form (`path`, `format`); a null format removes
     * it and turns the entry back into its path text, unless the object holds other keys.
     */
    fun setCertificateFormat(
        env: String,
        part: CertificatePart,
        format: String?,
    ) {
        val path = EnvSchema.certificatePath(part)
        val entry = trees.nodeAt(FileSide.PRIVATE, env, path)
        when {
            format == null && entry is ObjectNode -> {
                if (entry.propertyNames().all { it == EnvKeys.Ssl.PATH || it == EnvKeys.Ssl.FORMAT }) {
                    val text = entry.get(EnvKeys.Ssl.PATH) ?: EnvJson.nodes.stringNode("")
                    trees.put(FileSide.PRIVATE, env, path, text)
                } else {
                    entry.remove(EnvKeys.Ssl.FORMAT)
                }
            }

            format == null -> {}

            entry is ObjectNode -> {
                trees.put(FileSide.PRIVATE, env, path.key(EnvKeys.Ssl.FORMAT), EnvJson.nodes.stringNode(format))
            }

            else -> {
                val config =
                    EnvJson.nodes
                        .objectNode()
                        .set(EnvKeys.Ssl.PATH, entry ?: EnvJson.nodes.stringNode(""))
                        .set(EnvKeys.Ssl.FORMAT, EnvJson.nodes.stringNode(format))
                trees.put(FileSide.PRIVATE, env, path, config)
            }
        }
    }

    /** The format a value of a format row stands for: null for `null` and blank texts. */
    private fun formatOf(value: JsonNode): String? =
        when {
            value.isNull -> null
            value.isString -> value.stringValue().trim().ifEmpty { null }
            else -> value.toString()
        }
}

package dev.sluide.httpclient.envedit

import dev.sluide.httpclient.env.EnvironmentFiles
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode

/**
 * The texts of the public and private environment file ([publicText], [privateText]; null for a missing file), shown
 * as a [table] and edited by operations that each return the new texts of the files they change ([EnvEdit]). The
 * document itself never changes: the caller applies the edit and makes a new document of the new texts.
 *
 * Operations on a document whose table has an error throw [IllegalStateException]; unknown or read-only environments,
 * taken names and other wrong arguments throw [IllegalArgumentException]. Changed files are written in full by
 * [EnvJson]; values the form does not show stay as they are. After a value is removed from or moved out of a file,
 * the objects on its way that became empty are removed from that file if the other file still has them, so the
 * merged view keeps them.
 */
internal class EnvDocument(
    val publicText: String?,
    val privateText: String?,
) {
    private val trees: EnvTrees?
    val table: EnvTable

    init {
        var parsed: EnvTrees? = null
        table =
            try {
                parsed =
                    EnvTrees(read(publicText, EnvironmentFiles.PUBLIC), read(privateText, EnvironmentFiles.PRIVATE))
                EnvTableBuilder(parsed).build()
            } catch (e: UnreadableFile) {
                parsed = null
                EnvTable.failed(e.message)
            }
        trees = parsed
    }

    /**
     * Writes the value at [path] (a leaf, or anything inside a list) of [environment] where it lives now (the private
     * file when it overrides a public one). A new value goes to [side], else to the file the schema says (Client
     * Secret, Password and certificate settings private), else to the file of its object like [addVariable] (so a new
     * variable of an environment only the private file has stays there); missing objects on the way are created.
     * Certificate settings always go to the private file. On the certificate rows the path text is written in either
     * form of the entry, and a format (blank or `null` for none) switches between the forms like
     * [setCertificateFormat].
     */
    fun setValue(
        environment: String,
        path: ValuePath,
        value: JsonNode,
        side: FileSide? = null,
    ): EnvEdit = edit(environment) { it.setValue(environment, path, value, side) }

    /** Removes the value at [path] of [environment] from both files (inside a list: the entry from its list). */
    fun remove(
        environment: String,
        path: ValuePath,
    ): EnvEdit = edit(environment) { it.remove(environment, path) }

    /** Removes the value at [path] from all environments. */
    fun removeRow(path: ValuePath): EnvEdit = edit { editor -> editable().forEach { editor.remove(it, path) } }

    /**
     * Moves the value at [path] of [environment] to the file [side], replacing a public value it overrides. Inside a
     * list the whole list moves. Objects that merge key by key have no file of their own: their entries are moved.
     */
    fun setSide(
        environment: String,
        path: ValuePath,
        side: FileSide,
    ): EnvEdit = edit(environment) { it.setSide(environment, path, side) }

    /**
     * Converts the value at [path] of every environment to [type] ([EnvValues.convert]), in the file holding it; an
     * object both files hold becomes one value of the row's default file. Certificate settings have fixed types.
     */
    fun setType(
        path: ValuePath,
        type: ValueType,
    ): EnvEdit {
        require(type != ValueType.MIXED) { "Mixed is not a type of a value" }
        require(!EnvSchema.isCertificate(path)) { "Certificate settings have fixed types" }
        return edit { editor -> editable().forEach { editor.setType(it, path, type) } }
    }

    /** Whether [setType] would replace a value of some environment by an empty one. */
    fun isLossy(
        path: ValuePath,
        type: ValueType,
    ): Boolean {
        require(!EnvSchema.isCertificate(path)) { "Certificate settings have fixed types" }
        val editor = EnvEditor(requireTrees())
        return editable().any { editor.isLossy(it, path, type) }
    }

    /**
     * Adds [name] with the empty value of [type] to the object at [parent] in every environment that has it: in the
     * file the schema says (certificate settings private), else in the file of the object (the list's file inside a
     * list, the public file if it has the object, else the private one). Known auth fields are not added this way:
     * they have their own rows.
     */
    fun addVariable(
        parent: ValuePath,
        name: String,
        type: ValueType,
    ): EnvEdit {
        require(name.isNotEmpty()) { "A variable needs a name" }
        require(type != ValueType.MIXED) { "Mixed is not a type of a value" }
        require(!parent.isRoot || name !in EnvSchema.RESERVED) { "\"$name\" is not a variable" }
        require(parent != EnvSchema.SECURITY_AUTH) { "Auth configurations are added with addAuth" }
        require(!EnvSchema.isAuthConfig(parent) || EnvSchema.AUTH_FIELDS.none { it.key == name }) {
            "\"$name\" is a field of every auth configuration"
        }
        val editor = EnvEditor(requireTrees())
        require(editable().none { editor.hasKey(it, parent, name) }) { "\"${parent.key(name)}\" already exists" }
        return edit { e -> editable().forEach { e.addKey(it, parent, name, type) } }
    }

    /** Appends the empty value of [type] to the list at [list] in every environment that has it. */
    fun appendListEntry(
        list: ValuePath,
        type: ValueType,
    ): EnvEdit {
        require(type != ValueType.MIXED) { "Mixed is not a type of a value" }
        return edit { editor -> editable().forEach { editor.appendListEntry(it, list, type) } }
    }

    /** Appends the empty environment [name] to the public file. */
    fun addEnvironment(name: String): EnvEdit {
        requireNewName(name)
        return edit { it.addEnvironment(name) }
    }

    /** Renames [old] in both files, keeping its position (also an environment that is not an object). */
    fun renameEnvironment(
        old: String,
        new: String,
    ): EnvEdit {
        requireKnown(old)
        requireNewName(new)
        return edit { it.renameEnvironment(old, new) }
    }

    /** Removes [name] from both files (also an environment that is not an object). */
    fun removeEnvironment(name: String): EnvEdit {
        requireKnown(name)
        return edit { it.removeEnvironment(name) }
    }

    /**
     * Adds the OAuth2 configuration [id] with [grantType] to [environments], none of which may have it yet: Type,
     * Grant Type, Token URL, Client ID and Scope public, Client Secret private, for the Password grant also Username
     * public and Password private.
     */
    fun addAuth(
        id: String,
        grantType: String,
        environments: Collection<String>,
    ): EnvEdit {
        require(id.isNotBlank()) { "An auth configuration needs an id" }
        val grant =
            requireNotNull(EnvSchema.GRANT_TYPES.firstOrNull { it.equals(grantType, ignoreCase = true) }) {
                "Unsupported grant type \"$grantType\""
            }
        val editor = EnvEditor(requireTrees())
        environments.firstOrNull { editor.hasAuth(it, id) }?.let {
            throw IllegalArgumentException("Environment \"$it\" already has auth \"$id\"")
        }
        return edit(*environments.toTypedArray()) { e -> environments.forEach { e.addAuth(it, id, grant) } }
    }

    /** Removes the auth configuration [id] from all environments and both files. */
    fun removeAuth(id: String): EnvEdit = edit { editor -> editable().forEach { editor.removeAuth(it, id) } }

    /**
     * Adds `SSLConfiguration` (an empty certificate path, host verification on) to the private file of each of
     * [environments] that has none.
     */
    fun addCertificate(environments: Collection<String>): EnvEdit =
        edit(*environments.toTypedArray()) { editor -> environments.forEach { editor.addCertificate(it) } }

    /** Removes `SSLConfiguration` of [environment] from both files. */
    fun removeCertificate(environment: String): EnvEdit = edit(environment) { it.removeCertificate(environment) }

    /**
     * Sets the format of the certificate or key of [environment]: a path text becomes `{"path", "format"}`; a null
     * [format] turns it back into the path text.
     */
    fun setCertificateFormat(
        environment: String,
        part: CertificatePart,
        format: String?,
    ): EnvEdit = edit(environment) { it.setCertificateFormat(environment, part, format?.trim()?.ifEmpty { null }) }

    private fun editable() = table.environments.filterNot { it in table.readOnlyEnvironments }

    private fun requireTrees(): EnvTrees =
        checkNotNull(trees) { "The environment files cannot be edited: ${table.error}" }

    private fun edit(
        vararg environments: String,
        block: (EnvEditor) -> Unit,
    ): EnvEdit {
        val original = requireTrees()
        environments.forEach {
            requireKnown(it)
            require(it !in table.readOnlyEnvironments) { "Environment \"$it\" is not an object and cannot be edited" }
        }
        val changed = original.copy()
        block(EnvEditor(changed))
        return EnvEdit(
            public = EnvJson.print(changed.public).takeIf { changed.public != original.public },
            private = EnvJson.print(changed.private).takeIf { changed.private != original.private },
        )
    }

    private fun requireKnown(environment: String) {
        requireTrees()
        require(environment in table.environments) { "There is no environment \"$environment\"" }
    }

    private fun requireNewName(name: String) {
        val trees = requireTrees()
        require(name.isNotBlank()) { "An environment needs a name" }
        require(!name.startsWith("$")) { "Environment names cannot start with \$" }
        require(name !in trees.topLevelKeys) { "\"$name\" already exists" }
    }

    private class UnreadableFile(
        override val message: String,
    ) : Exception(message)

    private companion object {
        /** [text] as an object of environments, empty for a missing or blank file. */
        fun read(
            text: String?,
            fileName: String,
        ): ObjectNode {
            val node =
                try {
                    text?.let(EnvJson::parse)
                } catch (e: EnvJsonException) {
                    throw UnreadableFile("$fileName: ${e.message}")
                }
            return when (node) {
                null -> EnvJson.nodes.objectNode()
                is ObjectNode -> node
                else -> throw UnreadableFile("$fileName: expected an object of environments")
            }
        }
    }
}

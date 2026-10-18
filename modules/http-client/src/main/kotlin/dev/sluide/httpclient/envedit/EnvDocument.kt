package dev.sluide.httpclient.envedit

import dev.sluide.httpclient.env.EnvironmentFiles
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

package dev.sluide.httpclient.env

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.IOException
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * An environment: its variables ([values], without `Security` and `SSLConfiguration`), auth configurations by id,
 * the client certificate settings and what could not be understood.
 */
internal data class Environment(
    val name: String,
    val values: ObjectNode,
    val auth: Map<String, AuthConfig>,
    val ssl: SslConfig?,
    val problems: List<String>,
)

/** The environments of a pair of [EnvironmentFiles], in the order of the files; [problems] concern whole files. */
internal data class Environments(
    val environments: List<Environment>,
    val problems: List<String>,
) {
    val names: List<String> get() = environments.map { it.name }

    operator fun get(name: String): Environment? = environments.firstOrNull { it.name == name }

    companion object {
        private val mapper = JsonMapper.builder().build()
        private const val SECURITY = "Security"
        private const val SSL = "SSLConfiguration"

        /**
         * Reads both files. The environments are the top-level keys of either file; each is the public one deep-merged
         * with the private one, private values winning on leaves (arrays are leaves). Unreadable files count as empty.
         */
        fun load(files: EnvironmentFiles): Environments {
            val problems = mutableListOf<String>()
            val public = files.public?.let { read(it, problems) } ?: mapper.createObjectNode()
            val private = files.private?.let { read(it, problems) } ?: mapper.createObjectNode()
            val names = (public.propertyNames() + private.propertyNames()).distinct()
            names.filter { it.startsWith("$") }.forEach { problems += "\"$it\" is not supported and ignored" }
            val environments =
                names.filterNot { it.startsWith("$") }.map { name ->
                    environment(name, public.get(name), private.get(name), files.private?.parent)
                }
            return Environments(environments, problems)
        }

        private fun read(
            file: Path,
            problems: MutableList<String>,
        ): ObjectNode? {
            val node =
                try {
                    mapper.readTree(file.readText())
                } catch (e: JacksonException) {
                    problems += "${file.fileName}: ${e.originalMessage}"
                    return null
                } catch (e: IOException) {
                    problems += "${file.fileName}: ${e.message}"
                    return null
                }
            if (node is ObjectNode) return node
            if (!node.isMissingNode) problems += "${file.fileName}: expected an object of environments"
            return null
        }

        private fun environment(
            name: String,
            public: JsonNode?,
            private: JsonNode?,
            privateFolder: Path?,
        ): Environment {
            val problems = mutableListOf<String>()

            fun objectOrNull(
                node: JsonNode?,
                file: String,
            ): ObjectNode? {
                if (node == null) return null
                if (node is ObjectNode) return node
                problems += "$file: environment \"$name\" must be an object"
                return null
            }
            val publicValues = objectOrNull(public, EnvironmentFiles.PUBLIC)
            val privateValues = objectOrNull(private, EnvironmentFiles.PRIVATE)
            val values = mapper.createObjectNode()
            publicValues?.let { merge(values, it) }
            privateValues?.let { merge(values, it) }
            if (publicValues?.has(SSL) == true) {
                problems += "$SSL is only read from ${EnvironmentFiles.PRIVATE}"
            }
            val ssl =
                privateValues?.get(SSL)?.let { node ->
                    privateFolder?.let { SslConfig.parse(node, it, problems) }
                }
            val auth = auth(values.get(SECURITY), problems)
            values.remove(listOf(SECURITY, SSL))
            return Environment(name, values, auth, ssl, problems)
        }

        private fun auth(
            security: JsonNode?,
            problems: MutableList<String>,
        ): Map<String, AuthConfig> {
            val auth = security?.get("Auth") ?: return emptyMap()
            if (!auth.isObject) {
                problems += "$SECURITY.Auth must be an object"
                return emptyMap()
            }
            return auth
                .properties()
                .filter { (id, node) ->
                    node.isObject.also { if (!it) problems += "Auth \"$id\" must be an object" }
                }.associate { (id, node) -> id to AuthConfig.parse(id, node, problems) }
        }

        /** Copies [source] into [target]; objects are merged key by key, everything else replaces. */
        private fun merge(
            target: ObjectNode,
            source: ObjectNode,
        ) {
            for ((key, value) in source.properties()) {
                val existing = target.get(key)
                if (existing is ObjectNode && value is ObjectNode) {
                    merge(existing, value)
                } else {
                    target.set(key, value.deepCopy())
                }
            }
        }
    }
}

package dev.sluide.httpclient.curl

import dev.sluide.converting.WriteResult
import dev.sluide.httpclient.env.Environment
import dev.sluide.httpclient.env.EnvironmentFiles
import dev.sluide.httpclient.env.Environments
import dev.sluide.httpclient.parse.BodyPart
import dev.sluide.httpclient.parse.FileVariable
import dev.sluide.httpclient.parse.RequestBlock
import dev.sluide.httpclient.parse.ScriptBlock
import dev.sluide.httpclient.vars.DynamicVariables
import dev.sluide.httpclient.vars.VariableResolver
import tools.jackson.databind.JsonNode
import java.nio.file.Path

/** How to treat private values in a copied command: put them in, keep them as `{{name}}`, or copy nothing. */
internal enum class PrivateValuesAnswer { INCLUDE, KEEP, CANCEL }

/**
 * A request as a POSIX `curl` command for Copy as cURL: [keepingPrivate] with the values of the private environment
 * file as `{{name}}`, [withPrivate] with them resolved; [privateNames] are the private values it uses, [unresolved] the
 * variables without a value (kept as written, like `$auth.token(…)`, which are not listed), [notes] what the command
 * leaves out or changes.
 */
internal data class CopiedCommand(
    val keepingPrivate: String,
    val withPrivate: String,
    val privateNames: List<String>,
    val unresolved: List<String>,
    val notes: List<String> = emptyList(),
) {
    /** Whether to ask about the private values before copying. */
    val asks: Boolean get() = privateNames.isNotEmpty()

    /** The text to copy after [answer]; `null` to copy nothing. */
    fun text(answer: PrivateValuesAnswer): String? =
        when (answer) {
            PrivateValuesAnswer.INCLUDE -> withPrivate
            PrivateValuesAnswer.KEEP -> keepingPrivate
            PrivateValuesAnswer.CANCEL -> null
        }

    /** What to copy: asks [ask] about the private values first, if there are any; `null` to copy nothing. */
    fun copied(ask: (List<String>) -> PrivateValuesAnswer): String? =
        if (asks) text(ask(privateNames)) else keepingPrivate

    /** The message once copied: the [notes] and the [unresolved] variables. */
    val message: String
        get() {
            val unresolved = unresolved.takeIf { it.isNotEmpty() }?.let { "unresolved: ${it.joinToString(", ")}" }
            return (listOf("Copied as cURL") + notes + listOfNotNull(unresolved)).joinToString("; ")
        }
}

/** The question of Copy as cURL when the request uses the private values [names]. */
internal fun privateValuesQuestion(names: List<String>): String =
    "The request uses private values: ${names.joinToString(", ")}. Include them in the copied command?"

/**
 * Turns requests of a `.http` file in [folder] into `curl` commands with their variables resolved against the
 * [environment] (whose [privateValues] are those of the private environment file), the [fileVariables] and the
 * [dynamic] variables (ADR 0116). Body files get absolute paths, so the command runs from any folder; `-k` is added when
 * the environment does not verify host certificates, as the request does when run.
 */
internal class CurlCopy(
    private val environment: Environment?,
    private val privateValues: JsonNode?,
    private val fileVariables: List<FileVariable>,
    private val folder: Path,
    private val dynamic: DynamicVariables = DynamicVariables(),
) {
    fun command(block: RequestBlock): CopiedCommand {
        val privateNames = linkedSetOf<String>()
        val keeping = resolved(block) { name -> isPrivate(name).also { if (it) privateNames += name } }
        val with = if (privateNames.isEmpty()) keeping else resolved(block) { false }
        return CopiedCommand(keeping.text, with.text, privateNames.toList(), keeping.unresolved, keeping.notes)
    }

    private data class Resolved(
        val text: String,
        val unresolved: List<String>,
        val notes: List<String>,
    )

    /** The command of [block] with the names [kept] says left as written. */
    private fun resolved(
        block: RequestBlock,
        kept: (String) -> Boolean,
    ): Resolved {
        val resolver = VariableResolver(environment, fileVariables, dynamic, kept = kept)
        val unresolved = linkedSetOf<String>()

        fun resolve(text: String) = resolver.resolvePartially(text).also { unresolved += it.unresolved }.text
        val resolvedBlock =
            block.copy(
                url = resolve(block.url),
                headers = block.headers.map { it.copy(name = resolve(it.name), value = resolve(it.value)) },
                body =
                    block.body.map { part ->
                        when (part) {
                            is BodyPart.Text -> BodyPart.Text(resolve(part.text))
                            is BodyPart.FileRef -> part.copy(path = absolute(resolve(part.path)))
                        }
                    },
            )
        val notes = mutableListOf<String>()
        if (block.scripts.any { it.kind == ScriptBlock.Kind.PRE }) notes += "Pre-request script dropped"
        if (block.scripts.any { it.kind == ScriptBlock.Kind.RESPONSE }) notes += "Response handler dropped"
        val insecure = environment?.ssl?.verifyHostCertificate == false
        val request = HttpRequestReader.request(resolvedBlock, notes).copy(name = null, insecure = insecure)
        val written = CurlWriter.write(listOf(request), Shell.POSIX) as WriteResult.Written
        return Resolved(
            written.text.trimEnd(),
            unresolved.filterNot { it.startsWith(AUTH_PREFIX) },
            notes + written.notes,
        )
    }

    private fun absolute(path: String): String = if ("{{" in path) path else folder.resolve(path).normalize().toString()

    /** Whether [name] is a value of the private environment file, as the resolver looks it up in the environment. */
    private fun isPrivate(name: String): Boolean {
        val values = privateValues ?: return false
        if (values.get(name) != null) return true
        return name.split('.').fold(values as JsonNode?) { node, key -> node?.get(key) } != null
    }

    companion object {
        private const val AUTH_PREFIX = "\$auth."

        /**
         * Reads the environment files of [httpFile] (within [projectFolder]) for environment [environment] (none if
         * `null`). Reads files: call off the FX thread.
         */
        fun load(
            httpFile: Path,
            projectFolder: Path?,
            environment: String?,
            fileVariables: List<FileVariable>,
        ): CurlCopy {
            val folder = httpFile.toAbsolutePath().normalize().parent
            if (environment == null) return CurlCopy(null, null, fileVariables, folder)
            val files = EnvironmentFiles.find(httpFile, projectFolder)
            return CurlCopy(
                Environments.load(files)[environment],
                Environments.privateValues(files, environment),
                fileVariables,
                folder,
            )
        }
    }
}

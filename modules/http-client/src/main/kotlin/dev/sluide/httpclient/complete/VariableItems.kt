package dev.sluide.httpclient.complete

import dev.sluide.httpclient.HttpCompletionItem
import dev.sluide.httpclient.env.Environments
import dev.sluide.httpclient.parse.HttpFile
import dev.sluide.httpclient.vars.DYNAMIC_VARIABLE_NAMES
import dev.sluide.httpclient.vars.DynamicVariables
import dev.sluide.httpclient.vars.needsArguments

/**
 * The variables offered inside `{{ }}`, in four groups, each alphabetical, a label once (the first group wins):
 * the `@name` variables of the file, the names of all environments, the `$` variables, and the `$auth` calls.
 * Only names ever reach an item, never a value.
 */
internal object VariableItems {
    private const val ENV_PREFIX = "\$env."

    /** What `Template` can read as a name: no whitespace, parentheses or braces. */
    private val NAME = Regex("""[^\s(){}]+""")

    fun build(
        file: HttpFile,
        context: Context.Variable,
        partial: String,
        environments: Environments,
        osEnvNames: () -> Set<String>,
    ): List<HttpCompletionItem> {
        val closing = if (context.closed) "" else "}}"
        val result = LinkedHashMap<String, HttpCompletionItem>()

        fun add(
            label: String,
            detail: String,
            insertText: String = label + closing,
        ) {
            result.putIfAbsent(label, HttpCompletionItem(label, insertText, detail))
        }
        val defined = environmentNames(environments)
        // The resolver reads the environment before the file, so a name of both is one item, in the file's place.
        file.variables
            .filter { it.line != context.line }
            .map { it.name }
            .sortedWith(ORDER)
            .forEach { add(it, (defined[it].orEmpty() + "@ variable").joinToString(", ")) }
        defined.entries.sortedWith(compareBy(ORDER) { it.key }).forEach { (name, inEnvironments) ->
            add(name, inEnvironments.joinToString(", "))
        }
        dynamic(partial, osEnvNames).forEach { (label, detail, insertText) ->
            add(label, detail, insertText ?: (label + closing))
        }
        authCalls(environments).forEach { (label, detail) -> add(label, detail) }
        return result.values.toList()
    }

    private val ORDER: Comparator<String> = String.CASE_INSENSITIVE_ORDER.thenComparing(Comparator.naturalOrder())

    /** The top-level names of each environment, which `VariableResolver` looks up, with the environments defining them. */
    private fun environmentNames(environments: Environments): Map<String, List<String>> {
        val names = LinkedHashMap<String, MutableList<String>>()
        for (environment in environments.environments) {
            for (name in environment.values.propertyNames()) {
                if (name.startsWith("$") || !NAME.matches(name)) continue
                names.getOrPut(name) { mutableListOf() } += environment.name
            }
        }
        return names
    }

    private fun dynamic(
        partial: String,
        osEnvNames: () -> Set<String>,
    ): List<Triple<String, String, String?>> {
        val probe = DynamicVariables()
        // A name that needs arguments is completed up to its parenthesis, like `$env.` up to its dot.
        val own =
            DYNAMIC_VARIABLE_NAMES.map {
                Triple(it.name, it.hint ?: "dynamic", if (probe.needsArguments(it.name)) it.name + "(" else null)
            }
        val env =
            if (partial.startsWith(ENV_PREFIX)) {
                osEnvNames()
                    .filter { NAME.matches(it) }
                    .map { Triple(ENV_PREFIX + it, "OS environment", null) }
            } else {
                listOf(Triple(ENV_PREFIX, "OS environment variable", ENV_PREFIX))
            }
        return (own + env).sortedWith(compareBy(ORDER) { it.first })
    }

    private fun authCalls(environments: Environments): List<Pair<String, String>> {
        val ids = LinkedHashMap<String, MutableList<String>>()
        for (environment in environments.environments) {
            for (id in environment.auth.keys) {
                if (isWritable(id)) ids.getOrPut(id) { mutableListOf() } += environment.name
            }
        }
        return ids
            .flatMap { (id, defined) ->
                listOf("token", "idToken").map { "\$auth.$it(\"$id\")" to defined.joinToString(", ") }
            }.sortedWith(compareBy(ORDER) { it.first })
    }

    /** An auth id that fits in `"…"` of a call that `Template` reads back. */
    private fun isWritable(id: String) =
        id.isNotEmpty() && '"' !in id && "{{" !in id && "}}" !in id &&
            id.none { it.isISOControl() || it == '\u2028' || it == '\u2029' }
}

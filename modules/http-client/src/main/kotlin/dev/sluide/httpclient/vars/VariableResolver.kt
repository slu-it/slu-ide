package dev.sluide.httpclient.vars

import dev.sluide.httpclient.env.Environment
import dev.sluide.httpclient.parse.FileVariable
import dev.sluide.httpclient.vars.Template.Part
import tools.jackson.databind.JsonNode

/** Thrown with every variable of a text that has no value (or takes part in a cycle of variables). */
internal class UnresolvedVariablesException(
    val names: List<String>,
) : RuntimeException("Unresolved variables: ${names.joinToString(", ")}")

/**
 * Replaces the `{{…}}` variables of texts. A name is looked up in this order:
 * 1. `$` names: [dynamic] variables, and `$auth.token("id")` / `$auth.idToken("id")` from [auth];
 * 2. the [environment] (a dotted name is a path into its objects; values that are no text become JSON);
 * 3. the in-file `@variables` (the last definition wins).
 *
 * Text values of the environment and of in-file variables may use variables themselves; cycles are reported as
 * unresolved.
 */
internal class VariableResolver(
    private val environment: Environment?,
    fileVariables: List<FileVariable>,
    private val dynamic: DynamicVariables = DynamicVariables(),
    private val auth: (id: String, idToken: Boolean) -> String? = { _, _ -> null },
) {
    /** A text with the variables that could be replaced replaced, the others as written, and their names. */
    data class Partial(
        val text: String,
        val unresolved: List<String>,
    )

    private val fileValues = fileVariables.associate { it.name to it.value }

    /** [text] with all variables replaced; throws [UnresolvedVariablesException] listing every missing one. */
    fun resolve(text: String): String {
        val partial = resolvePartially(text)
        if (partial.unresolved.isNotEmpty()) throw UnresolvedVariablesException(partial.unresolved)
        return partial.text
    }

    /** [text] with the variables that have a value replaced and the others left as written. */
    fun resolvePartially(text: String): Partial {
        val unresolved = linkedSetOf<String>()
        val result = resolve(text, emptyList(), unresolved)
        return Partial(result, unresolved.toList())
    }

    /** Lookups return null for missing values after adding them to [unresolved]; the part then stays as written. */
    private fun resolve(
        text: String,
        enclosing: List<String>,
        unresolved: MutableSet<String>,
    ): String =
        Template.parse(text).parts.joinToString("") { part ->
            when (part) {
                is Part.Literal -> part.text
                is Part.Call -> call(part, unresolved)
                is Part.Ref -> ref(part, enclosing, unresolved)
            } ?: part.raw
        }

    private fun call(
        call: Part.Call,
        unresolved: MutableSet<String>,
    ): String? {
        val value =
            when (call.name) {
                "\$auth.token" -> call.args.singleOrNull()?.let { auth(it, false) }
                "\$auth.idToken" -> call.args.singleOrNull()?.let { auth(it, true) }
                else -> return dynamic(call.name, call.args, call.label, unresolved)
            }
        if (value == null) unresolved += call.label
        return value
    }

    private fun ref(
        ref: Part.Ref,
        enclosing: List<String>,
        unresolved: MutableSet<String>,
    ): String? {
        val name = ref.name
        if (name.startsWith("$")) return dynamic(name, emptyList(), name, unresolved)
        val node = environmentNode(ref)
        val text =
            when {
                node == null -> fileValues[name]
                node.isString -> node.stringValue()
                else -> return node.toString()
            }
        if (text == null) {
            unresolved += name
            return null
        }
        if (name in enclosing) {
            unresolved += (enclosing.dropWhile { it != name } + name).joinToString(" -> ")
            return null
        }
        return resolve(text, enclosing + name, unresolved)
    }

    private fun dynamic(
        name: String,
        args: List<String>,
        label: String,
        unresolved: MutableSet<String>,
    ): String? {
        val value =
            try {
                dynamic.value(name, args)
            } catch (e: IllegalArgumentException) {
                unresolved += "$label (${e.message})"
                return null
            }
        if (value == null) unresolved += label
        return value
    }

    private fun environmentNode(ref: Part.Ref): JsonNode? {
        val values = environment?.values ?: return null
        values.get(ref.name)?.let { return it }
        var node: JsonNode? = values
        for (key in ref.path) node = node?.get(key)
        return node
    }
}

package dev.sluide.githubactions.model

import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * Decodes the bundled parser's JSON (schema 1, `modules/previews/src/main/js/workflow-parser/README.md`) by hand from
 * Jackson's tree, tolerantly: unknown fields are ignored, a value of the wrong type counts as missing, and an entry
 * missing what it needs (a job without `id`, a step with neither `run` nor `uses`) is left out.
 */
internal object WorkflowJson {
    sealed interface Result {
        data class Decoded(
            val workflow: Workflow,
        ) : Result

        /** No JSON object, or one of another schema; [reason] is a line for the user. */
        data class Unsupported(
            val reason: String,
        ) : Result
    }

    private val mapper = JsonMapper.builder().build()

    fun decode(json: String): Result {
        val root =
            try {
                mapper.readTree(json)
            } catch (_: JacksonException) {
                null
            }?.takeIf { it.isObject } ?: return Result.Unsupported("Unsupported parser output (no JSON object).")
        val schema = root.int("schema")
        if (schema != SCHEMA) {
            return Result.Unsupported("Unsupported parser output (schema ${schema ?: "missing"}, expected $SCHEMA).")
        }
        return Result.Decoded(
            Workflow(
                name = root.name(),
                problems = root.objects("errors").map(::problem),
                events = root.objects("events").mapNotNull(::event),
                jobs = root.objects("jobs").mapNotNull(::job),
            ),
        )
    }

    private fun problem(node: JsonNode) =
        Problem(node.string("message") ?: "(no message)", node.int("line"), node.int("column"))

    private fun event(node: JsonNode): Event? = node.string("name")?.let { Event(it, node.string("detail")) }

    private fun job(node: JsonNode): Job? {
        val id = node.string("id") ?: return null
        val uses = node.string("uses")
        val body =
            if (uses != null && (node.string("type") ?: REUSABLE) == REUSABLE) {
                JobBody.WorkflowCall(
                    text = uses,
                    reference = UsesReference.parse(uses, UsesSite.JOB),
                    inputs = node.obj("with")?.let(::texts)?.mapValues { it.value.orEmpty() } ?: emptyMap(),
                    secretsInherit = node.boolean("secretsInherit") ?: false,
                )
            } else {
                JobBody.Steps(node.objects("steps").mapNotNull(::step))
            }
        return Job(
            id = id,
            name = node.name(),
            line = node.int("line"),
            needs = node.array("needs").filter { it.isString }.map { it.stringValue() },
            condition = node.string("if"),
            runsOn = node.string("runsOn"),
            matrix = node.obj("matrix")?.let(::matrix),
            body = body,
        )
    }

    private fun step(node: JsonNode): Step? {
        val run = node.string("run")
        val uses = node.string("uses")
        val action =
            when {
                // The contract counts a step with both as `run`.
                run != null -> StepAction.Run(run)

                uses != null -> StepAction.Uses(uses, UsesReference.parse(uses, UsesSite.STEP))

                else -> return null
            }
        return Step(node.string("id"), node.name(), node.int("line"), action, node.string("if"))
    }

    private fun matrix(node: JsonNode) =
        Matrix(
            expression = node.string("expression"),
            axes =
                node.obj("axes")?.properties()?.filter { it.value.isArray }?.associate { (name, values) ->
                    name to values.filter { it.isNull || it.isString }.map { it.text() }
                } ?: emptyMap(),
            include = node.objects("include").map(::texts),
            exclude = node.objects("exclude").map(::texts),
            includeExpression = node.string("includeExpression"),
            excludeExpression = node.string("excludeExpression"),
        )

    /** An object's string and null values by key, in order; values of other types are left out. */
    private fun texts(node: JsonNode): Map<String, String?> =
        node
            .properties()
            .filter { it.value.isNull || it.value.isString }
            .associate { (key, value) -> key to value.text() }

    private fun JsonNode.text(): String? = if (isString) stringValue() else null

    private fun JsonNode.string(name: String): String? = get(name)?.text()

    /** The `name`; a blank one counts as none, so that the id or what a step does is shown instead. */
    private fun JsonNode.name(): String? = string("name")?.takeIf { it.isNotBlank() }

    private fun JsonNode.int(name: String): Int? = get(name)?.takeIf { it.isInt }?.intValue()

    private fun JsonNode.boolean(name: String): Boolean? = get(name)?.takeIf { it.isBoolean }?.booleanValue()

    private fun JsonNode.obj(name: String): JsonNode? = get(name)?.takeIf { it.isObject }

    private fun JsonNode.array(name: String): List<JsonNode> = get(name)?.takeIf { it.isArray }?.toList() ?: emptyList()

    private fun JsonNode.objects(name: String): List<JsonNode> = array(name).filter { it.isObject }

    private const val SCHEMA = 1
    private const val REUSABLE = "reusableWorkflowJob"
}

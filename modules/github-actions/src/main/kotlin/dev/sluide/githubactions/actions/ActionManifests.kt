package dev.sluide.githubactions.actions

import dev.sluide.githubactions.model.Step
import dev.sluide.githubactions.model.StepAction
import dev.sluide.githubactions.model.UsesReference
import dev.sluide.githubactions.model.UsesSite
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.MarkedYAMLException
import org.yaml.snakeyaml.inspector.TagInspector
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import java.io.StringReader

/**
 * Reads a local action's manifest (`action.yml`) with SnakeYAML's node tree (`compose`): nothing is constructed, and
 * every scalar is read as its text, so YAML 1.1's `on`/`yes`/`no` stay words. [LoaderOptions] limit aliases, nesting
 * and size; custom tags are refused. Tolerant like [dev.sluide.githubactions.model.WorkflowJson]: values of the wrong
 * type count as missing, steps with neither `run` nor `uses` are left out.
 */
internal object ActionManifests {
    sealed interface Result {
        data class Parsed(
            val manifest: ActionManifest,
        ) : Result

        /** Not YAML, over a limit, or not a mapping; [reason] is short, for a box. */
        data class Failed(
            val reason: String,
        ) : Result
    }

    const val MAX_ALIASES = 50
    const val MAX_NESTING = 50
    const val MAX_CODE_POINTS = 1_048_576

    fun parse(text: String): Result {
        val root =
            try {
                Yaml(options()).compose(StringReader(text))
            } catch (e: RuntimeException) {
                // SnakeYAML's YAMLException and anything else it throws for hostile input.
                return Result.Failed(reason(e))
            }
        val mapping = root as? MappingNode ?: return Result.Failed("not a YAML mapping")
        return Result.Parsed(
            ActionManifest(
                name = mapping.text("name")?.takeIf { it.isNotBlank() },
                runs = runs(mapping.mapping("runs")),
            ),
        )
    }

    private fun options() =
        LoaderOptions().apply {
            maxAliasesForCollections = MAX_ALIASES
            nestingDepthLimit = MAX_NESTING
            codePointLimit = MAX_CODE_POINTS
            isProcessComments = false
            tagInspector = TagInspector { false }
        }

    private fun runs(node: MappingNode?): Runs {
        val using = node?.text("using")?.trim()
        return when {
            node == null || using == null -> Runs.Unknown(null)
            using == "composite" -> Runs.Composite(steps(node.get("steps") as? SequenceNode))
            using == "docker" -> Runs.Docker(node.text("image"))
            NODE.matches(using) -> Runs.JavaScript(using)
            else -> Runs.Unknown(using)
        }
    }

    private fun steps(node: SequenceNode?): List<Step> =
        node?.value.orEmpty().mapNotNull {
            (it as? MappingNode)?.let(::step)
        }

    private fun step(node: MappingNode): Step? {
        val run = node.text("run")
        val uses = node.text("uses")
        val action =
            when {
                // Like the workflow parser's contract: a step with both counts as `run`.
                run != null -> StepAction.Run(firstLine(run))

                uses != null -> StepAction.Uses(uses, UsesReference.parse(uses, UsesSite.STEP))

                else -> return null
            }
        return Step(node.text("id"), node.text("name"), node.startMark.line + 1, action, node.text("if"))
    }

    private fun firstLine(script: String) =
        script
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

    private fun MappingNode.get(key: String): Node? =
        value.firstOrNull { (it.keyNode as? ScalarNode)?.value == key }?.valueNode

    private fun MappingNode.text(key: String): String? = (get(key) as? ScalarNode)?.value

    private fun MappingNode.mapping(key: String): MappingNode? = get(key) as? MappingNode

    private fun reason(e: RuntimeException): String {
        val text =
            if (e is MarkedYAMLException) {
                // The line first: a box cuts long labels at their end.
                val line = (e.problemMark ?: e.contextMark)?.let { "line ${it.line + 1}: " }.orEmpty()
                line + (e.problem ?: e.context ?: "invalid YAML")
            } else {
                e.message
                    ?.lineSequence()
                    ?.firstOrNull()
                    ?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            }
        return if (text.length > MAX_REASON) text.take(MAX_REASON - 1) + "…" else text
    }

    private val NODE = Regex("""node\d+""")
    private const val MAX_REASON = 80
}

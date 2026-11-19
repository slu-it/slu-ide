package dev.sluide.texttypes.rules

import dev.sluide.texttypes.TextTypeSource
import dev.sluide.texttypes.TextTypes
import dev.sluide.texttypes.TypeScore

/**
 * Bicep by a `resource <name> '<Provider>@<api version>'` line, a `targetScope =` line, or two `param`/`output`
 * lines with a Bicep type.
 */
internal object BicepRule : TextTypeSource {
    const val RESOURCE_SCORE = 95
    const val TARGET_SCOPE_SCORE = 90
    const val PARAMS_SCORE = 70

    private val resource = Signal("""^resource[ \t]++\w++[ \t]++'[\w.]++/[\w./]++@\d{4}-\d{2}-\d{2}(?:-preview)?'""", 1)
    private val targetScope = Signal("""^targetScope[ \t]*+=[ \t]*+'""", 1)
    private val param =
        Regex("""^(?:param|output)[ \t]++\w++[ \t]++(?:string|int|bool|object|array)\b""", RegexOption.MULTILINE)

    override fun scores(text: String): List<TypeScore> {
        val score =
            when {
                resource.foundIn(text) -> RESOURCE_SCORE
                targetScope.foundIn(text) -> TARGET_SCOPE_SCORE
                param.findAll(text).take(2).count() == 2 -> PARAMS_SCORE
                else -> return emptyList()
            }
        return listOf(TypeScore(TextTypes.BICEP, score))
    }
}

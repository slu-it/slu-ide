package dev.sluide.scratches

import dev.sluide.scratches.generators.ID_GENERATORS
import dev.sluide.scratches.generators.SECRET_GENERATORS
import dev.sluide.scratches.generators.TIME_GENERATORS
import dev.sluide.scratches.generators.UUID_GENERATORS
import java.security.SecureRandom
import java.time.Clock
import java.time.ZoneId
import java.util.random.RandomGenerator

/** The headers of the generated scratch file picker, in this order. */
enum class GeneratorGroup(
    val title: String,
) {
    UUIDS("UUIDs"),
    IDS("IDs"),
    SECRETS("Secrets"),
    TIME("Time"),
}

/** What a generator draws on: the [random] source, the [clock] for "now" and the [zone] for local times. */
class GeneratorContext(
    val random: RandomGenerator = SecureRandom(),
    val clock: Clock = Clock.systemUTC(),
    val zone: ZoneId = ZoneId.systemDefault(),
)

/**
 * An entry of File > New Generated Scratch File (ADR 0114): its [group] and shown [name], the new file's [baseName] and
 * [extension] (without dot), and the content it [generate]s, ending with a line break.
 */
class ScratchGenerator(
    val group: GeneratorGroup,
    val name: String,
    val baseName: String,
    val extension: String = "txt",
    val generate: (GeneratorContext) -> String,
) {
    /** The name of the first file of this generator, e.g. `uuid-v7.txt`. */
    val fileName: String get() = "$baseName.$extension"

    override fun toString(): String = name
}

/** How many values a list generator writes, one per line. */
const val GENERATED_VALUES = 50

/** The generators the picker offers, grouped and in this order. */
val SCRATCH_GENERATORS: List<ScratchGenerator> = UUID_GENERATORS + ID_GENERATORS + SECRET_GENERATORS + TIME_GENERATORS

/**
 * The [generators] whose name, group title or file name contains [text] ignoring case, after trimming it; all of them
 * for empty [text].
 */
fun matchingGenerators(
    generators: List<ScratchGenerator>,
    text: String?,
): List<ScratchGenerator> {
    val wanted = text.orEmpty().trim()
    return generators.filter {
        wanted.isEmpty() ||
            it.name.contains(wanted, true) ||
            it.group.title.contains(wanted, true) ||
            it.fileName.contains(wanted, true)
    }
}

/** [GENERATED_VALUES] values from [value], one per line, with a final line break. */
internal fun lines(value: () -> String): String =
    buildString { repeat(GENERATED_VALUES) { append(value()).append('\n') } }

package dev.sluide.converting.model

import dev.sluide.converting.ConversionOption
import dev.sluide.converting.FormatFamily
import dev.sluide.converting.FormatMatch
import dev.sluide.converting.OptionValues
import dev.sluide.converting.ReadResult
import dev.sluide.converting.TextFormat
import dev.sluide.converting.WriteResult
import java.util.concurrent.RejectedExecutionException
import kotlin.time.Duration

internal class FakeFormat(
    override val id: String,
    override val extensions: List<String> = listOf(id),
) : TextFormat {
    override val name = id.uppercase()
    override val language: String? = null

    override fun toString() = id
}

internal val upper = ConversionOption.Toggle("upper", "Upper case", default = false)
internal val trim = ConversionOption.Toggle("trim", "Trim", default = false)
internal val indent = ConversionOption.Choice("indent", "Indent", listOf("2", "4"), default = "2")

/**
 * A family whose texts are `<format id>:<content>`: a text with another format's prefix or with a `!` (at its offset)
 * does not read, `#` in the content gives a read note, a content starting with `[` cannot be written in [rejecting]
 * formats. [detector] replaces the default detection (score 50 for the format of the prefix).
 */
internal class FakeFamily(
    override val name: String,
    override val formats: List<FakeFormat>,
    private val rejecting: Set<String> = emptySet(),
    private val readOptions: Map<String, List<ConversionOption>> = emptyMap(),
    private val writeOptions: Map<String, List<ConversionOption>> = emptyMap(),
    private val detector: ((String) -> List<FormatMatch>?)? = null,
) : FormatFamily<String> {
    var reads = 0

    fun format(id: String): FakeFormat = formats.first { it.id == id }

    override fun detect(text: String): List<FormatMatch> =
        detector?.invoke(text)
            ?: formats.filter { text.startsWith("${it.id}:") }.map { FormatMatch(it, 50) }

    override fun readOptions(format: TextFormat): List<ConversionOption> = readOptions[format.id].orEmpty()

    override fun writeOptions(format: TextFormat): List<ConversionOption> = writeOptions[format.id].orEmpty()

    override fun read(
        text: String,
        format: TextFormat,
        options: OptionValues,
    ): ReadResult<String> {
        reads++
        if ('!' in text) return ReadResult.Failed("unexpected !", text.indexOf('!'))
        if (formats.any { it.id != format.id && text.startsWith("${it.id}:") }) {
            return ReadResult.Failed("not ${format.name}", null)
        }
        val content = text.removePrefix("${format.id}:").let { if (options.flag(trim)) it.trim() else it }
        if (content == "overflow") throw StackOverflowError()
        return ReadResult.Read(content, if ('#' in content) listOf("1 comment dropped") else emptyList())
    }

    override fun write(
        content: String,
        format: TextFormat,
        options: OptionValues,
    ): WriteResult {
        if (content.startsWith("[") && format.id in rejecting) {
            return WriteResult.Failed("${format.name} cannot hold a top-level array")
        }
        if (content == "deep" && format.id in rejecting) throw StackOverflowError("${format.name} overflowed")
        val text = "${format.id}:$content"
        return WriteResult.Written(if (options.flag(upper)) text.uppercase() else text, emptyList())
    }
}

/** A scheduler the test steps; cancelled tasks never run; after [shutdown] it rejects tasks as an executor does. */
internal class ManualScheduler : ConvertingScheduler {
    private var shut = false

    class Task(
        val delay: Duration,
        val run: () -> Unit,
    ) {
        var cancelled = false
        var ran = false
    }

    val tasks = mutableListOf<Task>()

    val waiting: List<Task> get() = tasks.filter { !it.cancelled && !it.ran }

    override fun schedule(
        delay: Duration,
        task: () -> Unit,
    ): () -> Unit {
        if (shut) throw RejectedExecutionException("shut down")
        val scheduled = Task(delay, task)
        tasks += scheduled
        return { scheduled.cancelled = true }
    }

    override fun shutdown() {
        shut = true
    }

    fun run(task: Task) {
        task.ran = true
        task.run()
    }
}

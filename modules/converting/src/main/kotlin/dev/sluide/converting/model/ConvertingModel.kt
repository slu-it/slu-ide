package dev.sluide.converting.model

import dev.sluide.converting.ConversionOption
import dev.sluide.converting.FormatFamily
import dev.sluide.converting.OptionValues
import dev.sluide.converting.TextFormat
import dev.sluide.converting.WriteResult
import java.io.IOException
import java.nio.charset.CharacterCodingException
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.util.EnumMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The state behind the Converting tool (ADR 0115): two editable sides with a format each, the one edited last is the
 * source and the other one, the target, follows it. Called on the FX thread but FX-free: detection and conversion run
 * on [scheduler], an edit's after [delay]; their results come back through [dispatch] (the FX thread in the
 * application), and only the latest one is applied. Nothing is stored.
 */
internal class ConvertingModel(
    families: List<FormatFamily<*>>,
    private val dispatch: (Runnable) -> Unit,
    private val delay: Duration = 150.milliseconds,
    private val scheduler: ConvertingScheduler = ConvertingScheduler.singleThread(),
) {
    private val families = Families(families)
    private val texts = EnumMap<Side, String>(Side.entries.associateWith { "" })

    /** The user's picks; the model never pins a format itself. */
    private val picks = EnumMap<Side, Pick>(Side.entries.associateWith { Pick.Detect })

    /** The format of a side the user did not pin: the detected one on the source, the suggested one on the target. */
    private val shown =
        EnumMap<Side, TextFormat?>(mapOf(Side.LEFT to null, Side.RIGHT to this.families.formats.firstOrNull()))

    var source: Side = Side.LEFT
        private set
    val target: Side get() = source.other

    var options: OptionValues = OptionValues()
        private set
    var hint: Hint? = null
        private set

    /** Called once after every state change, on the dispatch thread. */
    var onChange: () -> Unit = {}

    // What the last conversion gave: the source's error, the target's notes or error, the content read.
    private var readError: Status.Failure? = null
    private var writeError: String? = null
    private var notes: List<String> = emptyList()
    private var content: ReadContent<*>? = null
    private var unavailable: Map<String, String> = emptyMap()

    /** The source's format shown so far, to tell whether a conversion changes it. */
    private var shownSource: TextFormat? = null

    /** The formats used as target, oldest first. */
    private val history = mutableListOf<TextFormat>()
    private var lastPinned: TextFormat? = null
    private var openedName: String? = null

    private var generation = 0
    private var cancel: () -> Unit = {}
    private var disposed = false

    fun text(side: Side): String = texts.getValue(side)

    fun pick(side: Side): Pick = picks.getValue(side)

    /** The format the user pinned on [side], else the detected or suggested one; null while there is none. */
    fun format(side: Side): TextFormat? = (pick(side) as? Pick.Pinned)?.format ?: shown[side]

    fun status(side: Side): Status =
        if (side == source) {
            readError ?: Status.Info(formatLabel(side), text(side).length, emptyList())
        } else {
            writeError?.let { Status.Failure(it, null) } ?: Status.Info(formatLabel(side), text(side).length, notes)
        }

    /** The formats of the source's family (all formats without a source format), see [checkTargets]. */
    val targetChoices: List<TargetChoice>
        get() {
            val formats = format(source)?.let { families.familyOf(it).formats } ?: families.formats
            return formats.map { TargetChoice(it, unavailable[it.id]) }
        }

    /** The read options of the source's format, then the write options of the target's, each id once. */
    fun visibleOptions(): List<ConversionOption> {
        val read = format(source)?.let { families.familyOf(it).readOptions(it) }.orEmpty()
        val write = format(target)?.let { families.familyOf(it).writeOptions(it) }.orEmpty()
        return (read + write).distinctBy { it.id }
    }

    /** The format with [extension] (with or without the dot), across all families. */
    fun formatOf(extension: String): TextFormat? = families.formatOf(extension)

    /** [side]'s text was edited: it becomes the source and is converted after the debounce. */
    fun edit(
        side: Side,
        text: String,
    ) {
        // The view sets the target's text the model gave it; that is no edit.
        if (disposed || texts.getValue(side) == text) return
        makeSource(side)
        texts[side] = text
        reconvert(delay)
        onChange()
    }

    /** Pins a format or detects it; re-reads the source or re-writes the target at once. */
    fun choose(
        side: Side,
        pick: Pick,
    ) {
        if (disposed || pick(side) == pick) return
        if (pick is Pick.Pinned) lastPinned = pick.format
        if (pick == Pick.Detect) shown[side] = format(side)
        picks[side] = pick
        reconvert()
        onChange()
    }

    fun applyHint() {
        val hint = hint ?: return
        choose(source, Pick.Pinned(hint.format))
    }

    fun setOption(
        id: String,
        value: Any,
    ) {
        if (disposed) return
        options = options.with(id, value)
        reconvert()
        onChange()
    }

    /**
     * Exchanges the texts and picks of the sides; the source moves with its text. A conversion still to come applies
     * as well, as results go to the source and the target, whichever side they are on.
     */
    fun swap() {
        if (disposed) return
        swap(texts)
        swap(picks)
        swap(shown)
        source = target
        onChange()
    }

    /** "Open Selection in Converting": [text] on the left as the source, pinned to [format] or detected. */
    fun open(
        text: String,
        format: TextFormat?,
    ) {
        load(Side.LEFT, text, format, name = null)
    }

    /** Opens a file of up to 1 MiB on [side], pinned to the format of its extension; null on success, else why not. */
    fun openFile(
        side: Side,
        file: Path,
    ): String? {
        if (disposed) return null
        val name = file.fileName?.toString() ?: file.toString()
        val text =
            try {
                if (Files.size(file) > MAX_FILE_SIZE) return "$name is larger than 1 MiB"
                Files.readString(file)
            } catch (_: NoSuchFileException) {
                return "Cannot read $name: file not found"
            } catch (_: AccessDeniedException) {
                return "Cannot read $name: access denied"
            } catch (_: CharacterCodingException) {
                return "Cannot read $name: not UTF-8 text"
            } catch (e: IOException) {
                return "Cannot read $name: ${e.message ?: e.javaClass.simpleName}"
            }
        val extension = name.substringAfterLast('.', "")
        load(side, text, extension.takeIf { it.isNotEmpty() }?.let(families::formatOf), baseName(name))
        return null
    }

    /** The suggested name for Save as on [side]: the opened file's base name and the first extension of its format. */
    fun fileName(side: Side): String =
        (openedName ?: "converted") + "." + (format(side)?.extensions?.firstOrNull() ?: "txt")

    /** Tries the source's content in every format of [targetChoices] in the background and marks those failing. */
    fun checkTargets() {
        if (disposed) return
        val read = content ?: return
        val formats = targetChoices.map { it.format }
        val options = options
        scheduler.schedule(Duration.ZERO) {
            val reasons =
                formats.mapNotNull { format ->
                    unavailableReason(read, format, options)?.let { format.id to it }
                }
            dispatch(
                Runnable {
                    if (!disposed && content === read) {
                        unavailable = reasons.toMap()
                        onChange()
                    }
                },
            )
        }
    }

    /** Drops results still to come and stops the background thread, as the window closes; later calls do nothing. */
    fun dispose() {
        disposed = true
        cancel()
        scheduler.shutdown()
    }

    private fun load(
        side: Side,
        text: String,
        format: TextFormat?,
        name: String?,
    ) {
        if (disposed) return
        makeSource(side)
        texts[side] = text
        if (format != null) lastPinned = format
        picks[side] = format?.let(Pick::Pinned) ?: Pick.Detect
        openedName = name
        reconvert()
        onChange()
    }

    private fun makeSource(side: Side) {
        if (side == source) return
        shownSource = format(side)
        source = side
        readError = null
        writeError = null
        notes = emptyList()
        hint = null
        content = null
        unavailable = emptyMap()
    }

    private fun reconvert(delay: Duration = Duration.ZERO) {
        cancel()
        val generation = ++generation
        val input =
            ConversionInput(
                text = text(source),
                pick = pick(source),
                previousSource = shownSource,
                target = format(target),
                targetPinned = pick(target) is Pick.Pinned,
                history = history.toList(),
                lastPinned = lastPinned,
                options = options,
            )
        cancel =
            scheduler.schedule(delay) {
                val outcome =
                    try {
                        convert(families, input)
                    } catch (e: Throwable) {
                        // A family's bug, even a StackOverflowError, must not end the conversion silently.
                        val failure = ConversionOutcome.Step.ReadFailed(messageOf(e), null)
                        ConversionOutcome(input.pinned ?: input.previousSource, input.target, failure)
                    }
                dispatch(Runnable { apply(generation, outcome) })
            }
    }

    private fun apply(
        generation: Int,
        outcome: ConversionOutcome,
    ) {
        if (disposed || generation != this.generation) return
        if (pick(source) == Pick.Detect) shown[source] = outcome.source
        shownSource = outcome.source
        outcome.target?.let(::useTarget)
        hint = outcome.hint?.let(::Hint)
        unavailable = emptyMap()
        when (val step = outcome.step) {
            ConversionOutcome.Step.Blank -> {
                written("", emptyList(), null)
            }

            ConversionOutcome.Step.Undetected -> {
                readError = Status.Failure("No format detected", null)
                content = null
            }

            is ConversionOutcome.Step.ReadFailed -> {
                readError = Status.Failure(step.message, step.position)
                content = null
            }

            is ConversionOutcome.Step.Written -> {
                written(step.text, step.notes, step.content)
            }

            is ConversionOutcome.Step.WriteFailed -> {
                readError = null
                writeError = step.message
                notes = emptyList()
                content = step.content
                outcome.target?.let { unavailable = mapOf(it.id to step.message) }
            }
        }
        onChange()
    }

    private fun written(
        text: String,
        notes: List<String>,
        content: ReadContent<*>?,
    ) {
        texts[target] = text
        readError = null
        writeError = null
        this.notes = notes
        this.content = content
    }

    /** The target shows [chosen]; a target the user pinned to another family's format than the source's detects. */
    private fun useTarget(chosen: TextFormat) {
        if (chosen.id != format(target)?.id) picks[target] = Pick.Detect
        shown[target] = chosen
        history.removeAll { it.id == chosen.id }
        history += chosen
    }

    private fun formatLabel(side: Side): String? =
        format(side)?.let { format ->
            if (side == source && pick(side) == Pick.Detect) "${format.name} · detected" else format.name
        }

    private fun <V> swap(map: EnumMap<Side, V>) {
        val left = map.getValue(Side.LEFT)
        map[Side.LEFT] = map.getValue(Side.RIGHT)
        map[Side.RIGHT] = left
    }

    companion object {
        const val MAX_FILE_SIZE = 1024 * 1024

        private fun baseName(fileName: String): String = fileName.substringBeforeLast('.').ifEmpty { fileName }

        private fun messageOf(e: Throwable): String = e.message ?: e.javaClass.simpleName

        /** Why [format] cannot hold [read], or null if it can. */
        private fun unavailableReason(
            read: ReadContent<*>,
            format: TextFormat,
            options: OptionValues,
        ): String? =
            try {
                (read.write(format, options) as? WriteResult.Failed)?.message
            } catch (e: Throwable) {
                // A family's bug, even a StackOverflowError, must not end the check silently.
                messageOf(e)
            }
    }
}

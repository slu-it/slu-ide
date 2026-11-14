package dev.sluide.barcodes

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.barcodes.engine.CodeFormat
import dev.sluide.barcodes.engine.GenerateOptions
import dev.sluide.barcodes.engine.QrEcc
import dev.sluide.projectdata.ProjectDataStore
import org.springframework.beans.factory.DisposableBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import tools.jackson.databind.node.ObjectNode
import java.time.Duration
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicReference

/**
 * The last format and options of the Barcodes tool, user-wide (ADR 0174): the top-level field `barcodes` of
 * `shared.json` (ADR 0101), e.g. `{"format": "AZTEC", "sizePx": 1024, "foreground": "#000000ff"}`. Only values that
 * differ from the defaults ([GenerateOptions] and QR Code) are written, so a default never pins itself. The text is
 * never stored.
 *
 * Read once on first use, then kept in memory; [save] changes the values at once and writes off the FX thread, saves
 * within 300 ms of each other ending in one write; the app quitting ([destroy]) writes what is pending.
 *
 * A field of the wrong type reads as its default; numbers are clamped to their ranges. Other fields of `barcodes`
 * stay as they are; a `barcodes` that is no JSON object is replaced.
 */
@Component
class BarcodeOptionsStore internal constructor(
    private val store: ProjectDataStore,
    private val writeDelay: Duration,
    private val writer: Executor,
) : DisposableBean {
    @Autowired
    constructor(
        store: ProjectDataStore,
    ) : this(store, Duration.ofMillis(300), NamedThreads.single("sluide-barcodes-prefs"))

    private data class Values(
        val format: CodeFormat,
        val options: GenerateOptions,
    )

    @Volatile
    private var current: Values? = null
    private val pending = AtomicReference<Values?>()

    /** Held while taking and writing the pending values, so that [destroy] waits for a write in progress. */
    private val writeLock = Any()

    @Volatile
    private var closed = false

    @Volatile
    private var warned = false

    /** The saved format (a generatable one) and options; the defaults for what is missing or invalid. */
    internal fun load(): Pair<CodeFormat, GenerateOptions> = values().let { it.format to it.options }

    /**
     * Remembers [format] and [options] at once and writes them off the FX thread shortly after: saves within the delay
     * (the steps of a spinner) end in one write of the latest values. After [destroy], every save writes at once.
     */
    internal fun save(
        format: CodeFormat,
        options: GenerateOptions,
    ) {
        val values = Values(format, options)
        current = values
        if (closed) {
            pending.set(values)
            flush()
        } else if (pending.getAndSet(values) == null) {
            writer.execute {
                try {
                    Thread.sleep(writeDelay)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                flush()
            }
        }
    }

    /** The app quits: the writer is a daemon thread, so pending values are written now; later saves write at once. */
    override fun destroy() {
        closed = true
        flush()
    }

    private fun flush() {
        synchronized(writeLock) { pending.getAndSet(null)?.let(::write) }
    }

    private fun values(): Values = current ?: read().also { current = it }

    private fun read(): Values {
        val node =
            store.readShared()?.get(FIELD) as? ObjectNode ?: return Values(CodeFormat.QR_CODE, GenerateOptions())
        val defaults = GenerateOptions()
        val format =
            node
                .get(FORMAT)
                ?.takeIf { it.isString }
                ?.asString()
                ?.let { name -> CodeFormat.entries.firstOrNull { it.name == name && it.generatable } }
                ?: CodeFormat.QR_CODE
        val options =
            GenerateOptions(
                qrEcc =
                    node
                        .get(QR_ECC)
                        ?.takeIf { it.isString }
                        ?.asString()
                        ?.let { name -> QrEcc.entries.firstOrNull { it.name == name } } ?: defaults.qrEcc,
                aztecEccPercent = int(node, AZTEC, AZTEC_RANGE) ?: defaults.aztecEccPercent,
                pdf417Level = int(node, PDF417, PDF417_RANGE) ?: defaults.pdf417Level,
                sizePx = int(node, SIZE, SIZE_RANGE) ?: defaults.sizePx,
                quietZone = int(node, QUIET_ZONE, QUIET_RANGE),
                foreground = color(node, FOREGROUND) ?: defaults.foreground,
                background = color(node, BACKGROUND) ?: defaults.background,
            )
        return Values(format, options)
    }

    private fun write(values: Values) {
        val (format, options) = values
        val defaults = GenerateOptions()
        val written =
            store.updateShared { root ->
                val node = root.get(FIELD) as? ObjectNode ?: root.objectNode()
                node.putOrRemove(FORMAT, format.takeIf { it.generatable && it != CodeFormat.QR_CODE }?.name)
                node.putOrRemove(QR_ECC, options.qrEcc.takeIf { it != defaults.qrEcc }?.name)
                node.putOrRemove(AZTEC, options.aztecEccPercent.takeIf { it != defaults.aztecEccPercent })
                node.putOrRemove(PDF417, options.pdf417Level.takeIf { it != defaults.pdf417Level })
                node.putOrRemove(SIZE, options.sizePx.takeIf { it != defaults.sizePx })
                node.putOrRemove(QUIET_ZONE, options.quietZone)
                node.putOrRemove(FOREGROUND, options.foreground.takeIf { it != defaults.foreground }?.let(::hex))
                node.putOrRemove(BACKGROUND, options.background.takeIf { it != defaults.background }?.let(::hex))
                if (node.isEmpty) root.remove(FIELD) else root.set(FIELD, node)
            }
        if (!written && !warned) {
            warned = true
            System
                .getLogger(BarcodeOptionsStore::class.java.name)
                .log(System.Logger.Level.WARNING, "Barcode options not saved: the shared data file cannot be written")
        }
    }

    private fun int(
        node: ObjectNode,
        name: String,
        range: IntRange,
    ): Int? =
        node
            .get(name)
            ?.takeIf { it.isIntegralNumber }
            ?.asLong()
            ?.coerceIn(range.first.toLong(), range.last.toLong())
            ?.toInt()

    /** `#rrggbbaa` as ARGB, or null when absent or malformed. */
    private fun color(
        node: ObjectNode,
        name: String,
    ): Int? {
        val text = node.get(name)?.takeIf { it.isString }?.asString() ?: return null
        if (!COLOR.matches(text)) return null
        val rgba = text.substring(1).toLong(16)
        val rgb = (rgba shr 8).toInt() and 0xFFFFFF
        val alpha = rgba.toInt() and 0xFF
        return (alpha shl 24) or rgb
    }

    private fun hex(argb: Int): String = "#%06x%02x".format(argb and 0xFFFFFF, argb ushr 24)

    private fun ObjectNode.putOrRemove(
        name: String,
        value: Any?,
    ) {
        when (value) {
            null -> remove(name)
            is Int -> put(name, value)
            else -> put(name, value.toString())
        }
    }

    internal companion object {
        const val FIELD = "barcodes"
        private const val FORMAT = "format"
        private const val QR_ECC = "qrEcc"
        private const val AZTEC = "aztecEccPercent"
        private const val PDF417 = "pdf417Level"
        private const val SIZE = "sizePx"
        private const val QUIET_ZONE = "quietZone"
        private const val FOREGROUND = "foreground"
        private const val BACKGROUND = "background"
        private val COLOR = Regex("#[0-9a-fA-F]{8}")

        val SIZE_RANGE = 256..4096
        val QUIET_RANGE = 0..10
        val AZTEC_RANGE = 5..95
        val PDF417_RANGE = 0..8
    }
}

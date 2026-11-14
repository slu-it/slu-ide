package dev.sluide.barcodes.model

import dev.sluide.barcodes.BarcodeOptionsStore
import dev.sluide.barcodes.engine.ArgbImage
import dev.sluide.barcodes.engine.CodeFormat
import dev.sluide.barcodes.engine.DecodedCode
import dev.sluide.barcodes.engine.GenerateOptions
import dev.sluide.barcodes.engine.Generated
import dev.sluide.barcodes.engine.Generator
import dev.sluide.barcodes.engine.colorWarning

/** Which side the state of the Barcodes tool comes from (ADR 0174): the typed text or a read image. */
internal enum class Source { TEXT, IMAGE }

/**
 * The state of one window's Barcodes tool: the text, the format and options, the generated code, and what was read
 * from an image. Free of JavaFX, used on the FX thread; the view listens ([addListener]) and shows it.
 *
 * [editText], [setFormat] and [setOptions] are the user's requests for a generated code: the text becomes the
 * [source], the code is made again, and they make a pending read stale. [imageRead] and [selectCode] are the app's own
 * changes of text and format: they do not generate.
 */
internal class BarcodeModel(
    private val store: BarcodeOptionsStore,
) {
    var source: Source = Source.TEXT
        private set
    var text: String = ""
        private set
    var format: CodeFormat
        private set
    var options: GenerateOptions
        private set

    /** The last successful code; kept while a [failure] shows, so that the view can grey it. */
    var generated: Generated.Code? = null
        private set
    var failure: Generated.Failure? = null
        private set
    var image: ArgbImage? = null
        private set
    var codes: List<DecodedCode> = emptyList()
        private set
    var selectedCode: Int = 0
        private set
    var imageStatus: String? = null
        private set

    /** A warning for the foreground and background colours, or null. */
    val colorWarning: String? get() = colorWarning(options.foreground, options.background)

    private var sequence = 0L
    private val listeners = mutableListOf<() -> Unit>()

    init {
        val (savedFormat, savedOptions) = store.load()
        format = savedFormat
        options = savedOptions
    }

    /** Calls [listener] after every change of the state; returns the function that removes it. */
    fun addListener(listener: () -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    fun editText(text: String) {
        userRequest()
        this.text = text
        regenerate()
        changed()
    }

    fun setFormat(format: CodeFormat) {
        userRequest()
        this.format = format
        store.save(format, options)
        regenerate()
        changed()
    }

    fun setOptions(options: GenerateOptions) {
        userRequest()
        this.options = options
        store.save(format, options)
        regenerate()
        changed()
    }

    /** Starts a read; its result is applied only while no newer read or user request has begun. */
    fun beginRead(): Long = ++sequence

    fun imageRead(
        seq: Long,
        image: ArgbImage,
        codes: List<DecodedCode>,
        origin: String,
    ) {
        if (seq != sequence) return
        if (codes.isEmpty()) {
            imageStatus = "No barcode found"
        } else {
            source = Source.IMAGE
            this.image = image
            this.codes = codes
            generated = null
            failure = null
            show(0)
            val count = if (codes.size == 1) "1 code" else "${codes.size} codes"
            imageStatus = "Read from $origin · $count · ${codes[0].format.title}"
        }
        changed()
    }

    fun readFailed(
        seq: Long,
        message: String,
    ) {
        if (seq != sequence) return
        imageStatus = message
        changed()
    }

    fun selectCode(index: Int) {
        if (index !in codes.indices) return
        show(index)
        changed()
    }

    /** Takes code [index] as text, and its format as the picker's if it can be generated. */
    private fun show(index: Int) {
        selectedCode = index
        val code = codes[index]
        text = code.text
        if (code.format.generatable && code.format != format) {
            format = code.format
            store.save(format, options)
        }
    }

    private fun userRequest() {
        sequence++
        source = Source.TEXT
        image = null
        codes = emptyList()
        selectedCode = 0
        imageStatus = null
    }

    private fun regenerate() {
        when (val result = Generator.generate(text, format, options)) {
            null -> {
                generated = null
                failure = null
            }

            is Generated.Code -> {
                generated = result
                failure = null
            }

            is Generated.Failure -> {
                failure = result
            }
        }
    }

    private fun changed() = listeners.toList().forEach { it() }
}

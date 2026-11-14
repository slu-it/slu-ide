package dev.sluide.barcodes.ui

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.barcodes.engine.ArgbImage
import dev.sluide.barcodes.engine.DecodedCode
import dev.sluide.barcodes.engine.Reader
import dev.sluide.barcodes.model.BarcodeModel
import javafx.application.Platform
import javafx.scene.input.Clipboard
import java.nio.file.Path

/**
 * Starts the reads of the Barcodes view: from the clipboard, a file or a drop. Pixels of a clipboard or a drag are
 * taken on the FX thread (they must be); file decoding and [Reader.read] run on one named thread, and the result
 * comes back through [Platform.runLater] as [BarcodeModel.imageRead] or [BarcodeModel.readFailed]. The model drops the
 * result when a newer read or an edit came first. [reading] is called when a read starts; [noted] with a note to add
 * to the status of a read that was applied. Whatever the work throws, an outcome is always posted, so the status
 * never stays on "Reading…".
 */
internal class ImageInput(
    private val model: BarcodeModel,
    private val reading: () -> Unit,
    private val noted: (String) -> Unit,
    private val reader: (ArgbImage) -> List<DecodedCode> = Reader::read,
) {
    private val log = System.getLogger(ImageInput::class.java.name)
    private val executor = NamedThreads.single("barcodes-reader")

    /** Reads the image on the system clipboard. */
    fun readClipboard() = readBoard(Clipboard.getSystemClipboard(), "clipboard", "The clipboard holds no image.")

    /** Reads the image on [board] (a clipboard or a drag board). */
    fun readBoard(
        board: Clipboard,
        origin: String,
        missing: String,
    ) {
        val seq = model.beginRead()
        if (!board.hasImage()) return model.readFailed(seq, missing)
        when (val pixels = argbOf(board.image)) {
            is ImageLoad.Failed -> model.readFailed(seq, pixels.message)
            is ImageLoad.Loaded -> start(seq, origin, null) { pixels }
        }
    }

    /** Reads the image file [path]; [note] is added to the status when the read is applied. */
    fun readFile(
        path: Path,
        note: String? = null,
    ) {
        val seq = model.beginRead()
        start(seq, path.fileName?.toString() ?: path.toString(), note) { loadImageFile(path) }
    }

    private fun start(
        seq: Long,
        origin: String,
        note: String?,
        load: () -> ImageLoad,
    ) {
        reading()
        executor.execute {
            var outcome: Outcome = Outcome.Failed(UNREADABLE)
            try {
                outcome =
                    when (val loaded = load()) {
                        is ImageLoad.Loaded -> Outcome.Read(loaded.image, reader(loaded.image))
                        is ImageLoad.Failed -> Outcome.Failed(loaded.message)
                    }
            } catch (e: Throwable) {
                // Anything, an OutOfMemoryError too: the outcome below is the failure message.
                logReadFailure(log, e)
            } finally {
                Platform.runLater { apply(seq, origin, note, outcome) }
            }
        }
    }

    private fun apply(
        seq: Long,
        origin: String,
        note: String?,
        outcome: Outcome,
    ) {
        when (outcome) {
            is Outcome.Failed -> {
                model.readFailed(seq, outcome.message)
            }

            is Outcome.Read -> {
                model.imageRead(seq, outcome.image, outcome.codes, origin)
                if (note != null && model.image === outcome.image) noted(note)
            }
        }
    }

    private sealed interface Outcome {
        class Read(
            val image: ArgbImage,
            val codes: List<DecodedCode>,
        ) : Outcome

        class Failed(
            val message: String,
        ) : Outcome
    }
}

package dev.sluide.ide.scratches

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.texttypes.TextType
import javafx.application.Platform

/** The message when File → New Scratch from Clipboard finds no text. */
internal const val NO_CLIPBOARD_TEXT = "The clipboard holds no text."

/**
 * File → New Scratch from Clipboard without the controls (ADR 0188): the clipboard's [text] is checked on the calling
 * (JavaFX) thread, its type is detected by [detect] on [runInBackground], and [openText] gets the extension and the
 * text on [dispatch], the JavaFX thread.
 */
internal class ClipboardScratch(
    private val detect: (String) -> TextType,
    private val showError: (String) -> Unit,
    private val openText: (extension: String, text: String) -> Unit,
    private val runInBackground: (Runnable) -> Unit = { NamedThreads.virtual("sluide-clipboard-scratch", it) },
    private val dispatch: (Runnable) -> Unit = Platform::runLater,
) {
    /** Creates the scratch from [text]; the error message and nothing else if it is null or blank. */
    fun create(text: String?) {
        if (text.isNullOrBlank()) {
            showError(NO_CLIPBOARD_TEXT)
            return
        }
        runInBackground {
            // The detector never answers No extension; txt is the fallback if it ever did.
            val extension = detect(text).extension ?: "txt"
            dispatch { openText(extension, text) }
        }
    }
}

package dev.sluide.fileviewers.preview

/** The result of formatting a file as plain text, for the Copy Formatted button of a preview. */
internal sealed interface Formatted {
    /** The formatted [text]. */
    class Text(
        val text: String,
    ) : Formatted

    /** Nothing to copy; [reason] is the message the preview's error notice shows, e.g. "Line 3, column 4: …". */
    class Failed(
        val reason: String,
    ) : Formatted
}

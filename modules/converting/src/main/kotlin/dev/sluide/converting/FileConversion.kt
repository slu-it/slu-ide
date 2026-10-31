package dev.sluide.converting

/** What converting a file for the explorer's "Convert to" gave. */
sealed interface FileConversion {
    /** [notes] tell what the target format could not hold. */
    data class Converted(
        val text: String,
        val notes: List<String>,
    ) : FileConversion

    /** [message] is a sentence for the user, with line and column where reading failed. */
    data class Failed(
        val message: String,
    ) : FileConversion
}

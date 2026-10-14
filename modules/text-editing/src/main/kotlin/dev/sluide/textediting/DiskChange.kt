package dev.sluide.textediting

/** What an editor does when the file it edits changed on disk ([decide]). */
enum class DiskChange {
    /** Nothing: the file holds what the editor saved last, e.g. the file watcher reporting the editor's own save. */
    IGNORE,

    /**
     * Take the new content as the saved one: the editor has no unsaved changes, so it shows the new content, or it
     * holds exactly that content already, so it has no unsaved changes anymore.
     */
    RELOAD,

    /** Ask the user: the editor has unsaved changes, and the file changed since it was saved or read. */
    CONFLICT,
    ;

    companion object {
        /**
         * What to do with [disk], the file's new content, in an editor that last read or saved [saved], holds
         * [buffer] and has unsaved changes if [dirty]. Line endings do not count as a change.
         */
        fun decide(
            disk: String,
            saved: String,
            buffer: String,
            dirty: Boolean,
        ): DiskChange {
            val text = LineEnding.normalize(disk)
            return when {
                text == LineEnding.normalize(saved) -> IGNORE
                !dirty || text == LineEnding.normalize(buffer) -> RELOAD
                else -> CONFLICT
            }
        }
    }
}

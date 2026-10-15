package dev.sluide.fileviewers

/**
 * A [FileViewer] that also opens text files without an extension by their shebang. [FileViewers] asks it only when
 * no viewer opens the file by its name, so a name always wins over a shebang.
 */
internal interface ShebangViewer : FileViewer {
    /** Whether this viewer opens a script whose shebang names [interpreter] ([shebangInterpreter]). */
    fun opensInterpreter(interpreter: String): Boolean
}

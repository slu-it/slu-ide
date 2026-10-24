package dev.sluide.fileviewers

/** A viewer whose views can run their file (e.g. shell scripts), for the window to offer Run for the files it opens. */
interface RunnableFileViewer : FileViewer

/** A view that can run its file: shows its output and runs it, asking what it needs to ask first. FX thread. */
interface RunnableFileView : FileView {
    fun run()
}

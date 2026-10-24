package dev.sluide.ide.scripts

import dev.sluide.fileviewers.FileView
import dev.sluide.fileviewers.FileViewer
import dev.sluide.fileviewers.RunnableFileView
import dev.sluide.fileviewers.RunnableFileViewer
import javafx.scene.Node
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The explorer's Run: for which files it shows (the explorer itself offers file actions only for one regular file),
 * and that it opens the file's tab before running it there.
 */
class WindowScriptRunsTest {
    private open class Viewer : FileViewer {
        override fun canOpen(file: Path) = true

        override fun open(file: Path): FileView = error("not needed")
    }

    private class Runnable :
        Viewer(),
        RunnableFileViewer

    /** A view that counts its runs; it has no node, which Run never needs. */
    private class View : RunnableFileView {
        var runs = 0
        override val node: Node get() = error("not needed")

        override fun reload() {}

        override fun run() {
            runs++
        }
    }

    private val events = mutableListOf<String>()
    private val view = View()
    private var opens = true

    private val runs =
        WindowScriptRuns(
            viewerFor = {
                when (it.fileName.toString()) {
                    "deploy.sh" -> Runnable()
                    "notes.txt" -> Viewer()
                    else -> null
                }
            },
            open = {
                events += "open ${it.fileName}"
                opens
            },
            viewOf = {
                events += "viewOf ${it.fileName}"
                view
            },
        )

    @Test
    fun `Run applies to files whose viewer can run them`() {
        assertEquals("Run", runs.action.label)
        assertTrue(runs.action.appliesTo(Path.of("/work/deploy.sh")))
        assertFalse(runs.action.appliesTo(Path.of("/work/notes.txt")))
        assertFalse(runs.action.appliesTo(Path.of("/work/blob")), "no viewer")
    }

    @Test
    fun `Run opens the file's tab, then runs its view, but not if it could not be opened`() {
        val script = Path.of("/work/deploy.sh")
        runs.action.perform(script)
        assertEquals(listOf("open deploy.sh", "viewOf deploy.sh"), events)
        assertEquals(1, view.runs)

        opens = false
        runs.action.perform(script)
        assertEquals(1, view.runs)
    }
}

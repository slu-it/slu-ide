package dev.sluide.ide.codeintelligence

import dev.sluide.codeintelligence.SourceCompletion
import dev.sluide.codeintelligence.SourceCompletions
import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.fileviewers.FileView
import dev.sluide.jshell.Completions
import dev.sluide.jshell.JShellFileView
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.Node
import javafx.scene.control.MenuItem
import javafx.scene.layout.Region
import javafx.util.Subscription
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import dev.sluide.jshell.JShellCompletion as Completion

/** The completion of `.jsh` editors: which files, which view it asks, and JShell's answer as the popup's items. */
class JShellCompletionSourceTest {
    private val file = Path.of("/w/deploy.jsh")

    private class Editor(
        file: Path,
    ) : ExtensibleCodeEditor {
        override val file = SimpleObjectProperty(file)
        override val lineCount: Int get() = 1

        override fun lineText(line: Int) = ""

        override val shownLines: IntRange get() = IntRange.EMPTY

        override fun onShownLinesChanged(action: () -> Unit) = Subscription.EMPTY

        override fun addContextMenuItems(items: (EditorMenuTarget) -> List<MenuItem>) {}

        override fun addGutterColumn(
            width: Double,
            column: (paragraph: Int) -> Node?,
        ) {}

        override fun refreshGutter() {}
    }

    /** A JShell view answering every caret with [answer]; the carets it was asked at. */
    private class View(
        private val answer: Completions,
    ) : FileView,
        JShellFileView {
        val carets = mutableListOf<Int>()
        override val node: Node get() = Region()

        override fun reload() {}

        override fun runAll() {}

        override fun complete(caret: Int): CompletableFuture<Completions> {
            carets += caret
            return CompletableFuture.completedFuture(answer)
        }
    }

    private class OtherView : FileView {
        override val node: Node get() = Region()

        override fun reload() {}
    }

    @Test
    fun `the view showing the editor's file answers at the caret, converted for the popup`() {
        val view = View(Completions(4, listOf(Completion("length()", false))))
        val source = JShellCompletionSource { if (it == file) view else null }

        val answer = source.complete(Editor(file), 7)?.get()

        assertEquals(listOf(7), view.carets)
        assertEquals(SourceCompletions(4, listOf(SourceCompletion("length()"))), answer)
    }

    @Test
    fun `without a JShell view of the file there is no completion`() {
        assertNull(JShellCompletionSource { null }.complete(Editor(file), 0))
        assertNull(JShellCompletionSource { OtherView() }.complete(Editor(file), 0))
    }

    @Test
    fun `smart items come first, then the others, each in JShell's order`() {
        val completions =
            Completions(
                3,
                listOf(
                    Completion("wait(", false),
                    Completion("substring(", true),
                    Completion("equals(", false),
                    Completion("length()", true),
                ),
            )

        assertEquals(
            SourceCompletions(3, listOf("substring(", "length()", "wait(", "equals(").map { SourceCompletion(it) }),
            sourceCompletions(completions),
        )
    }

    @ParameterizedTest
    @CsvSource("deploy.jsh, true", "DEPLOY.JSH, true", "deploy.java, false", "jsh, false", "deploy.jsh.txt, false")
    fun `JShell completes jsh files`(
        name: String,
        completes: Boolean,
    ) {
        assertEquals(completes, isJShellFile(Path.of("/w").resolve(name)))
    }
}

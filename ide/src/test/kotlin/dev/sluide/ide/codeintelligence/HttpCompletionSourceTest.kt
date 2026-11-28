package dev.sluide.ide.codeintelligence

import dev.sluide.codeintelligence.SourceCompletion
import dev.sluide.codeintelligence.SourceCompletions
import dev.sluide.fileviewers.EditorMenuTarget
import dev.sluide.fileviewers.ExtensibleCodeEditor
import dev.sluide.httpclient.HttpCompletionItem
import dev.sluide.httpclient.HttpCompletions
import dev.sluide.httpclient.isHttpFile
import javafx.beans.property.SimpleObjectProperty
import javafx.scene.Node
import javafx.scene.control.MenuItem
import javafx.util.Subscription
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

/** The completion of `.http` editors: which files, the triggers, and http-client's answer as the popup's items. */
class HttpCompletionSourceTest {
    private class Editor(
        file: Path,
        override val text: String,
    ) : ExtensibleCodeEditor {
        override val file = SimpleObjectProperty(file)
        override val lineCount: Int get() = 1

        override fun lineText(line: Int) = text

        override val shownLines: IntRange get() = IntRange.EMPTY

        override fun onShownLinesChanged(action: () -> Unit) = Subscription.EMPTY

        override fun addContextMenuItems(items: (EditorMenuTarget) -> List<MenuItem>) {}

        override fun addGutterColumn(
            width: Double,
            column: (paragraph: Int) -> Node?,
        ) {}

        override fun refreshGutter() {}
    }

    @Test
    fun `the items keep the anchor, their order, insert text and detail, also without a detail`() {
        val completions =
            HttpCompletions(
                5,
                listOf(
                    HttpCompletionItem("host", "host}}", "dev, test"),
                    HttpCompletionItem("GET"),
                    HttpCompletionItem("abc", "abd", null),
                ),
            )

        assertEquals(
            SourceCompletions(
                5,
                listOf(
                    SourceCompletion("host", "host}}", "dev, test"),
                    SourceCompletion("GET", "GET", null),
                    SourceCompletion("abc", "abd", null),
                ),
            ),
            sourceCompletions(completions),
        )
    }

    @Test
    fun `no items is an empty answer, not none`() {
        assertEquals(SourceCompletions(3, emptyList()), sourceCompletions(HttpCompletions(3, emptyList())))
    }

    @Test
    fun `the trigger characters are http-client's`() {
        assertEquals(listOf("{", "$", ".", "-", "/"), HttpCompletionSource { null }.triggerCharacters)
    }

    @Test
    fun `the editor's text and caret are completed with the environments of its project`(
        @TempDir project: Path,
    ) {
        project.resolve("http-client.env.json").writeText("""{"dev": {"host": "https://example.invalid"}}""")
        // below the project folder: found only because the source passes the project folder on
        val file =
            project
                .resolve("sub")
                .createDirectories()
                .resolve("a.http")
                .apply { createFile() }

        val answer = HttpCompletionSource { project }.complete(Editor(file, "GET {{"), 6).get(5, TimeUnit.SECONDS)

        assertEquals(6, answer.anchor)
        // the variables of the environment come before the `$` ones
        assertEquals(SourceCompletion("host", "host}}", "dev"), answer.items.first())
    }

    @ParameterizedTest
    @CsvSource(
        "a.http, true",
        "A.HTTP, true",
        "b.rest, true",
        "c.txt, false",
        "http, false",
        "x.http.bak, false",
    )
    fun `http-client completes http and rest files`(
        name: String,
        completes: Boolean,
    ) {
        assertEquals(completes, isHttpFile(Path.of("/w").resolve(name)))
    }
}

package dev.sluide.codeintelligence.workspace

import javafx.beans.property.SimpleIntegerProperty
import javafx.collections.FXCollections
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.jsonrpc.json.MessageJsonHandler
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the configuration problems banner says and when (ADR 0160); the view is `CodeIntelligenceUiTest`'s. */
class ConfigProblemsModelTest {
    private val window = Path.of("/work")
    private val tsconfig = window.resolve("tsconfig.json")
    private val nested = window.resolve("apps/b/tsconfig.json")
    private val kept = mutableMapOf<Path, List<Diagnostic>>()
    private val configs = mutableListOf(tsconfig)
    private val keptChanges = SimpleIntegerProperty()
    private val workspace = SimpleIntegerProperty()
    private val notNow = FXCollections.observableHashMap<Path, List<ConfigProblem>>()
    private var served = true

    private fun diagnostic(
        line: Int,
        column: Int,
        message: String,
    ) = Diagnostic(Range(Position(line, column), Position(line, column + 1)), message)

    private fun model(project: Path? = window) =
        ConfigProblemsModel(
            "TypeScript",
            project,
            { configs },
            { kept[it].orEmpty() },
            { served },
            listOf(keptChanges, workspace),
            notNow,
        )

    @Test
    fun `nothing without problems`() {
        assertTrue(model().shown.value.isEmpty())
    }

    @Test
    fun `the title counts the problems, each line is 1-based with the first line of the message`() {
        kept[tsconfig] = listOf(diagnostic(2, 4, "Option 'x' has been removed.\nMore text"))
        val model = model()
        val one = model.shown.value.single()
        assertEquals("tsconfig.json has 1 problem for TypeScript:", one.title)
        assertEquals(listOf("3:5  Option 'x' has been removed."), one.listed.map { it.text })
        assertEquals(ConfigProblem(2, 4, "Option 'x' has been removed."), one.listed.single())

        kept[tsconfig] = kept.getValue(tsconfig) + diagnostic(0, 0, "Early")
        keptChanges.value++
        val two = model.shown.value.single()
        assertEquals("tsconfig.json has 2 problems for TypeScript:", two.title)
        assertEquals(listOf("1:1  Early", "3:5  Option 'x' has been removed."), two.listed.map { it.text })
    }

    @Test
    fun `a file below the project folder is named relative to it`() {
        configs.clear()
        configs.add(nested)
        kept[nested] = listOf(diagnostic(0, 0, "x"))
        assertEquals(
            "apps/b/tsconfig.json has 1 problem for TypeScript:",
            model()
                .shown.value
                .single()
                .title,
        )
    }

    @Test
    fun `at most ten problems are listed and the rest is counted`() {
        kept[tsconfig] = (0 until 13).map { diagnostic(it, 0, "p$it") }
        val shown = model().shown.value.single()
        assertEquals("tsconfig.json has 13 problems for TypeScript:", shown.title)
        assertEquals(10, shown.listed.size)
        assertEquals(3, shown.more)
    }

    @Test
    fun `Not Now hides a file's banner until its set of problems changes`() {
        kept[tsconfig] = listOf(diagnostic(0, 0, "a"))
        val model = model()
        model.notNow(model.shown.value.single())
        assertTrue(model.shown.value.isEmpty())

        keptChanges.value++
        assertTrue(model.shown.value.isEmpty(), "the same problems stay hidden")

        kept[tsconfig] = listOf(diagnostic(0, 0, "a"), diagnostic(1, 0, "b"))
        keptChanges.value++
        assertEquals(
            2,
            model.shown.value
                .single()
                .all.size,
        )
    }

    @Test
    fun `the banner goes when the server pushes an empty list, and only for a served document`() {
        kept[tsconfig] = listOf(diagnostic(0, 0, "a"))
        val model = model()
        served = false
        workspace.value++
        assertTrue(model.shown.value.isEmpty(), "not served")

        served = true
        workspace.value++
        assertEquals(1, model.shown.value.size)

        kept[tsconfig] = emptyList()
        keptChanges.value++
        assertTrue(model.shown.value.isEmpty())
    }

    @Test
    fun `a malformed diagnostic without a range or a message is skipped`() {
        kept[tsconfig] =
            listOf(
                diagnostic(0, 0, "good"),
                Diagnostic().apply {
                    message =
                        org.eclipse.lsp4j.jsonrpc.messages.Either
                            .forLeft("no range")
                },
                Diagnostic().apply { range = Range(Position(1, 0), Position(1, 1)) },
            )
        assertEquals(
            listOf("1:1  good"),
            model()
                .shown.value
                .single()
                .listed
                .map { it.text },
        )
    }

    @Test
    fun `each config file has its own banner and its own Not Now`() {
        configs.add(nested)
        kept[tsconfig] = listOf(diagnostic(0, 0, "a"))
        kept[nested] = listOf(diagnostic(0, 0, "b"))
        val model = model()
        assertEquals(listOf(tsconfig, nested), model.shown.value.map { it.file })

        model.notNow(model.shown.value.first())
        assertEquals(listOf(nested), model.shown.value.map { it.file })
    }

    @Test
    fun `problems are listed by line, then column, whatever order the server pushed them in`() {
        kept[tsconfig] =
            listOf(diagnostic(5, 0, "e"), diagnostic(2, 9, "c"), diagnostic(2, 3, "b"), diagnostic(0, 20, "a"))
        val shown = model().shown.value.single()
        assertEquals(listOf("a", "b", "c", "e"), shown.all.map { it.message })
        assertEquals(listOf("1:21  a", "3:4  b", "3:10  c", "6:1  e"), shown.listed.map { it.text })
    }

    @Test
    fun `a message sent as MarkupContent is shown by its first line, one without text is skipped`() {
        // As lsp4j's parser makes them: its setters refuse a MarkupContent without value.
        fun diagnostic(message: String): Diagnostic =
            MessageJsonHandler(emptyMap()).gson.fromJson(
                """{"range":{"start":{"line":1,"character":1},"end":{"line":1,"character":2}},"message":$message}""",
                Diagnostic::class.java,
            )

        fun markup(value: String?) =
            diagnostic("""{"kind":"markdown"${value?.let { ""","value":"$it"""" }.orEmpty()}}""")
        kept[tsconfig] = listOf(markup("first\\nsecond"), markup(null), diagnostic(0, 0, ""))
        assertEquals(
            listOf("1:1  ", "2:2  first"),
            model()
                .shown.value
                .single()
                .listed
                .map { it.text },
        )
    }

    @Test
    fun `a diagnostic whose range has no start is skipped`() {
        kept[tsconfig] =
            listOf(
                Diagnostic().apply {
                    range = Range()
                    message = Either.forLeft("no start")
                },
                diagnostic(0, 0, "good"),
            )
        assertEquals(
            listOf("good"),
            model()
                .shown.value
                .single()
                .all
                .map { it.message },
        )
    }

    @Test
    fun `a file outside the project folder, or any file without one, is named by its file name`() {
        val outside = Path.of("/elsewhere/deep/tsconfig.json")
        configs.clear()
        configs.add(outside)
        kept[outside] = listOf(diagnostic(0, 0, "x"))
        assertEquals(
            "tsconfig.json has 1 problem for TypeScript:",
            model()
                .shown.value
                .single()
                .title,
        )
        assertEquals(
            "tsconfig.json has 1 problem for TypeScript:",
            model(project = null)
                .shown.value
                .single()
                .title,
        )
        configs.clear()
        configs.add(nested)
        kept[nested] = listOf(diagnostic(0, 0, "x"))
        assertEquals(
            "tsconfig.json has 1 problem for TypeScript:",
            model(project = null)
                .shown.value
                .single()
                .title,
        )
    }

    @Test
    fun `a closed model no longer follows the diagnostics, the workspace or Not Now`() {
        kept[tsconfig] = listOf(diagnostic(0, 0, "a"))
        val model = model()
        model.close()

        kept[tsconfig] = emptyList()
        keptChanges.value++
        workspace.value++
        assertEquals(1, model.shown.value.size)

        kept[tsconfig] = listOf(diagnostic(0, 0, "a"))
        notNow[tsconfig] =
            model.shown.value
                .single()
                .all
        assertEquals(1, model.shown.value.size, "Not Now of the old problems changes nothing")
    }
}

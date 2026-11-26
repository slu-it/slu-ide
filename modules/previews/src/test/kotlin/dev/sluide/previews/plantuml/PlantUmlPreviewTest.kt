package dev.sluide.previews.plantuml

import dev.sluide.backgroundtasks.BackgroundTasks
import dev.sluide.fileviewers.FileViewHost
import dev.sluide.plantuml.Diagram
import dev.sluide.plantuml.Rendering
import org.jsoup.Jsoup
import java.net.URI
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which page a PlantUML file's preview loads for a text, and when it keeps the one it shows. */
class PlantUmlPreviewTest {
    private val requests = mutableListOf<Pair<String, CompletableFuture<Rendering>>>()
    private val fxQueue = ArrayDeque<Runnable>()
    private var rerenders = 0

    private val preview =
        PlantUmlPreview(
            { source, firstLine ->
                assertEquals(1, firstLine, "a file is numbered from its first line")
                CompletableFuture<Rendering>().also { requests += source to it }
            },
            fxQueue::addLast,
        ).apply { attach({ rerenders++ }) { null } }

    private fun arrive(
        source: String,
        rendering: Rendering = drawn,
    ) {
        requests.last { it.first == source }.second.complete(rendering)
        while (fxQueue.isNotEmpty()) fxQueue.removeFirst().run()
    }

    private val drawn = Rendering(listOf(Diagram.Drawn("<svg/>", "<svg/>")), false)

    /** The content of [page] (without the script): each top element as `tag.class: text`. */
    private fun content(page: String?): List<String> =
        Jsoup
            .parse(assertNotNull(page))
            .body()
            .children()
            .filter { it.normalName() != "script" }
            .map { "${it.normalName()}.${it.className()}: ${it.text()}" }

    private val diagrams = listOf("div.plantuml: ")
    private val drawing = listOf("p.diagram-note: ${PlantUmlHtml.DRAWING}")
    private val noBlock = listOf("p.diagram-note: ${PlantUmlHtml.NO_BLOCK}")

    private val first = "@startuml\nA -> B\n@enduml\n"
    private val second = "@startuml\nA -> C\n@enduml\n"

    @Test
    fun `an empty file shows nothing and a file without a block says so, neither is drawn`() {
        assertEquals(emptyList(), content(preview.page("", dark = false)))
        assertEquals(emptyList(), content(preview.page(" \n\t\n", dark = false)))
        assertEquals(noBlock, content(preview.page("A -> B\n", dark = false)))
        assertEquals(emptyList(), requests)
    }

    @Test
    fun `the first page says it is drawing, the rerender shows the diagrams`() {
        assertEquals(drawing, content(preview.page(first, dark = false)))
        assertEquals(listOf(first), requests.map { it.first })
        assertEquals(0, rerenders)

        arrive(first)

        assertEquals(1, rerenders)
        assertEquals(diagrams, content(preview.page(first, dark = true)))
    }

    @Test
    fun `while a later text is drawn the diagrams shown stay, other pages do not`() {
        preview.page(first, dark = false)
        arrive(first)
        preview.page(first, dark = false)

        assertNull(preview.page(second, dark = false), "the diagrams stay")
        arrive(second)
        assertEquals(diagrams, content(preview.page(second, dark = false)))

        assertEquals(noBlock, content(preview.page("A -> B\n", dark = false)))
        assertEquals(drawing, content(preview.page(first + "\n", dark = false)), "no diagrams shown to keep")
    }

    @Test
    fun `a rendering without diagrams counts as no block`() {
        preview.page(first, dark = false)
        arrive(first, Rendering(emptyList(), false))

        assertEquals(noBlock, content(preview.page(first, dark = false)))
        assertEquals(drawing, content(preview.page(second, dark = false)), "nothing to keep")
    }

    @Test
    fun `the drawings of the four most recent texts are kept`() {
        val texts = (0..4).map { "@startuml\nA -> B : $it\n@enduml\n" }
        for (text in texts) {
            preview.page(text, dark = false)
            arrive(text)
        }

        preview.page(texts[1], dark = false)
        assertEquals(texts, requests.map { it.first }, "kept")
        preview.page(texts[0], dark = false)
        assertEquals(texts + texts[0], requests.map { it.first }, "drawn again")
    }

    @Test
    fun `after detach a result is dropped and the view not rendered again`() {
        preview.page(first, dark = false)

        preview.detach()
        arrive(first)

        assertEquals(0, rerenders)
    }

    private val file = Path("/x/diagram.puml")

    @Test
    fun `an export uses the rendering the view holds and draws nothing again`() {
        preview.page(first, dark = false)
        arrive(first)
        val future =
            preview.exports
                .first()
                .choose(file, null, first)
                .toCompletableFuture()

        assertTrue(future.isDone)
        assertEquals(listOf(first), requests.map { it.first })
    }

    @Test
    fun `an export of a text the view does not hold is drawn also when the view has no host`() {
        val future =
            preview.exports
                .first()
                .choose(file, null, second)
                .toCompletableFuture()
        requests.last { it.first == second }.second.complete(drawn)
        while (fxQueue.isNotEmpty()) fxQueue.removeFirst().run()

        assertEquals(listOf(file.resolveSibling("diagram.svg")), future.get(10, TimeUnit.SECONDS)!!.targets(file))
    }

    @Test
    fun `an export of a text the view does not hold is a task of the window, nothing once the view is disposed`() {
        val lines = CopyOnWriteArrayList<String>()
        val tasks = BackgroundTasks.forTests { lines += it }
        val host =
            object : FileViewHost {
                override fun openFile(file: Path) = false

                override fun openWebPage(uri: URI) {}

                @Suppress("UNCHECKED_CAST")
                override fun <T : Any> windowService(type: Class<T>): T? =
                    if (type == BackgroundTasks::class.java) tasks as T else null
            }
        preview.attach({ rerenders++ }) { host }
        val future =
            preview.exports
                .first()
                .choose(file, null, second)
                .toCompletableFuture()
        assertTrue(lines.single().endsWith("started: Drawing diagram.puml for the export"), lines.toString())

        preview.detach()
        requests.last { it.first == second }.second.complete(drawn)
        while (fxQueue.isNotEmpty()) fxQueue.removeFirst().run()

        assertNull(future.get(10, TimeUnit.SECONDS))
    }
}

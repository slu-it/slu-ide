package dev.sluide.previews.markdown

import dev.sluide.previews.preview.HtmlPage
import dev.sluide.previews.preview.Vendor
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.concurrent.Worker
import javafx.scene.web.WebEngine
import netscape.javascript.JSException
import netscape.javascript.JSObject
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.io.IOException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val log = System.getLogger(WebViewExportRenderer::class.java.name)

/** A formula to typeset for an export: its TeX source, in display mode or inline. */
internal class MathSource(
    val tex: String,
    val display: Boolean,
)

/**
 * A diagram drawn for an export: its SVG in Mermaid's default and dark theme, or the [error]. The error is Mermaid's,
 * unless the drawing [failed] (timed out, for example).
 */
internal class DrawnDiagram(
    val light: String?,
    val dark: String?,
    val error: String?,
    val failed: Boolean = false,
)

/**
 * The typeset [math] (KaTeX's HTML, one per formula; `null`: not typeset) and the drawn [diagrams], in the order they
 * were asked for.
 */
internal class ExportRendering(
    val math: List<String?>,
    val diagrams: List<DrawnDiagram>,
)

/** Typesets math and draws diagrams for an export; blocking, never on the FX thread. */
internal fun interface ExportRenderer {
    fun render(
        math: List<MathSource>,
        diagrams: List<String>,
    ): ExportRendering
}

/**
 * Typesets with the bundled KaTeX and draws with the bundled Mermaid (in `strict` security, both themes) in an
 * offscreen [WebEngine], created for each call and emptied afterwards. The results come in one after the other; what
 * is not done within [timeout] is missing: formulas are not typeset, diagrams fail with "Timed out".
 */
internal class WebViewExportRenderer(
    private val timeout: Duration = Duration.ofSeconds(60),
) : ExportRenderer {
    override fun render(
        math: List<MathSource>,
        diagrams: List<String>,
    ): ExportRendering {
        check(!Platform.isFxApplicationThread()) { "Rendering for an export blocks; not on the FX thread" }
        if (math.isEmpty() && diagrams.isEmpty()) return ExportRendering(emptyList(), emptyList())
        val input =
            mapper.writeValueAsString(
                mapOf(
                    "math" to math.map { mapOf("tex" to it.tex, "display" to it.display) },
                    "diagrams" to diagrams,
                ),
            )
        val session = Session(page(), input)
        Platform.runLater(session::start)
        val failure =
            try {
                session.result.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                null
            } catch (_: TimeoutException) {
                "Timed out"
            } catch (e: ExecutionException) {
                val cause = e.cause
                if (cause != null && cause !is IOException) {
                    log.log(System.Logger.Level.ERROR, "Rendering for an export failed unexpectedly", cause)
                }
                cause?.message ?: "Failed"
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                "Interrupted"
            } finally {
                // Here, so that a start that is still to come creates no engine, and later results are ignored.
                session.result.completeExceptionally(IllegalStateException("Ended"))
                Platform.runLater(session::close)
            }
        if (failure != null) {
            log.log(
                System.Logger.Level.WARNING,
                "Rendering ${math.size} formulas and ${diagrams.size} diagrams for an export ended early: $failure",
            )
        }
        return exportRendering(math.size, diagrams.size, session.bridge.typeset, session.bridge.drawn, failure)
    }

    /**
     * One call's engine, on the FX thread: loads the [page] with the libraries and the driver, then runs it with the
     * [input] that the page reads from the [bridge]. The [result] completes when the driver is done.
     */
    private class Session(
        private val page: String,
        input: String,
    ) {
        val result = CompletableFuture<Unit>()

        // Held here: the page only keeps a weak reference to it.
        val bridge = Bridge(input, result)
        private var engine: WebEngine? = null
        private val loaded =
            ChangeListener<Worker.State> { _, _, state ->
                when (state) {
                    Worker.State.SUCCEEDED -> run()
                    Worker.State.FAILED, Worker.State.CANCELLED -> fail("Not loaded")
                    else -> Unit
                }
            }

        fun start() {
            if (result.isDone) return
            val engine = WebEngine().also { engine = it }
            engine.loadWorker.stateProperty().addListener(loaded)
            engine.loadContent(page)
        }

        private fun run() {
            val engine = engine ?: return
            try {
                (engine.executeScript("window") as JSObject).setMember("sluideExport", bridge)
                engine.executeScript("sluideExportRender()")
            } catch (e: JSException) {
                fail(e.message ?: "Script failed")
            }
        }

        private fun fail(message: String) {
            result.completeExceptionally(IllegalStateException(message))
        }

        /** Frees the page's memory. */
        fun close() {
            val engine = engine ?: return
            this.engine = null
            engine.loadWorker.stateProperty().removeListener(loaded)
            engine.load("about:blank")
        }
    }

    /**
     * Called by the driver script: `sluideExport.input()` for the input, then `math(json)` with the typeset formulas,
     * `diagram(json)` for each diagram in turn, and `done()`; `fail(message)` if it fails. What comes after the
     * [result] is complete is ignored.
     */
    class Bridge internal constructor(
        private val input: String,
        private val result: CompletableFuture<Unit>,
    ) {
        @Volatile
        internal var typeset: List<String?>? = null
            private set

        internal val drawn = CopyOnWriteArrayList<DrawnDiagram>()

        fun input(): String = input

        fun math(json: String) {
            if (result.isDone) return
            typeset = tree(json)?.values()?.map { it.string() }
        }

        fun diagram(json: String) {
            if (result.isDone) return
            val diagram = tree(json) ?: return
            drawn +=
                DrawnDiagram(
                    diagram.path("light").string(),
                    diagram.path("dark").string(),
                    diagram.path("error").string(),
                )
        }

        fun done() {
            result.complete(Unit)
        }

        fun fail(message: String) {
            result.completeExceptionally(IllegalStateException(message))
        }

        private fun tree(json: String): JsonNode? =
            try {
                mapper.readTree(json)
            } catch (_: JacksonException) {
                null
            }

        private fun JsonNode.string(): String? = if (isString) asString() else null
    }

    private companion object {
        val mapper: JsonMapper = JsonMapper.builder().build()

        /** The driver's page; built for each call, since it is large and exports are rare. */
        fun page() =
            HtmlPage.render(
                dark = false,
                styles = listOf(HtmlPage.resource("preview.css")),
                scripts = listOf(Vendor.katexJs, Vendor.mermaidJs, DRIVER),
            ) {}

        // Reads the input from the bridge and answers through it: first the formulas as KaTeX's HTML (null: not
        // typeset), then each diagram, drawn in both themes or with Mermaid's error.
        const val DRIVER =
            """
async function sluideExportRender() {
    try {
        var input = JSON.parse(sluideExport.input());
        var math = input.math.map(function (formula) {
            try {
                return katex.renderToString(formula.tex, {
                    displayMode: formula.display, throwOnError: false, trust: false
                });
            } catch (error) {
                return null;
            }
        });
        sluideExport.math(JSON.stringify(math));
        for (var i = 0; i < input.diagrams.length; i++) {
            var drawn = { light: null, dark: null, error: null };
            var themes = [['light', 'default'], ['dark', 'dark']];
            for (var t = 0; t < themes.length && drawn.error === null; t++) {
                var id = 'sluide-export-' + i + '-' + themes[t][1];
                try {
                    mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: themes[t][1] });
                    drawn[themes[t][0]] = (await mermaid.render(id, input.diagrams[i])).svg;
                } catch (error) {
                    drawn = { light: null, dark: null, error: String((error && error.message) || error) };
                }
                // Mermaid leaves its drawing area behind after an error.
                var area = document.getElementById('d' + id);
                if (area) area.remove();
            }
            sluideExport.diagram(JSON.stringify(drawn));
        }
        sluideExport.done();
    } catch (error) {
        sluideExport.fail(String((error && error.message) || error));
    }
}
"""
    }
}

/**
 * The rendering of [formulas] formulas and [diagrams] diagrams from what came in: the [typeset] formulas (`null` if
 * none came) and the first of the [drawn] diagrams. The diagrams that are missing failed with [failure].
 */
internal fun exportRendering(
    formulas: Int,
    diagrams: Int,
    typeset: List<String?>?,
    drawn: List<DrawnDiagram>,
    failure: String?,
): ExportRendering =
    ExportRendering(
        List(formulas) { typeset?.getOrNull(it) },
        List(diagrams) { drawn.getOrNull(it) ?: DrawnDiagram(null, null, failure ?: "Not drawn", failed = true) },
    )

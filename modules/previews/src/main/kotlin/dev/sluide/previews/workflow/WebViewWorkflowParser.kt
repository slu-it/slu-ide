package dev.sluide.previews.workflow

import dev.sluide.githubactions.WorkflowParser
import dev.sluide.previews.preview.HtmlPage
import dev.sluide.previews.preview.Vendor
import javafx.application.Platform
import javafx.beans.value.ChangeListener
import javafx.concurrent.Worker
import javafx.scene.web.WebEngine
import netscape.javascript.JSException
import netscape.javascript.JSObject
import java.io.IOException
import java.io.InterruptedIOException
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

private val log = System.getLogger(WebViewWorkflowParser::class.java.name)

/**
 * Runs GitHub's workflow parser, bundled with our entry ([Vendor.workflowParserJs], ADR 0075), in a hidden [WebEngine]
 * of its own: no `WebView`, a page holding nothing but the bundle, created on the FX thread when first needed and kept
 * for the next calls.
 *
 * [parse] blocks, so never on the FX thread; calls run one after the other, each waiting at most [timeout] (also for
 * the page to load). A call that fails or times out throws an [IOException] and the engine is replaced at the next
 * call. Without a running JavaFX toolkit every call throws, and so does every call while [newEngine] fails.
 *
 * The parser itself runs on the FX thread (WebKit runs scripts only there); it takes a few milliseconds for a usual
 * workflow.
 */
internal class WebViewWorkflowParser(
    private val timeout: Duration = Duration.ofSeconds(10),
    private val newEngine: () -> WebEngine = ::WebEngine,
) : WorkflowParser {
    /** The engine with the parser's page, or null until needed or after a failure; FX thread only. */
    private var page: ParserPage? = null

    @Synchronized
    override fun parse(
        fileName: String,
        text: String,
    ): String {
        check(!Platform.isFxApplicationThread()) { "Parsing a workflow blocks; not on the FX thread" }
        val result = CompletableFuture<String>()
        try {
            Platform.runLater { run(fileName, text, result) }
        } catch (e: IllegalStateException) {
            throw IOException("JavaFX is not running", e)
        }
        try {
            return result.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            discard("timed out")
            throw IOException("The workflow parser did not answer within ${timeout.toSeconds()} seconds")
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            discard(cause.message ?: cause.javaClass.simpleName)
            throw cause as? IOException ?: IOException(cause.message, cause)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw InterruptedIOException("Interrupted while parsing the workflow")
        } finally {
            // So that a call still to come on the FX thread does nothing.
            result.completeExceptionally(CancellationException("Ended"))
        }
    }

    /** On the FX thread: runs one call once the page has loaded, creating the page if there is none. */
    private fun run(
        fileName: String,
        text: String,
        result: CompletableFuture<String>,
    ) {
        if (result.isDone) return
        val page =
            try {
                page ?: ParserPage(newEngine()).also { page = it }
            } catch (e: Throwable) {
                // Also errors such as a LinkageError of WebKit: the caller is not to wait for its timeout.
                result.completeExceptionally(IOException("The workflow parser could not be started: ${e.message}", e))
                return
            }
        // Runs at once if the page has loaded already, else on the FX thread when it has.
        page.loaded.whenComplete { _, error ->
            when {
                result.isDone -> Unit
                error != null -> result.completeExceptionally(error)
                else -> page.call(fileName, text, result)
            }
        }
    }

    /** Drops the engine after a failure ([why]), so that the next call starts with a new one. */
    private fun discard(why: String) {
        log.log(System.Logger.Level.WARNING, "Parsing a workflow failed, the parser starts anew: $why")
        try {
            Platform.runLater {
                page?.close()
                page = null
            }
        } catch (_: IllegalStateException) {
            // JavaFX ended; there is nothing to drop.
        }
    }

    /** The hidden [engine] with the parser loaded, on the FX thread; [loaded] completes when it is ready. */
    private class ParserPage(
        private val engine: WebEngine,
    ) {
        val loaded = CompletableFuture<Unit>()
        private val listener =
            ChangeListener<Worker.State> { _, _, state ->
                when (state) {
                    Worker.State.SUCCEEDED -> {
                        try {
                            if (engine.executeScript("typeof sluideWorkflowParser === 'object'") == true) {
                                loaded.complete(Unit)
                            } else {
                                loaded.completeExceptionally(IOException("The workflow parser did not start"))
                            }
                        } catch (e: JSException) {
                            loaded.completeExceptionally(IOException("The workflow parser did not start", e))
                        }
                    }

                    Worker.State.FAILED, Worker.State.CANCELLED -> {
                        loaded.completeExceptionally(IOException("The workflow parser could not be loaded"))
                    }

                    else -> {
                        Unit
                    }
                }
            }

        init {
            engine.isJavaScriptEnabled = true
            engine.loadWorker.stateProperty().addListener(listener)
            engine.loadContent(
                HtmlPage.render(dark = false, styles = emptyList(), scripts = listOf(Vendor.workflowParserJs)) {},
            )
        }

        /** Parses [text] of [fileName]; the inputs are passed as arguments, never written into script text. */
        fun call(
            fileName: String,
            text: String,
            result: CompletableFuture<String>,
        ) {
            try {
                val parser = engine.executeScript("sluideWorkflowParser") as JSObject
                when (val json = parser.call("parse", fileName, text)) {
                    is String -> result.complete(json)
                    else -> result.completeExceptionally(IOException("The workflow parser returned no text"))
                }
            } catch (e: JSException) {
                result.completeExceptionally(IOException("The workflow parser failed: ${e.message}", e))
            } catch (e: ClassCastException) {
                result.completeExceptionally(IOException("The workflow parser is missing", e))
            }
        }

        /** Frees the page's memory. */
        fun close() {
            engine.loadWorker.stateProperty().removeListener(listener)
            loaded.completeExceptionally(CancellationException("Closed"))
            engine.load("about:blank")
        }
    }

    companion object {
        /** The parser of every workflow view; its engine is created when the first workflow is parsed. */
        val shared = WebViewWorkflowParser()
    }
}

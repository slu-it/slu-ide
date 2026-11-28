package dev.sluide.httpclient

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.httpclient.complete.CompletionEngine
import dev.sluide.httpclient.env.EnvironmentFiles
import dev.sluide.httpclient.env.Environments
import java.nio.file.Path
import java.util.concurrent.CompletableFuture

/** The completions at one place of a `.http` file. */
object HttpCompletion {
    /** Typed alone, each opens the completion at once. */
    val triggerCharacters: List<String> = listOf("{", "$", ".", "-", "/")

    private val log = System.getLogger(HttpCompletion::class.java.name)
    private val engine = CompletionEngine()

    /**
     * The completions at [caret] (a UTF-16 offset into [text]) of [file]'s [text]. Parsing and reading the environment
     * files that apply (the nearest ones from [file]'s folder up to [projectFolder]) run on a virtual thread, which
     * completes the future. It never completes exceptionally: where nothing applies, or on any failure, `items` is
     * empty. Each item replaces `text[anchor, caret)`, and its label starts, ignoring case, with
     * `text[anchor, wordStart)`, where `wordStart` is the start of the run of letters, digits and `_` before the caret
     * (so `-`, `/`, `$`, `.` and `{` end a word): the editor filters the rest of the label by that word.
     * No value of an environment is ever part of an item.
     */
    fun complete(
        file: Path,
        text: String,
        caret: Int,
        projectFolder: Path?,
    ): CompletableFuture<HttpCompletions> =
        complete(text, caret) { Environments.load(EnvironmentFiles.find(file, projectFolder)) }

    /** [environments] is called on the virtual thread, and only when variables are offered. */
    internal fun complete(
        text: String,
        caret: Int,
        environments: () -> Environments,
    ): CompletableFuture<HttpCompletions> {
        val future = CompletableFuture<HttpCompletions>()
        NamedThreads.virtual("sluide-http-completion") {
            val result =
                try {
                    engine.complete(text, caret, environments)
                } catch (e: Throwable) {
                    // Never the message: it may quote a line of an environment file.
                    log.log(System.Logger.Level.DEBUG, "Completion failed: ${e.javaClass.name}")
                    HttpCompletions(caret.coerceIn(0, text.length), emptyList())
                }
            future.complete(result)
        }
        return future
    }
}

/** What [HttpCompletion.complete] offers: each item replaces `text[anchor, caret)` with its insert text. */
data class HttpCompletions(
    val anchor: Int,
    val items: List<HttpCompletionItem>,
)

data class HttpCompletionItem(
    val label: String,
    val insertText: String = label,
    val detail: String? = null,
)

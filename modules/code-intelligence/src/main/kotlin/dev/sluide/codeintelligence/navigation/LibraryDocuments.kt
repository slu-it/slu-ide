package dev.sluide.codeintelligence.navigation

import dev.sluide.backgroundtasks.NamedThreads
import dev.sluide.codeintelligence.binding.DefinitionTarget
import dev.sluide.codeintelligence.binding.SourcePosition
import dev.sluide.languageservers.LanguageServerSession
import dev.sluide.languageservers.LibraryDocument
import dev.sluide.languageservers.PendingRequest
import javafx.application.Platform
import java.io.IOException
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

private val log = System.getLogger(LibraryDocuments::class.java.name)

/**
 * Turns the targets of a definition answer into places that can be opened (ADR 0179): a local target is one already,
 * a [DefinitionTarget.Fetched] one needs its document: the spec's request ([LibraryDocument.method]) is sent through
 * the session, its answer, a JSON string, is written to the document's cache file off the FX thread, and the target
 * becomes a place in that file. Each distinct document is fetched once. Everything settles on the FX thread.
 *
 * @param io where the cache files are written.
 * @param fx runs a step on the FX thread.
 */
internal class LibraryDocuments(
    private val io: Executor = WRITER,
    private val fx: (Runnable) -> Unit = Platform::runLater,
) {
    /**
     * Starts resolving [targets]; [done] gets the places of those that could be made, in the targets' order, once
     * every fetch settled (a failed one is left out). Closing the handle cancels the requests (`$/cancelRequest`) and
     * stops writes that have not started; a write already under way finishes, but [done] is not called. FX thread.
     */
    fun resolve(
        session: LanguageServerSession,
        targets: List<DefinitionTarget>,
        done: (List<SourcePosition>) -> Unit,
    ): AutoCloseable {
        val places = arrayOfNulls<SourcePosition>(targets.size)
        var open = 0
        // Read by the writer thread: a cancelled fetch writes nothing.
        val cancelled = AtomicBoolean(false)
        val pending = mutableListOf<PendingRequest>()

        fun settle() {
            if (cancelled.get()) return
            open--
            if (open == 0) {
                done(places.filterNotNull().distinct())
            }
        }

        val waiting = LinkedHashMap<LibraryDocument, MutableList<Int>>()
        targets.forEachIndexed { index, target ->
            when (target) {
                is DefinitionTarget.Local -> places[index] = target.position
                is DefinitionTarget.Fetched -> waiting.getOrPut(target.document) { mutableListOf() } += index
            }
        }
        open = waiting.size + 1
        for ((document, indices) in waiting) {
            pending +=
                session.requestJson(
                    document.method,
                    document.params,
                    { result ->
                        if (result == null || !result.isJsonPrimitive || !result.asJsonPrimitive.isString) {
                            log.log(System.Logger.Level.WARNING, "library document ${document.method}: no text")
                            settle()
                            return@requestJson
                        }
                        val text = result.asString
                        io.execute {
                            var written = false
                            try {
                                if (!cancelled.get()) {
                                    writeLibraryDocument(document.file, text)
                                    written = true
                                }
                            } catch (e: IOException) {
                                log.log(System.Logger.Level.WARNING, "library document ${document.method}: $e")
                            } finally {
                                // Also after a runtime exception: the target is dropped, the others still count.
                                fx {
                                    if (written) {
                                        for (index in indices) {
                                            places[index] = (targets[index] as DefinitionTarget.Fetched).position
                                        }
                                    }
                                    settle()
                                }
                            }
                        }
                    },
                    { error ->
                        log.log(System.Logger.Level.INFO, "library document ${document.method} failed: $error")
                        settle()
                    },
                )
        }
        settle()
        return AutoCloseable {
            cancelled.set(true)
            pending.forEach { it.cancel() }
        }
    }

    private companion object {
        val WRITER: Executor by lazy { NamedThreads.single("code-intelligence-library-documents") }
    }
}

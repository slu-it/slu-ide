package dev.sluide.languageservers

import dev.sluide.languageservers.workspace.Signal
import javafx.beans.Observable
import org.eclipse.lsp4j.Diagnostic
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * What a server last published for files no editor listens to that the spec keeps
 * ([LanguageServerSpec.keepsDiagnostics], ADR 0160): the latest list per file, an empty one removes it. Changed on
 * the FX thread, where [changes] fires; read on any thread.
 */
internal class KeptDiagnostics {
    private val byUri = ConcurrentHashMap<String, List<Diagnostic>>()
    private val signal = Signal()

    /** Fires after the diagnostics of a kept file changed or were cleared. */
    val changes: Observable = signal

    /** Keeps [diagnostics] as those of [uri] (a normalized URI); FX thread. */
    fun keep(
        uri: String,
        diagnostics: List<Diagnostic>,
    ) {
        val before = if (diagnostics.isEmpty()) byUri.remove(uri) else byUri.put(uri, diagnostics)
        if (before.orEmpty() != diagnostics) signal.fire()
    }

    /** The diagnostics kept for [file]; none if nothing was kept. */
    fun of(file: Path): List<Diagnostic> = byUri[LspUris.of(file)].orEmpty()

    /** Forgets everything: the server stopped or restarts; fires if something was kept and [fire]. */
    fun clear(fire: Boolean = true) {
        if (byUri.isEmpty()) return
        byUri.clear()
        if (fire) signal.fire()
    }
}

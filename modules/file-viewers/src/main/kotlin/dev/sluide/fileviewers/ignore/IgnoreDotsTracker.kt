package dev.sluide.fileviewers.ignore

import dev.sluide.fileviewers.IgnoreFileEffects
import dev.sluide.fileviewers.IgnoreRuleEffects
import dev.sluide.textediting.LineEnding
import dev.sluide.textediting.decodeTextLeniently
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

/**
 * The bookkeeping behind the dots of a `.gitignore` view ([GitignoreFileView]): takes the window's [IgnoreRuleEffects]
 * from [serviceOf], looks the effects of the saved file up on [connect], [lookUp] and on every change of the service,
 * drops answers of older or disposed requests and those of a file whose text on disk is not [savedText], and keeps
 * the dots shifted to the current [text]. [onChanged] is called when the dots or whether they are known changed, so
 * the gutter is rebuilt. Used on the FX thread.
 */
internal class IgnoreDotsTracker(
    private var file: Path,
    private val savedText: () -> String,
    private val text: () -> String,
    private val serviceOf: () -> IgnoreRuleEffects?,
    private val onChanged: () -> Unit,
) {
    private var service: IgnoreRuleEffects? = null
    private var subscription: AutoCloseable? = null
    private var disposed = false
    private var request = 0

    /** The effects last answered and the saved text they describe. */
    private var known = false
    private var effectsBase = ""
    private var baseDots: Map<Int, IgnoreDot> = emptyMap()
    private var dots: Map<Int, IgnoreDot> = emptyMap()

    /** Whether the effects are known; the gutter has a column then. */
    val isKnown: Boolean get() = known

    /** The dot of the 0-based [paragraph], if it has one. */
    fun dotOf(paragraph: Int): IgnoreDot? = dots[paragraph]

    /** Takes the window's service, if any, and listens to it; the one before is let go. */
    fun connect() {
        subscription?.close()
        subscription = null
        service = serviceOf()
        subscription = service?.onChanged(::lookUp)
    }

    /** Follows the file; renamed to another name than `.gitignore`, there are no dots any more. */
    fun moved(to: Path) {
        file = to
        if (to.fileName.toString() != GITIGNORE) {
            subscription?.close()
            subscription = null
            service = null
        } else if (service == null) {
            connect()
        }
        lookUp()
    }

    fun dispose() {
        disposed = true
        request++
        subscription?.close()
        subscription = null
    }

    /** Asks the service for the effects of the saved file; answers of older or disposed requests are dropped. */
    fun lookUp() {
        val service = service
        if (disposed) return
        val id = ++request
        if (service == null) {
            apply(null, "")
            return
        }
        val base = savedText()
        service.lookup(file) { answer ->
            if (!disposed && id == request) apply(answer?.takeIf { onDisk(base) }, base)
        }
    }

    /** Shifts the dots to the text; calls [onChanged] if they or [force] (the column appears or goes) say so. */
    fun recompute(force: Boolean = false) {
        val new = IgnoreRuleDots.edited(effectsBase, text(), baseDots)
        if (new == dots && !force) return
        dots = new
        onChanged()
    }

    /** Whether the file on disk has the text [saved]; not while the view shows another (a banner offers to reload). */
    private fun onDisk(saved: String): Boolean =
        try {
            val bytes = Files.readAllBytes(file)
            LineEnding.normalize(decodeTextLeniently(bytes)) == saved
        } catch (_: IOException) {
            false
        }

    /** Takes the [answer] for the saved text [base]; `null` means unknown: no dots. */
    private fun apply(
        answer: IgnoreFileEffects?,
        base: String,
    ) {
        val wasKnown = known
        known = answer != null
        effectsBase = base
        baseDots = if (answer == null) emptyMap() else IgnoreRuleDots.of(base, answer)
        recompute(force = wasKnown != known)
    }

    private companion object {
        const val GITIGNORE = ".gitignore"
    }
}

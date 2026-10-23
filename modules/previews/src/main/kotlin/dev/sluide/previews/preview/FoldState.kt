package dev.sluide.previews.preview

import javafx.scene.web.WebEngine
import netscape.javascript.JSException
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper

/**
 * The folds of the foldable previews (JSON, XML, YAML), carried from one rendered page to the next: [read] asks the current
 * page (`fold-tree.js`) which containers it has closed, [appendFoldState] gives an incomplete page the folds it
 * could not show.
 */
internal object FoldState {
    private val mapper = JsonMapper()

    /** The `data-path`s of the containers the current page has closed; none if there is no such page. */
    fun read(engine: WebEngine): Set<String> =
        try {
            val script = "window.sluide && sluide.folds ? sluide.folds.collapsed() : null"
            (engine.executeScript(script) as? String)
                ?.let(mapper::readTree)
                ?.takeIf { it.isArray }
                ?.mapNotNull { it.asString(null) }
                ?.toSet()
                .orEmpty()
        } catch (_: JSException) {
            emptySet()
        } catch (_: JacksonException) {
            emptySet()
        }

    internal fun json(collapsed: Set<String>): String = mapper.writeValueAsString(collapsed.sorted())
}

/**
 * Writes the hidden `#fold-state` element holding [collapsed], which `sluide.folds.collapsed()` reports for containers
 * the page lacks. A page calls this only when it is incomplete (empty, with an error, or cut off).
 */
internal fun StringBuilder.appendFoldState(collapsed: Set<String>) {
    append("<div id=\"fold-state\" hidden data-collapsed=\"")
        .append(HtmlPage.escape(FoldState.json(collapsed)))
        .append("\"></div>")
}

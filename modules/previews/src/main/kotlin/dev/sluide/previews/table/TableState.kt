package dev.sluide.previews.table

import javafx.scene.web.WebEngine
import netscape.javascript.JSException
import tools.jackson.core.JacksonException
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper

/**
 * What the user did to a CSV table, carried from one rendered page to the next like the fold state
 * (`preview/FoldState.kt`): the [header] choice of the checkbox (`null` until it was clicked, so each render guesses
 * again), the [sortColumn] and its direction, and the texts of the column [filters].
 */
internal data class TableState(
    val header: Boolean? = null,
    val sortColumn: Int? = null,
    val descending: Boolean = false,
    val filters: List<String> = emptyList(),
) {
    /** This state as `csv-table.js` reads it from `data-state` and reports it through `sluide.table.state()`. */
    fun json(): String {
        val node = mapper.createObjectNode()
        if (header == null) node.putNull("header") else node.put("header", header)
        if (sortColumn == null) node.putNull("sortColumn") else node.put("sortColumn", sortColumn)
        node.put("descending", descending)
        val array = node.putArray("filters")
        filters.forEach(array::add)
        return mapper.writeValueAsString(node)
    }

    companion object {
        private val mapper = JsonMapper()

        /** The state of the table on the current page; the default if there is no such page. */
        fun read(engine: WebEngine): TableState =
            try {
                val script = "window.sluide && sluide.table ? sluide.table.state() : null"
                (engine.executeScript(script) as? String)?.let(::parse) ?: TableState()
            } catch (_: JSException) {
                TableState()
            }

        /** The state in [json]; what is missing or malformed in it keeps its default. */
        fun parse(json: String): TableState {
            val node =
                try {
                    mapper.readTree(json)
                } catch (_: JacksonException) {
                    return TableState()
                }
            if (node == null || !node.isObject) return TableState()
            return TableState(
                header = node.get("header")?.takeIf(JsonNode::isBoolean)?.booleanValue(),
                sortColumn =
                    node
                        .get("sortColumn")
                        ?.takeIf { it.isIntegralNumber && it.canConvertToInt() }
                        ?.intValue()
                        ?.takeIf { it >= 0 },
                descending = node.get("descending")?.takeIf(JsonNode::isBoolean)?.booleanValue() ?: false,
                filters =
                    node
                        .get("filters")
                        ?.takeIf(JsonNode::isArray)
                        ?.values()
                        ?.map { if (it.isString) it.stringValue() else "" }
                        .orEmpty(),
            )
        }
    }
}

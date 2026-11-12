package dev.sluide.codereview.window

import dev.sluide.codereview.model.ReviewLayout
import dev.sluide.projectdata.ProjectDataStore
import tools.jackson.databind.node.ObjectNode

/** The review window's remembered settings (ADR 0168). */
internal data class ReviewPrefsValue(
    val layout: ReviewLayout = ReviewLayout.SPLIT,
    val hideWhitespace: Boolean = false,
)

/**
 * The layout and Hide whitespace, remembered user-wide: `{"layout": "UNIFIED", "hideWhitespace": true}` in the
 * top-level field `review` of `shared.json` (ADR 0101). Only values that differ from the defaults (split, off) are
 * written; an empty `review` is removed, other fields of it stay. A missing or invalid value reads as the default.
 * [read] is cheap (the data file is small) and runs when a window opens; [write] may run on any thread.
 */
internal class ReviewPrefs(
    private val store: ProjectDataStore,
) {
    fun read(): ReviewPrefsValue {
        val review = store.readShared()?.get(FIELD) as? ObjectNode ?: return ReviewPrefsValue()
        val layout = review.get(LAYOUT)?.takeIf { it.isString }?.asString()
        return ReviewPrefsValue(
            ReviewLayout.entries.firstOrNull { it.name == layout } ?: ReviewLayout.SPLIT,
            review.get(HIDE_WHITESPACE)?.takeIf { it.isBoolean }?.asBoolean() ?: false,
        )
    }

    fun write(
        layout: ReviewLayout? = null,
        hideWhitespace: Boolean? = null,
    ) {
        val written =
            store.updateShared { root ->
                val review = (root.get(FIELD) as? ObjectNode) ?: root.objectNode()
                when (layout) {
                    null -> {}

                    ReviewLayout.SPLIT -> {
                        review.remove(LAYOUT)
                    }

                    else -> {
                        review.put(LAYOUT, layout.name)
                    }
                }
                when (hideWhitespace) {
                    null -> {}

                    true -> {
                        review.put(HIDE_WHITESPACE, true)
                    }

                    false -> {
                        review.remove(HIDE_WHITESPACE)
                    }
                }
                if (review.isEmpty) root.remove(FIELD) else root.set(FIELD, review)
            }
        if (!written) {
            System
                .getLogger(ReviewPrefs::class.java.name)
                .log(System.Logger.Level.WARNING, "Review settings not saved: the shared data file cannot be written")
        }
    }

    private companion object {
        const val FIELD = "review"
        const val LAYOUT = "layout"
        const val HIDE_WHITESPACE = "hideWhitespace"
    }
}

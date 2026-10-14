package dev.sluide.settings

import javafx.scene.Node
import org.springframework.stereotype.Component

/** One page of the settings dialog, e.g. Display → Fonts. Pages are Spring beans and are found automatically. */
interface SettingsPage {
    /** Where the page sits in the tree, e.g. `["Display", "Fonts"]`; the last element is the page's own name. */
    val path: List<String>

    /** The page's content; created on first use, on the FX thread. */
    val node: Node

    /** Shows the current settings, dropping unapplied edits. Called whenever the dialog opens. */
    fun load()

    /**
     * Applies and stores the page's edits.
     *
     * @throws java.io.IOException if they cannot be stored; the dialog reports it.
     */
    fun apply()
}

/** All settings pages, ordered by their path. */
@Component
class SettingsPages(
    pages: List<SettingsPage>,
) {
    val pages: List<SettingsPage> = pages.sortedBy { it.path.joinToString("\u0000") }
}

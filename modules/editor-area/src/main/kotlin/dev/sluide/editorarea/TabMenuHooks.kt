package dev.sluide.editorarea

import java.nio.file.Path

/**
 * The items the window adds to the context menu of the tabs that show a file: each is offered only while its hook is
 * set, and enabled per file by its `can` check, asked when the menu opens. Set by the window.
 */
class TabMenuHooks {
    /** Shows the history of a tab's file, e.g. in the git log area (ADR 0046): Show History. */
    var showHistory: ((Path) -> Unit)? = null

    /** Whether Show History is enabled for a tab's file. */
    var canShowHistory: (Path) -> Boolean = { false }

    /** Selects a tab's file in the file explorer (ADR 0095): Select in Explorer. */
    var selectInExplorer: ((Path) -> Unit)? = null

    /** Whether Select in Explorer is enabled for a tab's file. */
    var canSelectInExplorer: (Path) -> Boolean = { false }
}

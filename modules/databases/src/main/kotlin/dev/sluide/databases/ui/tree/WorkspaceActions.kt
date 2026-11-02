package dev.sluide.databases.ui.tree

import dev.sluide.databases.result.TableRef

/** What the tree (and others) ask of the selected connection's middle pane; set by the workspace when it is created. */
interface WorkspaceActions {
    /** Shows the data of a table or view in a data tab. */
    fun openData(ref: TableRef)

    /** Inserts [text] into the active console. */
    fun insertIntoConsole(text: String)
}

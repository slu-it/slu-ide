package dev.sluide.fileexplorer

import javafx.scene.Node
import java.nio.file.Path

/**
 * An action the window adds to the explorer's context menu for single files it [appliesTo]; absent from the menu
 * otherwise.
 */
class FileAction(
    val label: String,
    val icon: (() -> Node)? = null,
    val appliesTo: (file: Path) -> Boolean,
    val perform: (file: Path) -> Unit,
)

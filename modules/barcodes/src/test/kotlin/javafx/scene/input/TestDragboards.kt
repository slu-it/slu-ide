package javafx.scene.input

import com.sun.javafx.tk.Toolkit

/**
 * A [Dragboard] with [content], for firing `DragEvent`s at a node: its constructor is package-private, hence this
 * class in JavaFX's own package (the test classpath does not seal it). Needs the toolkit, so only in UI tests.
 */
object TestDragboards {
    fun of(content: Map<DataFormat, Any>): Dragboard =
        Dragboard.createDragboard(Toolkit.getToolkit().createLocalClipboard()).also { it.setContent(content) }
}

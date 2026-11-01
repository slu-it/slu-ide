package dev.sluide.coloring.ui

import javafx.scene.Node
import javafx.scene.Parent
import javafx.scene.control.Control
import javafx.scene.control.Labeled
import javafx.scene.control.TextField
import org.testfx.api.FxRobot

internal const val NARROW = 400.0

/**
 * Applies CSS and layout to [root] on the FX thread and returns the visible nodes that do not fit [width]: a node
 * reaching past it in scene coordinates, or a [Labeled] narrower than its preferred width (it would show "..."). The
 * walk does not enter the skin of a [Control].
 */
internal fun narrowOffenders(
    robot: FxRobot,
    root: Node,
    width: Double,
): List<String> {
    val offenders = mutableListOf<String>()

    fun walk(node: Node) {
        if (!node.isVisible) return
        val right = node.localToScene(node.boundsInLocal).maxX
        if (right > width + 0.5) offenders += "${node.javaClass.simpleName} ${node.styleClass} ends at $right"
        if (node is Labeled && node.width < node.prefWidth(-1.0) - 0.5) {
            offenders +=
                "${node.javaClass.simpleName} '${node.text}' is ${node.width} wide, wants ${node.prefWidth(-1.0)}"
        }
        if (node is Parent && node !is Control) node.childrenUnmodifiable.forEach(::walk)
    }
    robot.interact {
        root.applyCss()
        (root as? Parent)?.layout()
        walk(root)
    }
    return offenders
}

/**
 * The scene x of the field in each color slot under [root], in order, after CSS and layout on the FX thread: slots
 * whose labels are aligned have equal values.
 */
internal fun slotFieldXs(
    robot: FxRobot,
    root: Node,
): List<Double> {
    lateinit var xs: List<Double>
    robot.interact {
        root.applyCss()
        (root as? Parent)?.layout()
        xs =
            root.lookupAll(".color-slot").map { slot ->
                val field = (slot as Parent).childrenUnmodifiable.filterIsInstance<TextField>().single()
                field.localToScene(0.0, 0.0).x
            }
    }
    return xs
}

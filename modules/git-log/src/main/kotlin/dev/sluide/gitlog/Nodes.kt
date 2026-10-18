package dev.sluide.gitlog

import javafx.event.EventTarget
import javafx.scene.Node

/** Whether [target] is this node or one inside it, e.g. the focus owner or the target of an event. */
internal fun Node.contains(target: EventTarget?): Boolean {
    var node = target as? Node
    while (node != null) {
        if (node === this) return true
        node = node.parent
    }
    return false
}

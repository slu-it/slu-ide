package dev.sluide.plantuml

import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The one diagram of this rendering, asserted to be drawn. */
internal fun Rendering.onlyDrawn(): Diagram.Drawn {
    assertEquals(1, diagrams.size, toString())
    return assertIs<Diagram.Drawn>(diagrams[0], toString())
}

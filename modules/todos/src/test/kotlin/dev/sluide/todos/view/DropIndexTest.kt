package dev.sluide.todos.view

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.Test
import kotlin.test.assertEquals

class DropIndexTest {
    private val centers = listOf(10.0, 30.0, 50.0, 70.0)

    @ParameterizedTest
    @CsvSource(
        "1,5,0",
        "1,30,1",
        "1,51,2",
        "1,60,2",
        "1,100,3",
        "0,-100,0",
        "0,10,0",
        "0,100,3",
        "3,0,0",
        "3,40,2",
        "3,70,3",
    )
    fun `the drop index counts the other cards above the dragged card`(
        dragged: Int,
        y: Double,
        expected: Int,
    ) = assertEquals(expected, dropIndex(centers, dragged, y))

    @Test
    fun `a single card stays at index 0`() = assertEquals(0, dropIndex(listOf(10.0), 0, 500.0))
}

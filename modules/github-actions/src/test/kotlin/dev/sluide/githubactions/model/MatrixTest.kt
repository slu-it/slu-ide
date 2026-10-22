package dev.sluide.githubactions.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MatrixTest {
    private fun matrix(
        axes: Map<String, List<String?>> = emptyMap(),
        include: List<Map<String, String?>> = emptyList(),
        exclude: List<Map<String, String?>> = emptyList(),
        expression: String? = null,
        includeExpression: String? = null,
        excludeExpression: String? = null,
    ) = Matrix(expression, axes, include, exclude, includeExpression, excludeExpression)

    @Test
    fun `the axes multiply`() {
        val matrix = matrix(mapOf("os" to listOf("a", "b"), "node" to listOf("18", "20", "22")))

        assertEquals(6, matrix.combinations())
        assertEquals("matrix os×node (6)", matrix.title())
    }

    @Test
    fun `exclude removes the combinations matching all of an entry's keys`() {
        val axes = mapOf("os" to listOf("a", "b"), "java" to listOf("21", "25"), "x" to listOf("1"))

        assertEquals(3, matrix(axes, exclude = listOf(mapOf("os" to "b", "java" to "21"))).combinations())
        assertEquals(2, matrix(axes, exclude = listOf(mapOf("os" to "b"))).combinations())
        assertEquals(4, matrix(axes, exclude = listOf(mapOf("os" to "c"), mapOf("nope" to "1"))).combinations())
        assertEquals(0, matrix(axes, exclude = listOf(mapOf("x" to "1"))).combinations())
        assertEquals(4, matrix(axes, exclude = listOf(emptyMap())).combinations(), "an empty entry excludes nothing")
    }

    @Test
    fun `include extends matching combinations or adds its own`() {
        val axes = mapOf("fruit" to listOf("apple", "pear"), "animal" to listOf("cat", "dog"))
        // The example of GitHub's documentation: 4 combinations, extended by the first three entries, plus two new
        // ones; the second banana is not merged into the first.
        val include =
            listOf(
                mapOf("color" to "green"),
                mapOf("color" to "pink", "animal" to "cat"),
                mapOf("fruit" to "apple", "shape" to "circle"),
                mapOf("fruit" to "banana"),
                mapOf("fruit" to "banana", "animal" to "cat"),
            )

        assertEquals(6, matrix(axes, include).combinations())
    }

    @Test
    fun `include is matched against the combinations left after exclude`() {
        val axes = mapOf("os" to listOf("a", "b"))

        assertEquals(2, matrix(axes, listOf(mapOf("os" to "b", "x" to "1")), listOf(mapOf("os" to "b"))).combinations())
    }

    @Test
    fun `values are compared as text, null with null`() {
        val axes = mapOf("v" to listOf("1", null))

        assertEquals(1, matrix(axes, exclude = listOf(mapOf("v" to null))).combinations())
        assertEquals(2, matrix(axes, include = listOf(mapOf("v" to "1", "x" to "y"))).combinations())
        assertEquals(3, matrix(axes, include = listOf(mapOf("v" to "1.0"))).combinations())
    }

    @Test
    fun `without axes only include makes combinations`() {
        assertEquals(2, matrix(include = listOf(mapOf("a" to "1"), mapOf("a" to "2"))).combinations())
        assertEquals(0, matrix().combinations())
        assertEquals("matrix (2)", matrix(include = listOf(mapOf("a" to "1"), mapOf("a" to "2"))).title())
    }

    @Test
    fun `expressions make the matrix dynamic`() {
        val dynamic =
            listOf(
                matrix(expression = "\${{ fromJSON(x) }}"),
                matrix(mapOf("os" to listOf("\${{ fromJSON(x).os }}"))),
                matrix(mapOf("os" to listOf("a")), includeExpression = "\${{ x }}"),
                matrix(mapOf("os" to listOf("a")), excludeExpression = "\${{ x }}"),
            )

        for (matrix in dynamic) assertNull(matrix.combinations(), "$matrix")
        assertEquals(
            listOf("matrix (dynamic)", "matrix os (dynamic)", "matrix os (dynamic)", "matrix os (dynamic)"),
            dynamic.map { it.title() },
        )
        assertEquals(2, matrix(mapOf("os" to listOf("\${{ a }}", "b"))).combinations(), "an expression among values")
    }

    @Test
    fun `huge cross products are not counted`() {
        val axes = (1..17).associate { "a$it" to listOf("x", "y") }

        assertNull(matrix(axes).combinations())
        assertEquals("matrix ${axes.keys.joinToString("×")} (over 65536)", matrix(axes).title())
        assertEquals(65_536, matrix(axes - "a17").combinations())
    }

    @Test
    fun `the fixture's static matrix makes four jobs`() {
        val matrix =
            matrix(
                mapOf(
                    "os" to listOf("ubuntu-latest", "macos-latest"),
                    "java" to listOf("21", "25"),
                    "experimental" to listOf("false"),
                ),
                include =
                    listOf(
                        mapOf("os" to "windows-latest", "java" to "25", "experimental" to "true"),
                        mapOf("os" to "ubuntu-latest", "flags" to "{debug: true}"),
                    ),
                exclude = listOf(mapOf("os" to "macos-latest", "java" to "21")),
            )

        assertEquals(4, matrix.combinations())
    }
}

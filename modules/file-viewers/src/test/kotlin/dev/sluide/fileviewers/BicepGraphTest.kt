package dev.sluide.fileviewers

import dev.sluide.fileviewers.BicepGraph.Edge
import dev.sluide.fileviewers.BicepGraph.Kind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class BicepGraphTest {
    /** The edges as `from -> to` by symbol, `parent` edges as `from -parent-> to`. */
    private fun BicepGraph.edgeNames(): Set<String> =
        edges.map { "${nodes[it.from].symbol} -${if (it.parent) "parent" else ""}-> ${nodes[it.to].symbol}" }.toSet()

    @Test
    fun `resources, a child, a loop, an existing resource and a module are nodes`() {
        val graph = BicepGraph.parse(MAIN_BICEP)

        assertEquals(listOf("storage", "blobs", "container", "vault", "network"), graph.nodes.map { it.symbol })
        val (storage, blobs, container, vault, network) = graph.nodes
        assertEquals(Kind.RESOURCE, storage.kind)
        assertEquals("Microsoft.Storage/storageAccounts@2023-05-01", storage.target)
        assertEquals("storageAccounts", storage.shortTarget)
        assertEquals("blobServices", blobs.shortTarget)
        assertEquals("containers", container.shortTarget)
        assertTrue(container.loop && !storage.loop && !network.loop, "only the container is a loop")
        assertTrue(vault.existing && !storage.existing, "only the vault exists already")
        assertEquals(Kind.MODULE, network.kind)
        assertEquals("./modules/network.bicep", network.shortTarget)
        assertEquals(5, graph.total)
        assertNull(graph.note)
    }

    @Test
    fun `references, dependsOn, parents and vars make edges, transitively`() {
        val graph = BicepGraph.parse(MAIN_BICEP)

        // network uses `ids`, a var of `storageId`, a var of `storage.id`; `parent: storage::blobs` is the child.
        assertEquals(
            setOf("blobs -parent-> storage", "container -parent-> blobs", "network --> storage", "network --> vault"),
            graph.edgeNames(),
        )
    }

    @Test
    fun `a parent property gives one labelled edge, nested children their parent`() {
        val graph =
            BicepGraph.parse(
                """
                |resource sa 'Microsoft.Storage/storageAccounts@2023-05-01' = { name: 'sa' }
                |resource blob 'Microsoft.Storage/storageAccounts/blobServices@2023-05-01' = {
                |  parent: sa
                |  name: '${'$'}{sa.name}-default'
                |  resource c 'containers' = {
                |    name: 'c'
                |    resource deep 'x' = { name: blob.name }
                |  }
                |}
                |resource other 'y' = { name: blob::c::deep.name }
                |
                """.trimMargin(),
            )

        assertEquals(
            setOf("blob -parent-> sa", "c -parent-> blob", "deep -parent-> c", "deep --> blob", "other --> deep"),
            graph.edgeNames(),
        )
    }

    @Test
    fun `a conditional child's body is its own, from its brace on`() {
        val graph =
            BicepGraph.parse(
                """
                |resource other 'O' = {}
                |resource parent 'A' = {
                |  resource child 'B' = if (true) {
                |    properties: { x: other.id }
                |    resource grand 'G' = if (length(other.name) > 0) {
                |      name: child.name
                |    }
                |  }
                |  name: 'p'
                |}
                |
                """.trimMargin(),
            )

        assertEquals(listOf("other", "parent", "child", "grand"), graph.nodes.map { it.symbol })
        assertEquals(
            setOf("child -parent-> parent", "child --> other", "grand -parent-> child", "grand --> other"),
            graph.edgeNames(),
        )
    }

    @Test
    fun `short names are children of the enclosing resources first, and names are no references`() {
        val graph =
            BicepGraph.parse(
                """
                |resource sa1 'S' = {
                |  resource blob 'blobServices' = { name: 'default' }
                |}
                |resource sa2 'S' = {
                |  name: blob.name
                |  resource blob 'blobServices' = { name: 'default' }
                |  resource container 'containers' = { name: blob.name }
                |}
                |
                """.trimMargin(),
            )

        assertEquals(listOf("sa1", "blob", "sa2", "blob", "container"), graph.nodes.map { it.symbol })
        assertEquals(
            setOf(
                Edge(1, 0, parent = true),
                Edge(2, 3),
                Edge(3, 2, parent = true),
                Edge(4, 2, parent = true),
                Edge(4, 3),
            ),
            graph.edges.toSet(),
        )
    }

    @Test
    fun `spreads are references, also through vars`() {
        val graph =
            BicepGraph.parse(
                """
                |resource storage 'S' = {}
                |var common = { id: storage.id }
                |var tags = { ...common, extra: 1 }
                |resource r 'R' = { tags: { ...tags } }
                |
                """.trimMargin(),
            )

        assertEquals(setOf("r --> storage"), graph.edgeNames())
    }

    @Test
    fun `asserts end the body before them`() {
        val graph = BicepGraph.parse("resource a 'A' = {}\nresource b 'B' = { name: 'b' }\nassert ok = a.name != ''\n")

        assertEquals(emptySet(), graph.edgeNames())
    }

    @Test
    fun `comments, strings, keys and members are no references, interpolations are`() {
        val graph =
            BicepGraph.parse(
                """
                |resource a 'A' = {}
                |resource b 'B' = {
                |  // dependsOn: [a]
                |  name: 'a and /* a */'
                |  properties: { a: 1, x: c.a }
                |}
                |resource c 'C' = { name: '${'$'}{a.name}' }
                |// resource fake 'F' = {}
                |var text = 'resource fake2 \'G\' = {}'
                |
                """.trimMargin(),
            )

        assertEquals(listOf("a", "b", "c"), graph.nodes.map { it.symbol })
        assertEquals(setOf("b --> c", "c --> a"), graph.edgeNames())
    }

    @Test
    fun `params and outputs are no nodes, nor do vars make edges of their own`() {
        val graph =
            BicepGraph.parse(
                """
                |param p string = 'x'
                |var v = s.id
                |resource s 'S' = { name: p }
                |output o string = s.name
                |
                """.trimMargin(),
            )

        assertEquals(listOf("s"), graph.nodes.map { it.symbol })
        assertEquals(emptyList(), graph.edges)
    }

    @Test
    fun `a declaration at the start of a line recovers from unbalanced brackets`() {
        val graph =
            BicepGraph.parse(
                "resource a 'A' = {\n  name: [\nresource b 'B' = {\n  x: a\n}\n}}}\nmodule m 'm.bicep' = {}\n",
            )

        assertEquals(listOf("a", "b", "m"), graph.nodes.map { it.symbol })
        assertEquals(listOf(Edge(1, 0)), graph.edges)
    }

    @Test
    fun `labels are escaped for Mermaid and ids are generated`() {
        val hostile = "x\"]; click r_0 call alert(1) %% #ff0 <img src=x onerror=alert(2)> `**md**` ;\n€ 😀"
        val label = BicepGraph.mermaidLabel(hostile)

        assertTrue(Regex("""[A-Za-z0-9 _\-./:@(),#;]*""").matches(label), label)
        assertEquals(hostile.replace('\n', ' ').take(47), decode(label).take(47))
        assertEquals("a#35;b#59;c#34;#96;#60;#62;#38; #128512;", BicepGraph.mermaidLabel("a#b;c\"`<>&\t😀"))

        val graph =
            BicepGraph.parse(
                "module m 'mods/${hostile.replace("\n", "")}.bicep' = {}\nresource r 'A' existing = {}\n",
            )
        val mermaid = graph.toMermaid()
        val lines = mermaid.lines()
        assertEquals("flowchart LR", lines[0])
        assertTrue(Regex("""    r_0\[\["m<br/>[^"<>`]*"]]""").matches(lines[1]), lines[1])
        assertTrue(Regex("""    r_1\["r<br/>A"]""").matches(lines[2]), lines[2])
        assertTrue("    class r_1 existing" in lines, mermaid)
    }

    @Test
    fun `long labels are cut, keeping the end of module paths`() {
        val path = "br/public:avm/res/" + "very-long-segment/".repeat(10) + "storage-account:0.9.0"
        val label = BicepGraph.mermaidLabel(path, keepEnd = true)

        assertTrue(label.startsWith("#8230;") && label.endsWith("storage-account:0.9.0"), label)
        assertTrue(decode(label).codePointCount(0, decode(label).length) <= 48)
        val emoji = BicepGraph.mermaidLabel("😀".repeat(100))
        assertTrue(emoji.length <= 96 && emoji.endsWith("#8230;"), emoji)
    }

    @Test
    fun `only the first nodes and edges are kept, so that Mermaid draws them`() {
        val many =
            (0 until 300).joinToString(
                "\n",
            ) { "resource r$it 'A' = { x: [${if (it > 0) "r0, r${it - 1}" else ""}] }" }
        val graph = BicepGraph.parse(many)

        assertEquals(BicepGraph.MAX_NODES, graph.nodes.size)
        assertEquals(300, graph.total)
        assertEquals("Showing the first 200 of 300 resources and modules.", graph.note)
        assertTrue(graph.edges.all { it.from < 200 && it.to < 200 })

        val dense = BicepGraph.parse(many, maxEdges = 50)
        assertEquals(50, dense.edges.size)
        assertEquals("Showing the first 200 of 300 resources and modules.", dense.note)
        val denseOnly = BicepGraph.parse(many.lines().take(100).joinToString("\n"), maxEdges = 50)
        assertEquals("Showing the first 50 dependencies.", denseOnly.note)
    }

    @Test
    fun `the largest diagram stays below Mermaid's limits`() {
        val type = "😀".repeat(200)
        val symbol = "s".repeat(300)
        val text =
            (0 until 250).joinToString("\n") {
                "resource $symbol$it '$type/$type@1' existing = [for x in y: { parent: ${symbol}0, a: [${symbol}1, ${symbol}2, ${symbol}3] }]"
            }
        val mermaid = BicepGraph.parse(text).toMermaid()

        assertTrue(mermaid.length < 50_000, "${mermaid.length} characters")
        assertTrue(BicepGraph.parse(text).edges.size <= 500)
    }

    @Test
    fun `hostile and huge files stay linear and never throw`() {
        val chain = "var v0 = r.id\n" + (1 until 100_000).joinToString("") { "var v$it = [v${it - 1}, v${it - 1}]\n" }
        val texts =
            mapOf(
                "resources" to (0 until 50_000).joinToString("") { "resource r$it 'A' = {\n  x: r${it / 2}\n}\n" },
                "var chain" to "resource r 'A' = {}\n" + chain + "resource s 'B' = { x: v99999 }\n",
                "var cycle" to "var a = b\nvar b = a\nresource r 'A' = { x: a }\n",
                "braces" to "resource r 'A' = " + "{".repeat(200_000) + "\n" + "}".repeat(200_000),
                "interpolations" to "resource r 'A' = { x: '" + "\${'".repeat(100_000) + " }\n",
                "nested children" to "resource r 'A' = {\n" + " resource c 'C' = {\n  x: missing.id\n".repeat(50_000),
                "keywords" to "resource ".repeat(100_000) + "module = = [for".repeat(50_000),
                "child chains" to "resource r 'A' = { x: " + "r::c".repeat(50_000) + " }\n",
                "unterminated" to "resource r 'A' = { x: '''" + "a".repeat(100_000),
            )
        for ((name, text) in texts) {
            val took = measureTime { BicepGraph.parse(text).toMermaid() }
            assertTrue(took < 3.seconds, "$name took $took")
        }
        val chained = BicepGraph.parse(texts.getValue("var chain"))
        assertEquals(setOf("s --> r"), chained.edgeNames())
        assertEquals(1, BicepGraph.parse(texts.getValue("var cycle")).nodes.size)
    }

    /** [label] with its entity codes (`#35;`) decoded, as Mermaid shows it. */
    private fun decode(label: String): String =
        Regex("#(\\d+);").replace(label) { String(Character.toChars(it.groupValues[1].toInt())) }
}

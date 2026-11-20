package dev.sluide.jshell.engine

import dev.sluide.jshell.ChunkedInput
import dev.sluide.testsupport.LogCapture
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtocolReaderTest {
    private val mapper = JsonMapper.builder().build()

    // the shape of print-without-newline.jsonl, lines 4 and 8
    private fun output(
        text: String,
        error: Boolean = false,
        id: Long? = 1,
    ) = mapper.writeValueAsString(linkedMapOf("id" to id, "event" to "output", "text" to text, "error" to error))

    private fun reader(vararg lines: String) =
        ProtocolReader(
            ChunkedInput().apply {
                feed(lines.joinToString("") { "$it\n" })
                end()
            },
        )

    private fun ProtocolReader.all(): List<ObjectNode> = generateSequence { next() }.toList()

    private fun texts(messages: List<ObjectNode>) = messages.map { it.path("text").asString() }

    @Test
    fun `messages come in order and the end of the stream is null`() {
        val messages =
            reader(
                """{"event":"ready","javaVersion":"25.0.5"}""",
                """{"id":1,"event":"finished"}""",
            ).all()
        assertEquals(
            listOf(
                """{"event":"ready","javaVersion":"25.0.5"}""",
                """{"id":1,"event":"finished"}""",
            ),
            messages.map {
                it.toString()
            },
        )
    }

    @Test
    fun `a line that is no JSON object is logged and skipped`() {
        LogCapture(ProtocolReader::class.java.name).use { log ->
            val messages = reader("not json", "[1]", "", """{"id":1,"event":"finished"}""").all()
            assertEquals(listOf("finished"), messages.map { it.path("event").asString() })
            assertEquals(
                List(3) { "The JShell agent sent a line that is no JSON object (${listOf(8, 3, 0)[it]} characters)" },
                log.messages(),
            )
        }
    }

    @Test
    fun `buffered outputs of one evaluation and stream come joined`() {
        val messages = reader(*Array(1000) { output("$it ") }, """{"id":1,"event":"finished"}""").all()
        assertEquals(listOf((0 until 1000).joinToString("") { "$it " }, ""), texts(messages))
        assertEquals(
            """{"id":1,"event":"output","text":"${texts(messages)[0]}","error":false}""",
            messages[0].toString(),
        )
    }

    @Test
    fun `outputs of other streams, other evaluations or around another message stay apart`() {
        val messages =
            reader(
                output("a"),
                output("b", error = true),
                output("c"),
                output("d", id = 2),
                output("e", id = null),
                output("f", id = null),
                """{"id":2,"event":"value","feedback":"x ==> 1"}""",
                output("g", id = 2),
            ).all()
        assertEquals(listOf("a", "b", "c", "d", "ef", "", "g"), texts(messages))
    }

    @Test
    fun `a joined output stops before it would pass the cap`() {
        val big = "x".repeat(30_000)
        assertEquals(
            listOf(60_000, 30_000),
            texts(reader(output(big), output(big), output(big)).all()).map { it.length },
        )
        assertEquals(
            listOf(65_536),
            texts(reader(output("y".repeat(65_535)), output("z")).all()).take(1).map { it.length },
        )
    }

    @Test
    fun `one output over the cap comes whole`() {
        assertEquals(listOf(70_000, 1), texts(reader(output("x".repeat(70_000)), output("y")).all()).map { it.length })
    }

    @Test
    fun `a message of the same evaluation and stream that is no output is not joined`() {
        val diagnostic = """{"id":1,"event":"diagnostic","start":0,"end":1,"line":0,"message":"m","error":false}"""
        assertEquals(
            listOf("output", "diagnostic"),
            reader(output("a"), diagnostic).all().map { it.path("event").asString() },
        )
    }

    @Test
    fun `the end after an output and a broken line ends the join`() {
        assertEquals(listOf("a"), texts(reader(output("a"), "{broken").all()))
    }

    @Test
    fun `an output not yet buffered is not waited for`() {
        val input = ChunkedInput()
        val reader = ProtocolReader(input)
        input.feed(output("a") + "\n")
        assertEquals("a", reader.next()?.path("text")?.asString())
        input.feed(output("b") + "\n" + output("c") + "\n")
        assertEquals("bc", reader.next()?.path("text")?.asString())
        input.end()
        assertNull(reader.next())
    }
}

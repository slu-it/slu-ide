package dev.sluide.httpclient.envedit

import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import tools.jackson.databind.JsonNode
import tools.jackson.databind.node.ObjectNode
import java.util.stream.Stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Navigation and low-level edits of the two parsed environment files, below [EnvDocument]. */
internal class EnvTreesTest {
    private fun trees(
        public: String = "{}",
        private: String = "{}",
    ) = EnvTrees(json(public) as ObjectNode, json(private) as ObjectNode)

    private val a = ValuePath.of("a")

    @ParameterizedTest(name = "{0}")
    @MethodSource("locations")
    fun `follows the merge of the files down a path`(
        name: String,
        public: String,
        private: String,
        path: ValuePath,
        expected: Location,
    ) {
        assertEquals(expected, trees(public, private).locate("e", path), name)
    }

    @Test
    fun `finds nothing in an environment that does not exist or is no object`() {
        assertEquals(Location.NONE, trees().locate("zz", a))
        assertEquals(Location.NONE, trees("""{"e": "text"}""", """{"e": [1]}""").locate("e", a))
        assertNull(trees("""{"e": "text"}""").nodeAt(FileSide.PUBLIC, "e", ValuePath.ROOT))
    }

    @Test
    fun `walks one file only, through keys of objects and indexes of lists`() {
        val trees = trees("""{"e": {"a": {"b": [10, {"c": 1}]}, "s": "text"}}""", """{"e": {"a": {"b": [99]}}}""")

        assertEquals(json("10"), trees.nodeAt(FileSide.PUBLIC, "e", a.key("b").index(0)))
        assertEquals(json("99"), trees.nodeAt(FileSide.PRIVATE, "e", a.key("b").index(0)))
        assertEquals(json("1"), trees.nodeAt(FileSide.PUBLIC, "e", a.key("b").index(1).key("c")))
        assertNull(trees.nodeAt(FileSide.PUBLIC, "e", ValuePath.of("s").key("x")))
        assertNull(trees.nodeAt(FileSide.PUBLIC, "e", ValuePath.of("s").index(0)))
        assertNull(trees.nodeAt(FileSide.PUBLIC, "e", a.index(0)))
        assertNull(trees.nodeAt(FileSide.PUBLIC, "e", a.key("b").key("x")))
        assertNull(trees.nodeAt(FileSide.PUBLIC, "e", a.key("b").index(2)))
    }

    @Test
    fun `puts a value at the root, replacing the environment in that file only`() {
        val trees = trees("""{"e": {"x": 1}}""", """{"e": {"y": 2}}""")

        trees.put(FileSide.PRIVATE, "e", ValuePath.ROOT, json("""{"z": 3}"""))

        assertEquals(json("""{"x": 1}"""), trees.public.get("e"))
        assertEquals(json("""{"z": 3}"""), trees.private.get("e"))
    }

    @Test
    fun `puts a value, creating the environment and the objects on the way and reusing those that exist`() {
        val trees = trees("""{"e": {"a": {"keep": 1}}}""")

        trees.put(FileSide.PUBLIC, "e", a.key("new"), json("2"))
        trees.put(FileSide.PUBLIC, "fresh", a.key("b"), json("3"))

        assertEquals(json("""{"a": {"keep": 1, "new": 2}}"""), trees.public.get("e"))
        assertEquals(json("""{"a": {"b": 3}}"""), trees.public.get("fresh"))
    }

    @Test
    fun `puts into a list by replacing an entry, appending at the end or refusing an index beyond it`() {
        val trees = trees("""{"e": {"a": [1, 2]}}""")

        trees.put(FileSide.PUBLIC, "e", a.index(0), json("9"))
        trees.put(FileSide.PUBLIC, "e", a.index(2), json("3"))
        assertEquals(json("[9, 2, 3]"), trees.public.get("e").get("a"))

        assertEquals(
            "http-client.env.json: \"a[5]\" of environment \"e\" is beyond the end of its list",
            assertThrows<IllegalStateException> { trees.put(FileSide.PUBLIC, "e", a.index(5), json("0")) }.message,
        )
        assertEquals(
            "http-client.env.json: \"a[-1]\" of environment \"e\" is beyond the end of its list",
            assertThrows<IllegalStateException> { trees.put(FileSide.PUBLIC, "e", a.index(-1), json("0")) }.message,
        )
        assertEquals(json("[9, 2, 3]"), trees.public.get("e").get("a"))
    }

    @Test
    fun `refuses to write through what is no object or no list, naming the file and where`() {
        val trees = trees("""{"e": {"a": {"s": "text"}}, "str": "x"}""", """{"e": {"a": [1]}}""")

        fun refusal(
            side: FileSide,
            env: String,
            path: ValuePath,
        ) = assertThrows<IllegalStateException> { trees.put(side, env, path, json("0")) }.message

        assertEquals(
            "http-client.env.json: \"a.s\" of environment \"e\" is not an object",
            refusal(FileSide.PUBLIC, "e", a.key("s").key("x")),
        )
        assertEquals(
            "http-client.env.json: environment \"str\" is not an object",
            refusal(FileSide.PUBLIC, "str", a),
        )
        assertEquals(
            "http-client.private.env.json: \"a\" of environment \"e\" is not an object",
            refusal(FileSide.PRIVATE, "e", a.key("x").key("y")),
        )
        assertEquals(
            "http-client.env.json: \"a\" of environment \"e\" is not a list",
            refusal(FileSide.PUBLIC, "e", a.index(0)),
        )
        assertEquals(
            "http-client.env.json: environment \"e\" is not a list",
            refusal(FileSide.PUBLIC, "e", ValuePath.ROOT.index(0)),
        )
        assertEquals(
            "http-client.private.env.json: \"a[1].x\" of environment \"e\" does not exist",
            refusal(FileSide.PRIVATE, "e", a.index(1).key("x")),
        )
    }

    @Test
    fun `deletes an environment from one file and tells whether there was one`() {
        val trees = trees("""{"e": {}}""", """{"e": {}}""")

        assertTrue(trees.delete(FileSide.PUBLIC, "e", ValuePath.ROOT))
        assertFalse(trees.delete(FileSide.PUBLIC, "e", ValuePath.ROOT))
        assertNull(trees.public.get("e"))
        assertEquals(json("{}"), trees.private.get("e"))
    }

    @Test
    fun `deletes a key or a list entry and tells whether there was one`() {
        val trees = trees("""{"e": {"a": {"k": 1, "l": [1, 2]}}}""")
        val list = a.key("l")

        assertFalse(trees.delete(FileSide.PUBLIC, "e", a.key("missing")))
        assertFalse(trees.delete(FileSide.PUBLIC, "e", list.index(2)))
        assertFalse(trees.delete(FileSide.PUBLIC, "e", list.index(7)))
        assertFalse(trees.delete(FileSide.PUBLIC, "e", list.key("x")))
        assertFalse(trees.delete(FileSide.PUBLIC, "e", a.key("k").index(0)))
        assertFalse(trees.delete(FileSide.PUBLIC, "e", ValuePath.of("nowhere", "x")))
        assertEquals(json("""{"k": 1, "l": [1, 2]}"""), trees.public.get("e").get("a"))

        assertTrue(trees.delete(FileSide.PUBLIC, "e", list.index(1)))
        assertTrue(trees.delete(FileSide.PUBLIC, "e", a.key("k")))
        assertEquals(json("""{"l": [1]}"""), trees.public.get("e").get("a"))
    }

    @Test
    fun `prunes nothing inside lists when an entry of a list becomes empty`() {
        val trees = trees("""{"e": {"l": [{"x": 1}]}}""", """{"e": {"l": [{"x": 2}]}}""")

        assertTrue(trees.delete(FileSide.PRIVATE, "e", ValuePath.of("l").index(0).key("x")))

        assertEquals(json("""{"l": [{}]}"""), trees.private.get("e"))
        assertEquals(json("""{"l": [{"x": 1}]}"""), trees.public.get("e"))
    }

    companion object {
        private fun json(text: String): JsonNode = checkNotNull(EnvJson.parse(text))

        private fun case(
            name: String,
            public: String,
            private: String,
            path: ValuePath,
            expected: Location,
        ): Arguments = Arguments.of(name, public, private, path, expected)

        private fun loc(
            public: String?,
            private: String?,
            owner: FileSide?,
            ownerDepth: Int,
            overridesPublic: Boolean = false,
        ) = Location(public?.let(::json), private?.let(::json), owner, ownerDepth, overridesPublic)

        private val a = ValuePath.of("a")

        @JvmStatic
        fun locations(): Stream<Arguments> =
            Stream.of(
                case(
                    "objects in both files merge",
                    """{"e": {"a": {"x": 1}}}""",
                    """{"e": {"a": {"y": 2}}}""",
                    a,
                    loc("""{"x": 1}""", """{"y": 2}""", null, -1),
                ),
                case(
                    "a value of the public file below merged objects",
                    """{"e": {"a": {"x": 1}}}""",
                    """{"e": {"a": {"y": 2}}}""",
                    a.key("x"),
                    loc("1", null, FileSide.PUBLIC, 1),
                ),
                case(
                    "a private object replaces a public text",
                    """{"e": {"a": "s"}}""",
                    """{"e": {"a": {"b": 1}}}""",
                    a,
                    loc(null, """{"b": 1}""", FileSide.PRIVATE, 0, true),
                ),
                case(
                    "a private text replaces a public object",
                    """{"e": {"a": {"b": 1}}}""",
                    """{"e": {"a": "s"}}""",
                    a,
                    loc(null, "\"s\"", FileSide.PRIVATE, 0, true),
                ),
                case(
                    "a private value that the public file lacks is not an override",
                    "{}",
                    """{"e": {"a": 5}}""",
                    a,
                    loc(null, "5", FileSide.PRIVATE, 0),
                ),
                case(
                    "an object only the private file has stays unowned",
                    "{}",
                    """{"e": {"a": {"b": 1}}}""",
                    a,
                    loc(null, """{"b": 1}""", null, -1),
                ),
                case(
                    "an object only the public file has stays unowned",
                    """{"e": {"a": {"b": 1}}}""",
                    "{}",
                    a,
                    loc("""{"b": 1}""", null, null, -1),
                ),
                case(
                    "a public value alone is owned by the public file",
                    """{"e": {"a": 5}}""",
                    "{}",
                    a,
                    loc("5", null, FileSide.PUBLIC, 0),
                ),
                case(
                    "an entry of a public list",
                    """{"e": {"a": [1, 2]}}""",
                    "{}",
                    a.index(1),
                    loc("2", null, FileSide.PUBLIC, 0),
                ),
                case(
                    "an entry of a private list that overrides a public one",
                    """{"e": {"a": [3]}}""",
                    """{"e": {"a": [1, 2]}}""",
                    a.index(1),
                    loc(null, "2", FileSide.PRIVATE, 0, true),
                ),
                case(
                    "an index below objects that merge",
                    """{"e": {"a": {"x": 1}}}""",
                    """{"e": {"a": {"y": 2}}}""",
                    a.index(0),
                    Location.NONE,
                ),
            )
    }
}

package dev.sluide.ide.scratches

import dev.sluide.texttypes.TextTypes
import kotlin.test.Test
import kotlin.test.assertEquals

class ClipboardScratchTest {
    private val errors = mutableListOf<String>()
    private val opened = mutableListOf<Pair<String, String>>()
    private val detected = mutableListOf<String>()
    private val background = mutableListOf<Runnable>()
    private val onFx = mutableListOf<Runnable>()

    private fun scratch(extension: String? = "json") =
        ClipboardScratch(
            detect = { text ->
                detected += text
                TextTypes.all.first { it.extension == extension }
            },
            showError = { errors += it },
            openText = { ext, text -> opened += ext to text },
            runInBackground = { background += it },
            dispatch = { onFx += it },
        )

    @Test
    fun `no text or a blank text shows the error and creates nothing`() {
        val scratch = scratch()

        scratch.create(null)
        scratch.create("")
        scratch.create(" \n\t ")

        assertEquals(List(3) { "The clipboard holds no text." }, errors)
        assertEquals(emptyList(), background + onFx)
        assertEquals(emptyList(), detected)
    }

    @Test
    fun `a text is detected in the background and then opened on the FX thread with its extension`() {
        val scratch = scratch("json")

        scratch.create("{\"a\": 1}\n")

        assertEquals(emptyList(), detected, "nothing detected on the calling thread")
        assertEquals(1, background.size)
        background.single().run()
        assertEquals(listOf("{\"a\": 1}\n"), detected)
        assertEquals(emptyList(), opened, "opened only on the FX thread")
        onFx.single().run()
        assertEquals(listOf("json" to "{\"a\": 1}\n"), opened)
        assertEquals(emptyList(), errors)
    }

    @Test
    fun `Plain Text gives txt and the text is passed on as it is`() {
        val scratch = scratch("txt")

        scratch.create("  Note: buy milk\r\n")
        background.single().run()
        onFx.single().run()

        assertEquals(listOf("txt" to "  Note: buy milk\r\n"), opened)
    }

    @Test
    fun `an answer without an extension still gives a txt scratch`() {
        val scratch =
            ClipboardScratch(
                detect = { TextTypes.NO_EXTENSION },
                showError = { errors += it },
                openText = { ext, text -> opened += ext to text },
                runInBackground = { background += it },
                dispatch = { onFx += it },
            )

        scratch.create("x")
        background.single().run()
        onFx.single().run()

        assertEquals(listOf("txt" to "x"), opened)
    }
}

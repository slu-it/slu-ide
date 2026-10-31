package dev.sluide.converting.ui

import dev.sluide.converting.ConversionOption
import dev.sluide.converting.OptionValues
import dev.sluide.converting.model.FakeFamily
import dev.sluide.converting.model.FakeFormat
import dev.sluide.converting.model.Pick
import dev.sluide.converting.model.Status
import dev.sluide.converting.model.TargetChoice
import dev.sluide.converting.model.TextPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SidePresentationTest {
    private val json = FakeFormat("json")
    private val yaml = FakeFormat("yaml", listOf("yaml", "yml"))
    private val toml = FakeFormat("toml")
    private val data = FakeFamily("Data", listOf(json, yaml, toml))
    private val curl = FakeFormat("curl", emptyList())
    private val requests = FakeFamily("Requests", listOf(curl))

    @Test
    fun `the picker label names the pinned, detected or suggested format`() {
        assertEquals("Detect", pickerLabel(isSource = true, Pick.Detect, null))
        assertEquals("Detect: YAML", pickerLabel(isSource = true, Pick.Detect, yaml))
        assertEquals("YAML", pickerLabel(isSource = true, Pick.Pinned(yaml), yaml))
        assertEquals("JSON", pickerLabel(isSource = false, Pick.Detect, json), "the target's suggestion")
        assertEquals("TOML", pickerLabel(isSource = false, Pick.Pinned(toml), toml))
        assertEquals("Detect", pickerLabel(isSource = false, Pick.Detect, null))
    }

    @Test
    fun `the picker offers Detect and the formats, marking the pick, without headers for one family`() {
        val entries = pickerEntries(listOf(data, requests), choices(json, yaml), Pick.Pinned(yaml))

        assertEquals(
            listOf(
                PickerEntry.Item("Detect", Pick.Detect, selected = false),
                PickerEntry.Item("JSON", Pick.Pinned(json), selected = false),
                PickerEntry.Item("YAML", Pick.Pinned(yaml), selected = true),
            ),
            entries,
        )
    }

    @Test
    fun `formats of several families are grouped under the family names`() {
        val entries = pickerEntries(listOf(data, requests), choices(json, yaml, toml, curl), Pick.Detect)

        assertEquals(
            listOf(
                PickerEntry.Item("Detect", Pick.Detect, selected = true),
                PickerEntry.Header("Data"),
                PickerEntry.Item("JSON", Pick.Pinned(json), selected = false),
                PickerEntry.Item("YAML", Pick.Pinned(yaml), selected = false),
                PickerEntry.Item("TOML", Pick.Pinned(toml), selected = false),
                PickerEntry.Header("Requests"),
                PickerEntry.Item("CURL", Pick.Pinned(curl), selected = false),
            ),
            entries,
        )
    }

    @Test
    fun `an unavailable target format is disabled and says why`() {
        val choices = listOf(TargetChoice(json, null), TargetChoice(toml, "needs an object at the top"))

        val entries = pickerEntries(listOf(data), choices, Pick.Detect)

        assertEquals(
            PickerEntry.Item("TOML — needs an object at the top", Pick.Pinned(toml), selected = false, disabled = true),
            entries.last(),
        )
    }

    @Test
    fun `a failure with a position is clickable, at the editor's offset`() {
        val failure = Status.Failure("Unexpected '}'", TextPosition(offset = 7, line = 2, column = 3))

        assertEquals(7, clickOffset(failure, "{\n  \"a\": }"))
        assertEquals(6, clickOffset(failure, "{\r\n  \"a\": }"), "the editor holds \\n for \\r\\n")
        assertNull(clickOffset(Status.Failure("No format detected", null), "x"))
        assertNull(clickOffset(Status.Info("JSON", 1, emptyList()), "x"))
    }

    @Test
    fun `offsets past a CRLF count one less, as the editor has LF`() {
        assertEquals(0, editorOffset("a\r\nb", 0))
        assertEquals(1, editorOffset("a\r\nb", 1), "the CR is the editor's LF")
        assertEquals(1, editorOffset("a\r\nb", 2), "so is the LF of the pair")
        assertEquals(2, editorOffset("a\r\nb", 3))
        assertEquals(2, editorOffset("a\rb", 2), "a lone CR is one LF")
        assertEquals(2, editorOffset("ab", 9), "kept within the text")
    }

    @Test
    fun `the editor text has LF line breaks`() {
        assertEquals("a\nb\nc", editorText("a\r\nb\rc"))
    }

    @Test
    fun `a scratch gets the format's first extension, else txt`() {
        assertEquals("yaml", scratchExtension(yaml))
        assertEquals("txt", scratchExtension(curl))
        assertEquals("txt", scratchExtension(null))
    }

    @Test
    fun `options become check boxes and choices with their current values`() {
        val sortKeys = ConversionOption.Toggle("sortKeys", "Sort keys", default = false)
        val indent = ConversionOption.Choice("indent", "Indent", listOf("2", "4"), default = "2")

        val controls = optionControls(listOf(sortKeys, indent), OptionValues().with("indent", "4"))

        assertEquals(
            listOf(
                OptionControl.Check("sortKeys", "Sort keys", selected = false),
                OptionControl.Select("indent", "Indent", listOf("2", "4"), value = "4"),
            ),
            controls,
        )
    }

    private fun choices(vararg formats: FakeFormat) = formats.map { TargetChoice(it, null) }
}

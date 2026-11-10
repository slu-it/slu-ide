package dev.sluide.fileviewers.highlight

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrontMatterTest {
    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "---\\na: 1\\n---\\n# T|yaml|a: 1\\n|13",
            "---\\na: 1\\n...\\nbody|yaml|a: 1\\n|13",
            "+++\\na = 1\\n+++\\nbody|toml|a = 1\\n|14",
            "---\\n---\\nbody|yaml||8",
            "---\\r\\na: 1\\r\\n---\\r\\nbody|yaml|a: 1\\r\\n|16",
            "---  \\na: 1\\n---  \\nbody|yaml|a: 1\\n|17",
            "---\\na: 1\\n---|yaml|a: 1\\n|12",
        ],
    )
    fun `front matter is found`(
        escaped: String,
        language: String,
        content: String?,
        end: Int,
    ) {
        val text = escaped.replace("\\r", "\r").replace("\\n", "\n")
        val found = frontMatter(text)!!
        assertEquals(language, found.language)
        assertEquals(end, found.end)
        assertEquals(
            (content ?: "").replace("\\r", "\r").replace("\\n", "\n"),
            text.substring(found.contentStart, found.contentEnd),
        )
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = [
            "---\\n\\na: 1\\n---",
            "---\\na: 1\\n",
            "# T\\n---\\na: 1\\n---",
            "+++\\na = 1\\n---",
            "+++\\na = 1\\n...",
            "---",
            "---\\n",
        ],
    )
    fun `no front matter`(escaped: String) {
        assertNull(frontMatter(escaped.replace("\\n", "\n")))
    }
}

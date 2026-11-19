package dev.sluide.findinfiles.search

import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class FileMaskTest {
    @ParameterizedTest(name = "mask \"{0}\" on {1}: {2}")
    @CsvSource(
        delimiter = '|',
        value = [
            // blank: everything
            "''               | src/Main.kt            | true",
            "'  '             | src/Main.kt            | true",
            "' , ,'           | src/Main.kt            | true",
            "'!'              | src/Main.kt            | true",
            // name globs
            "*.kt             | src/Main.kt            | true",
            "*.kt             | src/Main.java          | false",
            "*.kt             | Main.kts               | false",
            "Main.??          | src/Main.kt            | true",
            "Main.??          | src/Main.kts           | false",
            "Main.kt          | deep/er/Main.kt        | true",
            "main.KT          | src/Main.kt            | true",
            "' *.kt , *.md '  | docs/a.md              | true",
            "*.kt,*.md        | docs/a.txt             | false",
            "*Test*           | src/MainTest.kt        | true",
            // path globs
            "src/*.kt         | src/Main.kt            | true",
            "src/*.kt         | src/a/Main.kt          | false",
            "src/*.kt         | other/src/Main.kt      | false",
            "/src/*.kt        | src/Main.kt            | true",
            "/Main.kt         | Main.kt                | true",
            "/Main.kt         | src/Main.kt            | false",
            "!/README.md      | README.md              | false",
            "!/README.md      | docs/README.md         | true",
            // a run of stars is one **
            "***.kt           | src/Main.kt            | true",
            "src/**/**/*.kt   | src/Main.kt            | true",
            "src/**/**/*.kt   | src/a/b/Main.kt        | true",
            "src/**/**/*.kt   | other/Main.kt          | false",
            "src/**           | src/a/b/Main.kt        | true",
            "src/**/*.kt      | src/Main.kt            | true",
            "src/**/*.kt      | src/a/b/Main.kt        | true",
            "**/test/*.kt     | test/A.kt              | true",
            "**/test/*.kt     | m/test/A.kt            | true",
            "**/test/*.kt     | m/test/x/A.kt          | false",
            "SRC/*.KT         | src/Main.kt            | true",
            // one-character names: every token reaches the last character
            "*                | src/a                | true",
            "**               | src/a                | true",
            "?                | src/a                | true",
            "**/a             | a                    | true",
            // ? and * stay inside one path part, ** and **/ cross them, **/ ends only after a /
            "a?b/x            | a/b/x                | false",
            "a?b/x            | aXb/x                | true",
            "a*b/x            | a/b/x                | false",
            "a**b/x           | a/b/x                | true",
            "src/**/Main.kt   | src/aMain.kt         | false",
            "src/**/Main.kt   | src/a/Main.kt        | true",
            "src/**/Main.kt   | src/Main.kt          | true",
            "src/**Main.kt    | src/aMain.kt         | true",
            // only excludes: everything else
            "!*Test.kt        | src/MainTest.kt        | false",
            "!*Test.kt        | src/Main.kt            | true",
            "'! *Test.kt'     | src/MainTest.kt        | false",
            "!docs/adr/*      | docs/adr/0001.md       | false",
            "!docs/adr/*      | docs/a.md              | true",
            // mixed
            "'*.kt, !*Test.kt' | src/Main.kt           | true",
            "'*.kt, !*Test.kt' | src/MainTest.kt       | false",
            "'*.kt, !*Test.kt' | README.md             | false",
            "'*.md, !docs/adr/*' | docs/adr/0001.md    | false",
            "'*.md, !docs/adr/*' | docs/x.md           | true",
            // odd characters are literal
            "[a].kt           | src/[a].kt             | true",
            "[a].kt           | src/a.kt               | false",
            "{a,b}.kt         | src/b.kt               | false",
            "a+b.kt           | a+b.kt                 | true",
            "a+b.kt           | aab.kt                 | false",
            "a.kt             | abkt                   | false",
            "'(x'             | (x                     | true",
            "'\\E.kt'         | \\E.kt                 | true",
        ],
    )
    fun `a mask matches by name or path`(
        mask: String,
        relative: String,
        expected: Boolean,
    ) {
        val root = Path.of("/project")

        assertEquals(expected, FileMask.parse(mask).matches(root, root.resolve(relative)))
    }

    @Test
    fun `a file without a name is matched by the empty name`() {
        val root = Path.of("/")

        assertEquals(true, FileMask.parse("*").matches(root, root))
        assertEquals(false, FileMask.parse("*.kt").matches(root, root))
        assertEquals(true, FileMask.parse("!*.kt").matches(root, root))
    }

    @Test
    fun `a mask of many stars does not backtrack`() {
        val root = Path.of("/project")
        val mask = FileMask.parse("*a*a*a*a*a*a*a*a*b, a/" + "**/".repeat(30) + "x")
        val names = listOf("a".repeat(200), "a/" + "b".repeat(200))

        // A backtracking matcher (a regex) takes many seconds for the first name.
        assertTimeoutPreemptively(Duration.ofSeconds(1)) {
            names.forEach { assertFalse(mask.matches(root, root.resolve(it))) }
        }
    }
}

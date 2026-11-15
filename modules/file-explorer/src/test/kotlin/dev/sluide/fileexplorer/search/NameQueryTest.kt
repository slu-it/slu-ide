package dev.sluide.fileexplorer.search

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import kotlin.test.assertEquals

class NameQueryTest {
    @ParameterizedTest(name = "{0} on {1} -> {2}")
    @CsvSource(
        "app,        src/App.kt,        true",
        "APP,        src/app.kt,        true",
        "xyz,        src/App.kt,        false",
        "src,        src/App.kt,        false",
        "main/App,   src/main/App.kt,   true",
        "src/main,   src/main,          true",
        "src/main,   src/main/App.kt,   false",
        "src/main/,  src/main/App.kt,   false",
        "/App,       src/App.kt,        true",
        "b/ab,       ab/ab/abc,         true",
        "MAIN/app,   src/main/App.kt,   true",
        "/build,     build,             true",
        "/build,     x/build,           true",
        "/build,     mybuild,           false",
        "/build,     x/mybuild,         false",
    )
    fun matches(
        text: String,
        relativePath: String,
        expected: Boolean,
    ) {
        val name = relativePath.substringAfterLast('/')
        assertEquals(expected, NameQuery(text).matches(relativePath, name))
    }

    @ParameterizedTest(name = "{0} in {1} ({2}) -> {3}")
    @CsvSource(
        "app,            App.kt,           src/App.kt,         0..2",
        "main,           src/main/kotlin,  x/src/main/kotlin,  4..7",
        "main/kot,       src/main/kotlin,  x/src/main/kotlin,  4..11",
        "x/src/main/kot, src/main/kotlin,  x/src/main/kotlin,  0..11",
        "src/main,       src/main/kotlin,  x/src/main/kotlin,  0..7",
        "src/main/,      src/main/kotlin,  x/src/main/kotlin,  none",
        "MAIN/APP,       App.kt,           src/main/App.kt,    0..2",
        "b/ab,           abc,              ab/ab/abc,          0..1",
        "/app,           App.kt,           src/App.kt,         0..2",
        "/build,         build,            x/build,            0..4",
        "xyz,            App.kt,           src/App.kt,         none",
        "ide,            slu-ide,          '',                 none",
        "ide,            slu-ide,          src/App.kt,         none",
        "main/App,       proj,             src/main/App.kt,    none",
    )
    fun highlight(
        text: String,
        shown: String,
        relativePath: String,
        expected: String,
    ) {
        val range = expected.takeIf { it != "none" }?.split("..")?.let { it[0].toInt()..it[1].toInt() }
        assertEquals(range, NameQuery(text).highlight(shown, relativePath))
    }
}

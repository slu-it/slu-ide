package dev.sluide.java

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class JdtlsLogPathTest {
    private val root = Path.of("/home/u/.sluide/jdtls/workspaces")

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(
        "/home/u/.sluide/jdtls/workspaces/app-1234/data/.metadata/.log, true",
        // Only the folder above .metadata decides: .metadata directly under a workspace, or under another folder.
        "/home/u/.sluide/jdtls/workspaces/app-1234/other/.metadata/.log, false",
        "/home/u/.sluide/jdtls/workspaces/app-1234/.metadata/.log, false",
        // Only the folder's name below the workspace decides.
        "/home/u/.sluide/jdtls/workspaces/app-1234/data/meta/.log, false",
        // Only the file's name decides.
        "/home/u/.sluide/jdtls/workspaces/app-1234/data/.metadata/.log.1, false",
        "/home/u/.sluide/jdtls/workspaces/app-1234/data/.metadata/other.log, false",
        // Only the depth below the root decides.
        "/home/u/.sluide/jdtls/workspaces/a/b/data/.metadata/.log, false",
        "/home/u/.sluide/jdtls/workspaces/data/.metadata/.log, false",
        // Only the root decides.
        "/elsewhere/app-1234/data/.metadata/.log, false",
        "/home/u/.sluide/jdtls/other/app-1234/data/.metadata/.log, false",
        "/home/u/.sluide/jdtls/workspaces2/app-1234/data/.metadata/.log, false",
        // Dot segments resolve before the rule applies.
        "/home/u/.sluide/jdtls/workspaces/app-1234/x/../data/.metadata/.log, true",
        "/home/u/.sluide/jdtls/workspaces/../workspaces/app-1234/data/.metadata/.log, true",
        "/home/u/.sluide/jdtls/workspaces/app-1234/data/.metadata/../.metadata/.log, true",
        "/home/u/.sluide/jdtls/workspaces/app-1234/data/.metadata/../other/.log, false",
        "/home/u/.sluide/jdtls/workspaces/../../elsewhere/app-1234/data/.metadata/.log, false",
    )
    fun `the file is a jdtls log only in the real layout`(
        file: String,
        expected: Boolean,
    ) {
        assertEquals(expected, isJdtlsLog(Path.of(file), root))
    }

    @Test
    fun `a root with dot segments is normalized too`() {
        assertEquals(
            true,
            isJdtlsLog(
                Path.of("/home/u/.sluide/jdtls/workspaces/app-1234/data/.metadata/.log"),
                Path.of("/home/u/x/../.sluide/jdtls/workspaces"),
            ),
        )
    }

    @Test
    fun `a relative file is made absolute first`() {
        assertEquals(true, isJdtlsLog(Path.of("ws/app-1234/data/.metadata/.log"), Path.of("ws")))
    }
}

package dev.sluide.ide.help

import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

class AboutDialogTest {
    private val settings = Path.of("/home/u/.sluide")
    private val log = Path.of("/home/u/.sluide/logs/sluide.log")

    private fun lines(
        version: String?,
        osVersion: String? = "6.1",
    ) = aboutLines(
        version = version,
        javaVendor = "Acme",
        javaVersion = "25.0.1+9",
        javaFxVersion = "25.0.1",
        osName = "Linux",
        osVersion = osVersion,
        osArch = "amd64",
        settingsFolder = settings,
        logFile = log,
    )

    @Test
    fun `lists version, runtimes, OS and the folders`() {
        assertEquals(
            listOf(
                "Version" to "1.2.3",
                "Java" to "Acme 25.0.1+9",
                "JavaFX" to "25.0.1",
                "OS" to "Linux 6.1 (amd64)",
                "Settings" to "/home/u/.sluide",
                "Log file" to "/home/u/.sluide/logs/sluide.log",
                "Diagnostics" to "/home/u/.sluide/diagnostics",
            ),
            lines("1.2.3"),
        )
    }

    @Test
    fun `an unknown version says unknown`() {
        assertEquals("unknown", lines(null).first().second)
    }

    @Test
    fun `a missing OS version leaves no gap`() {
        assertEquals("OS" to "Linux (amd64)", lines("1", osVersion = null)[3])
    }

    @Test
    fun `the copied text has one key and value per line`() {
        assertEquals("Version: 1\nJava: x y", aboutText(listOf("Version" to "1", "Java" to "x y")))
    }
}

package dev.sluide.httpclient.env

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

class EnvironmentFilesTest {
    @TempDir
    lateinit var root: Path

    private fun file(relative: String): Path {
        val file = root.resolve(relative)
        file.parent.createDirectories()
        file.writeText("{}")
        return file
    }

    @Test
    fun `finds both files next to the http file`() {
        val public = file("api/http-client.env.json")
        val private = file("api/http-client.private.env.json")

        assertEquals(EnvironmentFiles(public, private), EnvironmentFiles.find(root.resolve("api/a.http"), root))
    }

    @Test
    fun `finds each file independently, the nearest one up to the project root`() {
        file("http-client.env.json")
        val nearerPublic = file("api/http-client.env.json")
        val private = file("http-client.private.env.json")

        assertEquals(
            EnvironmentFiles(nearerPublic, private),
            EnvironmentFiles.find(root.resolve("api/v1/deep/a.http"), root),
        )
    }

    @Test
    fun `does not look above the project root`() {
        file("http-client.env.json")
        val project = root.resolve("project").createDirectories()

        assertEquals(EnvironmentFiles(null, null), EnvironmentFiles.find(project.resolve("sub/a.http"), project))
    }

    @Test
    fun `looks only in the file's folder without a project root or outside of it`() {
        file("http-client.env.json")
        val elsewhere = root.resolve("elsewhere").createDirectories()

        assertEquals(EnvironmentFiles(null, null), EnvironmentFiles.find(root.resolve("sub/a.http"), null))
        assertEquals(EnvironmentFiles(null, null), EnvironmentFiles.find(root.resolve("sub/a.http"), elsewhere))
        assertEquals(
            EnvironmentFiles(root.resolve("http-client.env.json"), null),
            EnvironmentFiles.find(root.resolve("a.http"), null),
        )
    }
}

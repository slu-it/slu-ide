package dev.sluide.translation.session

import dev.sluide.localmodels.InstallState
import dev.sluide.localmodels.ModelDownloader
import dev.sluide.localmodels.ModelFile
import dev.sluide.localmodels.ModelInstaller
import dev.sluide.localmodels.ModelSpecs
import dev.sluide.translation.engine.PairModel
import dev.sluide.translation.engine.Quality
import java.net.URI
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/** The adapter over a real installer whose downloads run in place and only mark their key as on disk. */
class InstallerModelInstallsTest {
    private val installed = PairModel("de", "en", Quality.MEDIUM)
    private val missing = PairModel("en", "sv", Quality.MEDIUM)
    private val onDisk = mutableSetOf(installed)
    private var downloads = 0

    private val specs =
        object : ModelSpecs<PairModel> {
            override fun files(key: PairModel) = listOf(ModelFile(key.pair, URI("http://localhost/${key.pair}"), 4, ""))

            override fun folder(key: PairModel): Path = Path.of("unused")

            override fun installed(key: PairModel) = key in onDisk

            override fun taskName(
                key: PairModel,
                percent: Int,
            ) = "Downloading ${key.pair} $percent %"
        }

    private val downloader =
        object : ModelDownloader() {
            override fun download(
                files: List<ModelFile>,
                folder: Path,
                progress: (Double) -> Unit,
                cancelled: () -> Boolean,
            ) {
                downloads++
                onDisk += listOf(installed, missing).filter { key -> files.any { it.path == key.pair } }
            }
        }

    private val installs =
        InstallerModelInstalls(
            ModelInstaller(specs, downloader, { it.run() }, Runnable::run),
            { models -> 7L * models.size },
            null,
        )

    @Test
    fun `downloads every model, an installed one fetching nothing, and tells the watcher until closed`() {
        var changes = 0
        val watch = installs.watch(listOf(installed, missing)) { changes++ }
        assertEquals(InstallState.NotInstalled, installs.state(missing))
        assertEquals(14, installs.missingBytes(listOf(installed, missing)))

        installs.download(listOf(installed, missing))

        // The installed one too: a watcher that missed its install hears of it (Downloading, then Installed again).
        assertEquals(2, downloads)
        assertEquals(InstallState.Installed, installs.state(installed))
        assertEquals(InstallState.Installed, installs.state(missing))
        assertEquals(4, changes, "downloading, then installed, for each")
        watch.close()
        onDisk -= missing
        installs.download(listOf(missing))
        assertEquals(4, changes)
    }
}

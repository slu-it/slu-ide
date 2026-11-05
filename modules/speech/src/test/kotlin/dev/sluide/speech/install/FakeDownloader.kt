package dev.sluide.speech.install

import dev.sluide.localmodels.ModelDownloader
import dev.sluide.localmodels.ModelFile
import java.nio.file.Path

/** A downloader that runs [work] with the files, the progress callback and the cancel check instead of fetching. */
internal fun fakeDownloader(
    work: (files: List<ModelFile>, progress: (Double) -> Unit, cancelled: () -> Boolean) -> Unit = { _, _, _ -> },
): ModelDownloader =
    object : ModelDownloader() {
        override fun download(
            files: List<ModelFile>,
            folder: Path,
            progress: (Double) -> Unit,
            cancelled: () -> Boolean,
        ) = work(files, progress, cancelled)
    }

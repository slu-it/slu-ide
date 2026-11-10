package dev.sluide.servertools

import dev.sluide.localmodels.ModelDownloader
import dev.sluide.localmodels.ModelFile
import dev.sluide.localmodels.ToolInstaller
import dev.sluide.localmodels.ToolRelease
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.writeBytes

/**
 * A real [ToolInstaller] for [tool], whose downloads write a tar.gz holding a shell script as the executable (it
 * prints `<versionPrefix>0.0.99`) and whose work runs when the test calls [runQueued]; results come back at once.
 * The fake platform is the tool's own.
 */
class ServerToolFakes(
    val tool: ServerTool,
) {
    private val queued = ArrayDeque<Runnable>()

    /** The files the installs downloaded, in order. */
    val downloaded = mutableListOf<ModelFile>()

    /** What an install's smoke test answers: null for "works". */
    var smoke: (Path) -> String? = { null }

    private val downloader =
        object : ModelDownloader() {
            override fun download(
                files: List<ModelFile>,
                folder: Path,
                progress: (Double) -> Unit,
                cancelled: () -> Boolean,
            ) {
                downloaded += files
                progress(0.5)
                val file = files.single()
                folder.resolve(file.path).writeBytes(archive(tool.executableIn(file.path), tool.versionPrefix))
            }
        }

    /** A [ToolInstaller] on the tool's root over the fake download, the [smoke] test and the queued executor. */
    fun tools() = ToolInstaller(tool.root, { smoke(it) }, tool::taskName, downloader, { queued += it }, { it.run() })

    /** The release of [version] with the pinned asset of the tool's platform. */
    fun release(version: String): ToolRelease = tool.release(version, checkNotNull(tool.pinnedAsset))

    /** Runs the queued work of the installer, also work queued meanwhile. */
    fun runQueued() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    val hasQueuedWork: Boolean get() = queued.isNotEmpty()

    companion object {
        /** A tar.gz with the folders of [executable] and [executable] as a shell script printing a version. */
        fun archive(
            executable: String,
            versionPrefix: String,
        ): ByteArray {
            val tar = ByteArrayOutputStream()
            val segments = executable.split('/').dropLast(1)
            for (i in segments.indices) tar.write(header(segments.take(i + 1).joinToString("/") + "/", 0, '5', "755"))
            val script = "#!/bin/sh\necho '${versionPrefix}0.0.99'\n".toByteArray()
            tar.write(header(executable, script.size, '0', "755"))
            tar.write(script.copyOf((script.size + 511) / 512 * 512))
            tar.write(ByteArray(1024))
            val gzip = ByteArrayOutputStream()
            GZIPOutputStream(gzip).use { it.write(tar.toByteArray()) }
            return gzip.toByteArray()
        }

        private fun header(
            name: String,
            size: Int,
            type: Char,
            mode: String,
        ): ByteArray {
            val header = ByteArray(512)

            fun put(
                offset: Int,
                text: String,
            ) = text.toByteArray(Charsets.ISO_8859_1).copyInto(header, offset)
            put(0, name)
            put(100, mode.padStart(7, '0') + "\u0000")
            put(108, "0000000\u0000")
            put(116, "0000000\u0000")
            put(124, size.toString(8).padStart(11, '0') + "\u0000")
            put(136, "00000000000\u0000")
            put(148, "        ")
            header[156] = type.code.toByte()
            put(257, "ustar\u0000")
            put(263, "00")
            val checksum = header.sumOf { it.toInt() and 0xff }
            put(148, checksum.toString(8).padStart(6, '0') + "\u0000 ")
            return header
        }
    }
}

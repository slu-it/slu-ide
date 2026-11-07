package dev.sluide.python

import dev.sluide.localmodels.ModelDownloader
import dev.sluide.localmodels.ModelFile
import dev.sluide.localmodels.ToolInstaller
import dev.sluide.localmodels.ToolRelease
import dev.sluide.settings.SettingsStore
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.zip.GZIPOutputStream
import kotlin.io.path.writeBytes

/** The asset of the fake pin, on the fake platform `linux-aarch64`. */
internal const val FAKE_ASSET = "ty-aarch64-unknown-linux-musl.tar.gz"

/**
 * A [TyInstaller] over a real [ToolInstaller] in [dir], whose downloads write a tar.gz holding a shell script as ty and
 * whose work runs when the test calls [runQueued]; results come back at once.
 */
internal class TyFakes(
    private val dir: Path,
) {
    val root: Path = dir.resolve("tools/ty")
    val pin = TyPin("0.0.99", mapOf("linux-aarch64" to TyAsset(FAKE_ASSET, 13_659_057, "a".repeat(64))))
    val settings by lazy { PythonSettings(SettingsStore(dir.resolve("settings.properties"))) }
    private val queued = ArrayDeque<Runnable>()
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
                folder.resolve(file.path).writeBytes(tyArchive(TyPin.executableIn(file.path)))
            }
        }

    fun tools() = ToolInstaller(root, { smoke(it) }, TyInstaller::taskName, downloader, { queued += it }, { it.run() })

    fun installer(platform: String? = "linux-aarch64") = TyInstaller(settings, pin, platform, tools(), root)

    fun release(version: String): ToolRelease = TyPin.release(version, pin.asset("linux-aarch64")!!)

    /** Runs the queued work of the installer, also work queued meanwhile. */
    fun runQueued() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    val hasQueuedWork: Boolean get() = queued.isNotEmpty()

    companion object {
        /** A tar.gz with the folder of [executable] and [executable] as a shell script printing a ty version. */
        fun tyArchive(executable: String): ByteArray {
            val tar = ByteArrayOutputStream()
            tar.write(header(executable.substringBeforeLast('/') + "/", 0, '5', "755"))
            val script = "#!/bin/sh\necho ty 0.0.99\n".toByteArray()
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
